# PULSE codegen example: DQValidator at the silver→gold boundary.
#
# What this blueprint does (and what it does NOT):
#   - Asserts BUSINESS invariants the gold layer publishes externally
#     (executive dashboards, downstream marts, regulatory reporting).
#   - Bronze→silver gate is structural ("PK not-null, columns present").
#     Silver→gold gate is semantic ("balance ≥ 0", "amount > 0 for
#     non-reversal txns", "no rows with future event_ts").
#   - On failure, raises immediately. Gold publishes are NEVER silently
#     skipped; an incident is louder than a stale dashboard.
#   - Severity is hard-coded to ERROR here — the silver→gold boundary is
#     where wrong data becomes externally visible. Use AnomalyDetection's
#     SEVERITY=WARN if you want soft signals.
#
# Convention notes:
#   - __PLACEHOLDER__ tokens are codegen-time. PULSE_* env vars runtime-only.
#   - BUSINESS_INVARIANTS_JSON is a JSON-encoded list of {column, min, max,
#     allow_null} dicts — kept declarative so the codegen agent can author
#     more invariants from the user's chat description without touching
#     this file's structure.

from datetime import date
import json
import logging
import os

import great_expectations as gx
from great_expectations.checkpoint import UpdateDataDocsAction
from pyspark.sql import SparkSession
from pyspark.sql import functions as F


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
# Storage convention (#30) — read silver (format-branched).
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"
SILVER_TABLE = "__SILVER_TABLE__"
PRIMARY_KEY = "__PRIMARY_KEY__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"

# Business invariants — the codegen agent expands this list from the
# user's description. Each entry is one expectation. Example:
#   [{"column": "balance", "min": 0, "max": null, "allow_null": false},
#    {"column": "txn_amount", "min": 0.01, "max": 1e9, "allow_null": false}]
BUSINESS_INVARIANTS = json.loads("__BUSINESS_INVARIANTS_JSON__")

MIN_ROWS_PER_PARTITION = __MIN_ROWS_PER_PARTITION__
GX_DOCS_PATH = "__GX_DOCS_PATH__"
_split = GX_DOCS_PATH.replace("://", " ").split(" ", 1)
_bucket_split = _split[1].split("/", 1) if len(_split) == 2 else ["", ""]
GX_DOCS_BUCKET = _bucket_split[0]
GX_DOCS_PREFIX = _bucket_split[1] if len(_bucket_split) > 1 else ""


log = logging.getLogger("pulse.dq_validator.silver_gold")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-dq-sg-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    def _read_silver():
        if LAKE_FORMAT == "delta": return spark.read.format("delta").load(TABLE_PATH)
        if LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
            return spark.read.format("iceberg").load(TABLE_PATH)
        if LAKE_FORMAT == "parquet": return spark.read.format("parquet").load(TABLE_PATH)
        if LAKE_FORMAT == "bq_native":
            return spark.read.format("bigquery").option("table", BQ_TABLE).load()
        raise ValueError(f"Unsupported LAKE_FORMAT: {LAKE_FORMAT}")
    log.info("DQValidator(silver→gold) start. path=%s as_of=%s invariants=%d",
             LAKE_FORMAT, AS_OF_DATE, len(BUSINESS_INVARIANTS))

    df = (
        _read_silver()
        .filter(F.col(PARTITION_COLUMN) == AS_OF_DATE.isoformat())
    )

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
    asset = ds.add_dataframe_asset(name=SILVER_TABLE)
    batch = asset.add_batch_definition_whole_dataframe(
        name=f"{SILVER_TABLE}_{AS_OF_DATE.isoformat()}",
    )

    suite = gx.ExpectationSuite(name=f"{SILVER_TABLE}_silver_gold_gate")
    # Volume floor.
    if MIN_ROWS_PER_PARTITION > 0:
        suite.add_expectation(gx.expectations.ExpectTableRowCountToBeBetween(
            min_value=MIN_ROWS_PER_PARTITION,
        ))
    # PK still must hold at the gold boundary.
    suite.add_expectation(gx.expectations.ExpectColumnValuesToNotBeNull(column=PRIMARY_KEY))

    # Expand the declarative invariants into expectations.
    for inv in BUSINESS_INVARIANTS:
        col = inv["column"]
        if not inv.get("allow_null", False):
            suite.add_expectation(gx.expectations.ExpectColumnValuesToNotBeNull(column=col))
        kwargs = {"column": col}
        if inv.get("min") is not None:
            kwargs["min_value"] = inv["min"]
        if inv.get("max") is not None:
            kwargs["max_value"] = inv["max"]
        if "min_value" in kwargs or "max_value" in kwargs:
            suite.add_expectation(gx.expectations.ExpectColumnValuesToBeBetween(**kwargs))

    suite = context.suites.add(suite)

    val_def = context.validation_definitions.add(
        gx.ValidationDefinition(name=f"{SILVER_TABLE}_sg_val",
                                data=batch, suite=suite),
    )
    checkpoint = context.checkpoints.add(
        gx.Checkpoint(
            name=f"{SILVER_TABLE}_sg_checkpoint",
            validation_definitions=[val_def],
            actions=[UpdateDataDocsAction(name="update_docs")],
        )
    )
    result = checkpoint.run(batch_parameters={"dataframe": df})

    log.info("gate result: success=%s statistics=%s",
             result.success, result.statistics)

    if not result.success:
        # No quarantine here — the gold-side fix-up is to investigate the
        # silver upstream, not to publish a different gold partition.
        spark.stop()
        raise RuntimeError(
            f"Silver→gold DQ gate failed for {SILVER_TABLE} on "
            f"{AS_OF_DATE}: {result.statistics}. Gold publish blocked."
        )

    spark.stop()


if __name__ == "__main__":
    main()
