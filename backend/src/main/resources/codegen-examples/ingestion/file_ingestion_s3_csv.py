# PULSE codegen example: FileIngestion — daily CSV file from S3 → bronze.
#
# What this blueprint does (and what it does NOT):
#   - Reads a SINGLE dated file (or glob) from object storage and lands
#     it in bronze with audit columns. No transform.
#   - The dated key is built from V93 split fields: bucket, path_prefix,
#     filename_pattern, pattern_kind, date_format, date_value. This is
#     the same convention as the FileArrivalSensor — same fields →
#     same key string. The agent SHOULD propose the same values to
#     both blueprints when they're co-instantiated.
#   - Idempotent on date_value: re-running the same business date
#     overwrites only that ds partition (Delta replaceWhere).
#   - Schema-on-read for CSV: schema inference is OK for bronze
#     because silver applies a typed contract. For Parquet/Avro/ORC
#     the schema is on the file itself, so use the typed read path.
#
# Convention notes:
#   - __PLACEHOLDER__ tokens are codegen-time. PULSE_* env vars runtime-only.
#   - Date inputs (date_value) are RESOLVED AT RUNTIME via pulse_dates
#     so a 'PBD' (previous business day) value picks the right key
#     even on Tuesday after a Monday holiday.
#   - S3 credentials come from the Airflow connection (aws_default).
#     Plaintext credentials are forbidden.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from pulse_dates import resolve_mnemonic
from pulse_pipeline_state import advance_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

# V93 FileIngestion split fields.
SOURCE_BUCKET = "__SOURCE_BUCKET__"               # source-system landing bucket
PATH_PREFIX = "__PATH_PREFIX__"                   # 'inbound/loan_master/'
FILENAME_PATTERN = "__FILENAME_PATTERN__"         # 'loan_master_{date}.csv'
PATTERN_KIND = "__PATTERN_KIND__"                 # 'literal' | 'date_template' | 'glob'
DATE_FORMAT = "__DATE_FORMAT__"                   # strftime, e.g. '%Y%m%d'
DATE_VALUE = "__DATE_VALUE__"                     # mnemonic OR ISO literal
HOLIDAY_CALENDAR_ID = "__HOLIDAY_CALENDAR_ID__"
FISCAL_OFFSET_MONTHS = __FISCAL_OFFSET_MONTHS__
EXPECTED_SIZE_MIN = __EXPECTED_SIZE_MIN__         # bytes

CSV_HAS_HEADER = __CSV_HAS_HEADER__               # bool
CSV_DELIMITER = "__CSV_DELIMITER__"

# Storage convention (#30) — bronze write uses TABLE_PATH; the inbound
# file location (SOURCE_BUCKET above) is the SOURCE-system's bucket and
# is NOT governed by PULSE's lake convention.
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"


log = logging.getLogger("pulse.file_ingestion.s3_csv")
log.setLevel(logging.INFO)


def resolve_arrival_date():
    return resolve_mnemonic(
        DATE_VALUE, as_of=AS_OF_DATE,
        calendar_id=HOLIDAY_CALENDAR_ID,
        fiscal_offset_months=FISCAL_OFFSET_MONTHS,
    )


def build_object_key(arrival_date):
    """Apply pattern_kind to produce the S3 key (or glob).

    Mirrors the convention in file_arrival_sensor.py so the sensor and
    the ingest task land on the same key for the same run.
    """
    if PATTERN_KIND == "literal":
        return f"{PATH_PREFIX}{FILENAME_PATTERN}"
    if PATTERN_KIND == "glob":
        return f"{PATH_PREFIX}{FILENAME_PATTERN}"
    if PATTERN_KIND == "date_template":
        formatted = arrival_date.strftime(DATE_FORMAT)
        return f"{PATH_PREFIX}{FILENAME_PATTERN.replace('{date}', formatted)}"
    raise ValueError(f"Unsupported pattern_kind: {PATTERN_KIND}")


def main():
    arrival_date = resolve_arrival_date()
    object_key = build_object_key(arrival_date)
    full_uri = f"s3a://{SOURCE_BUCKET}/{object_key}"

    spark = (
        SparkSession.builder
        .appName(f"pulse-file-ingest-{PIPELINE_NAME}-{RUN_ID}")
        .config("spark.sql.adaptive.enabled", "true")
        .getOrCreate()
    )
    log.info("FileIngestion(s3/csv) start. uri=%s arrival=%s as_of=%s",
             full_uri, arrival_date, AS_OF_DATE)

    df = (
        spark.read
        .option("header", "true" if CSV_HAS_HEADER else "false")
        .option("inferSchema", "true")
        .option("delimiter", CSV_DELIMITER)
        .option("mode", "PERMISSIVE")           # bronze tolerates malformed; silver enforces
        .option("columnNameOfCorruptRecord", "_pulse_corrupt_record")
        .csv(full_uri)
    )

    # Volume guard — log only; the sensor's check_fn already enforced
    # min size, but a downstream change could remove that step.
    if EXPECTED_SIZE_MIN > 0:
        try:
            input_files = df.inputFiles()
            log.info("input file(s): %s", input_files)
        except Exception as exc:
            log.warning("could not introspect input files: %s", exc)

    audited = (
        df
        .withColumn("ds", F.lit(arrival_date.isoformat()))
        .withColumn("_pulse_business_date", F.lit(AS_OF_DATE.isoformat()))
        .withColumn("_pulse_arrival_date", F.lit(arrival_date.isoformat()))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{object_key}"))
    )

    replace_where = f"ds = '{arrival_date.isoformat()}'"
    written = audited.cache()
    row_count = written.count()
    log.info("FileIngestion writing rows=%d format=%s replaceWhere=%s",
             row_count, LAKE_FORMAT, replace_where)

    if LAKE_FORMAT == "delta":
        (written.write.format("delta")
         .mode("overwrite").option("replaceWhere", replace_where)
         .partitionBy("ds").save(TABLE_PATH))
    elif LAKE_FORMAT == "iceberg_external":
        written.sparkSession.sql(
            f"DELETE FROM iceberg.`{TABLE_PATH}` WHERE {replace_where}"
        )
        written.writeTo(f"iceberg.`{TABLE_PATH}`").append()
    elif LAKE_FORMAT == "iceberg_bq_managed":
        (written.write.format("bigquery")
         .option("table", BQ_TABLE if BQ_TABLE else TABLE_PATH)
         .option("writeMethod", "indirect").mode("append").save())
    elif LAKE_FORMAT == "parquet":
        (written.write.format("parquet")
         .mode("overwrite").option("partitionOverwriteMode", "dynamic")
         .partitionBy("ds").save(TABLE_PATH))
    else:
        raise ValueError(
            f"Unsupported LAKE_FORMAT for FileIngestion bronze: {LAKE_FORMAT}"
        )
    written.unpersist()

    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=arrival_date.isoformat(), row_count=row_count,
    )
    log.info("FileIngestion complete. rows=%d arrival=%s", row_count, arrival_date)
    spark.stop()


if __name__ == "__main__":
    main()
