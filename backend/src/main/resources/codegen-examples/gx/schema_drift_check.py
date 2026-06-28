# PULSE codegen example: SchemaDriftDetection — guard a silver/gold table
# against unexpected upstream schema evolution.
#
# What this blueprint does (and what it does NOT):
#   - Asserts the dataset's column SET matches the contract registered in
#     PULSE's schema_propagation registry (Agent E). Extra columns OR
#     missing columns are both drift.
#   - Per-column type assertions catch the silent flavor: a column that
#     was DECIMAL(18,4) in the contract but arrives as DOUBLE in the
#     latest write. Most warehouses will accept this and silently lose
#     precision; this gate is the only thing that catches it.
#   - DOES NOT mutate the contract. If the user wants to accept new
#     columns or a type widening, they propose a contract update via
#     PULSE's schema-override flow (#43 → blueprint params_schema), not
#     by editing this generated file.
#   - DOES NOT enforce business invariants — that's DQValidator. This
#     gate is a structural guard.
#
# Convention notes:
#   - EXPECTED_SCHEMA_JSON is the contract serialized at codegen time
#     from the schema_propagation registry, so this gate fails closed
#     when the user forgot to register a new column.
#   - Severity: "ERROR" raises (default for gold-feeding pipelines);
#     "WARN" logs the drift and continues. The agent SHOULD set ERROR
#     for any pipeline that publishes externally.

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
# Storage convention (#30) — format-branched read of the target table.
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"
TARGET_TABLE = "__TARGET_TABLE__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"

# Contract: list of {"name": str, "type": str, "nullable": bool} from
# the schema_propagation registry, JSON-encoded at codegen time.
EXPECTED_SCHEMA = json.loads("__EXPECTED_SCHEMA_JSON__")

# Severity: "ERROR" | "WARN"
SEVERITY = "__SEVERITY__"

# When True, allow extra columns beyond the contract (still warn). False
# (the strict default) treats any extra column as a hard failure.
ALLOW_ADDITIONAL_COLUMNS = __ALLOW_ADDITIONAL_COLUMNS__

GX_DOCS_PATH = "__GX_DOCS_PATH__"
_split = GX_DOCS_PATH.replace("://", " ").split(" ", 1)
_bucket_split = _split[1].split("/", 1) if len(_split) == 2 else ["", ""]
GX_DOCS_BUCKET = _bucket_split[0]
GX_DOCS_PREFIX = _bucket_split[1] if len(_bucket_split) > 1 else ""


log = logging.getLogger("pulse.schema_drift")
log.setLevel(logging.INFO)


def _spark_to_contract_type(spark_type: str) -> str:
    # Normalize Spark's printable type strings to the contract's vocabulary.
    # Keep this map small; expand only when a real schema needs it.
    if spark_type.startswith("decimal("):
        return "decimal"
    return {
        "string": "string", "varchar": "string",
        "int": "int", "integer": "int", "bigint": "long", "long": "long",
        "double": "double", "float": "float",
        "boolean": "boolean", "bool": "boolean",
        "date": "date", "timestamp": "timestamp",
    }.get(spark_type.lower(), spark_type.lower())


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-schema-drift-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    def _read_target():
        if LAKE_FORMAT == "delta": return spark.read.format("delta").load(TABLE_PATH)
        if LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
            return spark.read.format("iceberg").load(TABLE_PATH)
        if LAKE_FORMAT == "parquet": return spark.read.format("parquet").load(TABLE_PATH)
        if LAKE_FORMAT == "bq_native":
            return spark.read.format("bigquery").option("table", BQ_TABLE).load()
        raise ValueError(f"Unsupported LAKE_FORMAT: {LAKE_FORMAT}")
    log.info("SchemaDriftDetection start. path=%s expected_columns=%d severity=%s",
             LAKE_FORMAT, len(EXPECTED_SCHEMA), SEVERITY)

    df = (
        _read_target()
        .filter(F.col(PARTITION_COLUMN) == AS_OF_DATE.isoformat())
    )

    actual = {f.name: _spark_to_contract_type(f.dataType.simpleString())
              for f in df.schema.fields}
    expected = {col["name"]: col["type"] for col in EXPECTED_SCHEMA}

    missing = [c for c in expected.keys() if c not in actual]
    extra = [c for c in actual.keys() if c not in expected]
    type_mismatches = [
        {"column": c, "expected": expected[c], "actual": actual[c]}
        for c in expected.keys()
        if c in actual and actual[c] != expected[c]
    ]

    drift_summary = {
        "pipeline": PIPELINE_NAME,
        "run_id": RUN_ID,
        "table": TARGET_TABLE,
        "as_of": AS_OF_DATE.isoformat(),
        "missing_columns": missing,
        "extra_columns": extra,
        "type_mismatches": type_mismatches,
    }
    log.info("schema diff: %s", json.dumps(drift_summary))

    # Run a GX checkpoint too, so Data Docs has a record of every run.
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
    asset = ds.add_dataframe_asset(name=TARGET_TABLE)
    batch = asset.add_batch_definition_whole_dataframe(
        name=f"{TARGET_TABLE}_{AS_OF_DATE.isoformat()}",
    )
    suite = gx.ExpectationSuite(name=f"{TARGET_TABLE}_schema_drift")
    suite.add_expectation(gx.expectations.ExpectTableColumnsToMatchSet(
        column_set=list(expected.keys()),
        exact_match=not ALLOW_ADDITIONAL_COLUMNS,
    ))
    for col in expected.keys():
        suite.add_expectation(gx.expectations.ExpectColumnToExist(column=col))
    suite = context.suites.add(suite)
    val_def = context.validation_definitions.add(
        gx.ValidationDefinition(name=f"{TARGET_TABLE}_drift_val",
                                data=batch, suite=suite),
    )
    checkpoint = context.checkpoints.add(
        gx.Checkpoint(
            name=f"{TARGET_TABLE}_drift_checkpoint",
            validation_definitions=[val_def],
            actions=[UpdateDataDocsAction(name="update_docs")],
        )
    )
    checkpoint.run(batch_parameters={"dataframe": df})

    has_drift = bool(missing or type_mismatches or
                     (extra and not ALLOW_ADDITIONAL_COLUMNS))

    if has_drift:
        if SEVERITY == "ERROR":
            spark.stop()
            raise RuntimeError(f"Schema drift detected for {TARGET_TABLE}: {drift_summary}")
        log.warning("Schema drift detected — SEVERITY=WARN, DAG continues. %s",
                    json.dumps(drift_summary))
    else:
        log.info("schema OK — %s matches contract.", TARGET_TABLE)

    spark.stop()


if __name__ == "__main__":
    main()
