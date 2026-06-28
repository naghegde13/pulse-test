# PULSE codegen example: DQValidator at the bronze→silver boundary.
#
# What this blueprint does (and what it does NOT):
#   - Runs a Great Expectations checkpoint against today's bronze partition
#     BEFORE any silver transform consumes it.
#   - Asserts STRUCTURAL contract only: PK not-null + unique, required
#     columns present, basic type/range invariants the source MUST honor.
#   - Per #27 — basic dbt cleaning tests (e.g., trim/case-cast) belong
#     INSIDE BronzeToSilverCleaning's dbt model, not here. DQValidator is
#     for invariants the upstream system OWES us, expressed declaratively.
#   - On failure, quarantines the partition (does not silently drop) and
#     raises so Airflow halts downstream silver/gold builds.
#
# Convention notes:
#   - __PLACEHOLDER__ tokens are substituted by CodeGenerationService at
#     codegen time. PULSE_* env vars are runtime-only.
#   - REQUIRED_COLUMNS_JSON is substituted as a JSON-encoded string of
#     the user's required column list — parsed at runtime so we don't
#     embed a Python list literal directly.

from datetime import date
import json
import logging
import os

import great_expectations as gx
from great_expectations.checkpoint import UpdateDataDocsAction
from pyspark.sql import SparkSession
from pyspark.sql import functions as F


# ---------------------------------------------------------------------
# Runtime-only.
# ---------------------------------------------------------------------
RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


# ---------------------------------------------------------------------
# Static config — from blueprint params.
# ---------------------------------------------------------------------
PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"
# Storage convention (#30) — read bronze (format-branched), write
# rejected partitions to QUARANTINE_PATH.
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"
QUARANTINE_PATH = "__QUARANTINE_PATH__"
BRONZE_TABLE = "__BRONZE_TABLE__"
PRIMARY_KEY = "__PRIMARY_KEY__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"         # usually 'ds'

# REQUIRED_COLUMNS is encoded as a JSON string at codegen time so we don't
# inject a Python literal mid-file. e.g. '["account_id","event_ts","amount"]'
REQUIRED_COLUMNS = json.loads("__REQUIRED_COLUMNS_JSON__")
MIN_ROWS_PER_PARTITION = __MIN_ROWS_PER_PARTITION__   # absolute floor (0 disables)
MAX_NULL_FRACTION = __MAX_NULL_FRACTION__             # e.g. 0.01 = 1%

GX_DOCS_PATH = "__GX_DOCS_PATH__"
_split = GX_DOCS_PATH.replace("://", " ").split(" ", 1)
_bucket_split = _split[1].split("/", 1) if len(_split) == 2 else ["", ""]
GX_DOCS_BUCKET = _bucket_split[0]
GX_DOCS_PREFIX = _bucket_split[1] if len(_bucket_split) > 1 else ""


log = logging.getLogger("pulse.dq_validator.bronze_silver")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-dq-bs-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    log.info("DQValidator(bronze→silver) start. format=%s as_of=%s pk=%s",
             LAKE_FORMAT, AS_OF_DATE, PRIMARY_KEY)

    def _read_bronze():
        if LAKE_FORMAT == "delta": return spark.read.format("delta").load(TABLE_PATH)
        if LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
            return spark.read.format("iceberg").load(TABLE_PATH)
        if LAKE_FORMAT == "parquet": return spark.read.format("parquet").load(TABLE_PATH)
        if LAKE_FORMAT == "bq_native":
            return spark.read.format("bigquery").option("table", BQ_TABLE).load()
        raise ValueError(f"Unsupported LAKE_FORMAT: {LAKE_FORMAT}")

    df = _read_bronze().filter(F.col(PARTITION_COLUMN) == AS_OF_DATE.isoformat())

    context = gx.get_context(mode="ephemeral")
    context.add_data_docs_site(
        site_name="pulse_docs",
        site_config={
            "class_name": "SiteBuilder",
            "store_backend": {"class_name": "TupleS3StoreBackend",
                              "bucket": GX_DOCS_BUCKET,
                              "prefix": GX_DOCS_PREFIX},
            "site_index_builder": {"class_name": "DefaultSiteIndexBuilder"},
        },
    )
    ds = context.data_sources.add_spark(name="pulse_spark")
    asset = ds.add_dataframe_asset(name=BRONZE_TABLE)
    batch = asset.add_batch_definition_whole_dataframe(
        name=f"{BRONZE_TABLE}_{AS_OF_DATE.isoformat()}",
    )

    suite = gx.ExpectationSuite(name=f"{BRONZE_TABLE}_bronze_silver_gate")
    # PK invariants — the source contract.
    suite.add_expectation(gx.expectations.ExpectColumnValuesToNotBeNull(column=PRIMARY_KEY))
    suite.add_expectation(gx.expectations.ExpectColumnValuesToBeUnique(column=PRIMARY_KEY))
    # Required columns must exist; the source schema MUST NOT silently drop them.
    for col in REQUIRED_COLUMNS:
        suite.add_expectation(gx.expectations.ExpectColumnToExist(column=col))
        # Per-column null-fraction floor. MAX_NULL_FRACTION translates to a
        # min "mostly" threshold — GX's idiom for fuzzy not-null.
        suite.add_expectation(gx.expectations.ExpectColumnValuesToNotBeNull(
            column=col, mostly=1.0 - MAX_NULL_FRACTION,
        ))
    # Volume floor catches "source emitted 12 rows when it usually emits 12M".
    if MIN_ROWS_PER_PARTITION > 0:
        suite.add_expectation(gx.expectations.ExpectTableRowCountToBeBetween(
            min_value=MIN_ROWS_PER_PARTITION,
        ))
    suite = context.suites.add(suite)

    val_def = context.validation_definitions.add(
        gx.ValidationDefinition(name=f"{BRONZE_TABLE}_bs_val",
                                data=batch, suite=suite),
    )
    checkpoint = context.checkpoints.add(
        gx.Checkpoint(
            name=f"{BRONZE_TABLE}_bs_checkpoint",
            validation_definitions=[val_def],
            actions=[UpdateDataDocsAction(name="update_docs")],
        )
    )
    result = checkpoint.run(batch_parameters={"dataframe": df})

    log.info("gate result: success=%s statistics=%s",
             result.success, result.statistics)

    if not result.success:
        # Quarantine: write the offending partition to a parallel path so
        # we have an audit trail AND the silver pipeline can be re-run
        # against a known-bad fixture during incident triage.
        # QUARANTINE_PATH from #30 resolver:
        # {lake_bucket}/{domain}/{sor}/{pipeline}/_quarantine/{table}/{ds}/.
        # We append the run_id to disambiguate retries within the same ds.
        run_quarantine = f"{QUARANTINE_PATH.rstrip('/')}/run={RUN_ID}/"
        log.error("DQ gate failed — quarantining partition to %s", run_quarantine)
        df.write.mode("overwrite").format("delta").save(run_quarantine)
        spark.stop()
        raise RuntimeError(
            f"Bronze→silver DQ gate failed for {BRONZE_TABLE} on "
            f"{AS_OF_DATE}: {result.statistics}. Partition quarantined "
            f"at {run_quarantine}."
        )

    spark.stop()


if __name__ == "__main__":
    main()
