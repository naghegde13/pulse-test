# PULSE codegen example: CDCIngestion — incremental polling variant.
#
# What this blueprint does (and what it does NOT):
#   - Pulls rows from a JDBC source table where WATERMARK_COLUMN is
#     greater than the last-recorded high-water mark, then advances
#     the watermark to the max value seen this run. SAME blueprint
#     as cdc_debezium_postgres.py but the cdc_mode is 'poll' instead
#     of 'debezium'.
#   - High-water-mark IS persisted via pulse_pipeline_state, NOT via
#     ad-hoc REST calls. The platform-installed helper handles the
#     idempotency/transactionality for us.
#   - Append-only into bronze. Like the Debezium variant, materialization
#     into current-state silver is downstream.
#   - Use this when the source DB cannot run a Debezium connector
#     (managed RDS without logical replication, ad-hoc MySQL, etc.).
#     Otherwise prefer the Debezium variant — polling has higher
#     latency and misses physical deletes.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from pulse_pipeline_state import advance_high_water_mark, read_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")


PIPELINE_NAME = "__PIPELINE_NAME__"
INSTANCE_ID = "__INSTANCE_ID__"               # SubPipelineInstance ULID
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

JDBC_URL = "__SOURCE_JDBC_URL__"
JDBC_DRIVER = "__SOURCE_JDBC_DRIVER__"
JDBC_TABLE = "__SOURCE_JDBC_TABLE__"          # SCHEMA.TABLE
WATERMARK_COLUMN = "__WATERMARK_COLUMN__"     # event_ts | updated_at | id (any monotonic)
WATERMARK_TYPE = "__WATERMARK_TYPE__"         # 'timestamp' | 'long'
PRIMARY_KEY = "__PRIMARY_KEY__"
JDBC_NUM_PARTITIONS = __JDBC_NUM_PARTITIONS__

# Storage convention (#30).
LAKE_FORMAT = "__LAKE_FORMAT__"        # 'delta' | 'iceberg_external' | 'iceberg_bq_managed' | 'parquet'
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"          # CDC bronze table directory URI
BQ_TABLE = "__BQ_TABLE__"

# Initial watermark when the pipeline runs for the first time.
INITIAL_WATERMARK = "__INITIAL_WATERMARK__"   # ISO ts or '0' for long


JDBC_USER = os.environ["__SOURCE_NAME___USERNAME"]
JDBC_PASSWORD = os.environ["__SOURCE_NAME___PASSWORD"]


log = logging.getLogger("pulse.cdc_poll")
log.setLevel(logging.INFO)


def quote_watermark(value):
    if WATERMARK_TYPE == "timestamp":
        return f"TIMESTAMP '{value}'"
    if WATERMARK_TYPE == "long":
        return str(int(value))
    raise ValueError(f"Unsupported watermark_type: {WATERMARK_TYPE}")


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-cdc-poll-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    last = read_high_water_mark(pipeline=PIPELINE_NAME, instance_id=INSTANCE_ID)
    watermark = last["high_water_mark"] if last else INITIAL_WATERMARK
    log.info("CDCIngestion(poll) start. table=%s wm_col=%s last_hwm=%s",
             JDBC_TABLE, WATERMARK_COLUMN, watermark)

    predicate = (
        f"{WATERMARK_COLUMN} > {quote_watermark(watermark)}"
    )
    pushdown_query = f"(SELECT * FROM {JDBC_TABLE} WHERE {predicate}) src"

    df = (
        spark.read.format("jdbc")
        .option("url", JDBC_URL)
        .option("driver", JDBC_DRIVER)
        .option("user", JDBC_USER)
        .option("password", JDBC_PASSWORD)
        .option("dbtable", pushdown_query)
        # Spark JDBC partitioning — without this, the entire delta is
        # one task and a 50M-row delta pins one executor for hours.
        .option("partitionColumn", PRIMARY_KEY)
        .option("lowerBound", "0")
        .option("upperBound", str(2**31 - 1))
        .option("numPartitions", JDBC_NUM_PARTITIONS)
        .option("fetchsize", "10000")
        .load()
    )

    audited = (
        df
        .withColumn("ds", F.lit(PULSE_BUSINESS_DATE))
        .withColumn("_pulse_business_date", F.lit(PULSE_BUSINESS_DATE))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{JDBC_TABLE}"))
    )

    written = audited.cache()
    row_count = written.count()
    log.info("CDC poll delta rows=%d format=%s", row_count, LAKE_FORMAT)

    if row_count == 0:
        log.info("no new rows since hwm=%s; advancing nothing", watermark)
        written.unpersist()
        spark.stop()
        return

    if LAKE_FORMAT == "delta":
        (written.write.format("delta").mode("append")
         .partitionBy("ds").save(TABLE_PATH))
    elif LAKE_FORMAT == "iceberg_external":
        written.writeTo(f"iceberg.`{TABLE_PATH}`").append()
    elif LAKE_FORMAT == "iceberg_bq_managed":
        (written.write.format("bigquery")
         .option("table", BQ_TABLE if BQ_TABLE else TABLE_PATH)
         .option("writeMethod", "indirect")
         .mode("append").save())
    elif LAKE_FORMAT == "parquet":
        # CDC append on plain parquet — append-mode parquet works but
        # without ACID; downstream consumers must tolerate eventual
        # consistency on the listing.
        (written.write.format("parquet").mode("append")
         .partitionBy("ds").save(TABLE_PATH))
    else:
        raise ValueError(
            f"Unsupported LAKE_FORMAT for CDCIngestion(poll) bronze: {LAKE_FORMAT}"
        )

    new_max_row = written.agg(F.max(WATERMARK_COLUMN).alias("mx")).first()
    new_max = new_max_row["mx"]
    if new_max is None:
        log.warning("delta non-empty but max(%s) is null — leaving hwm unchanged",
                    WATERMARK_COLUMN)
    else:
        advance_high_water_mark(
            pipeline=PIPELINE_NAME, run_id=RUN_ID,
            high_water_mark=str(new_max), row_count=row_count,
            instance_id=INSTANCE_ID,
        )
        log.info("advanced hwm: %s → %s rows=%d", watermark, new_max, row_count)

    written.unpersist()
    spark.stop()


if __name__ == "__main__":
    main()
