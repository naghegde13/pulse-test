# PULSE codegen example: SnapshotIngestion — periodic full-table snapshot
# from a JDBC source (Oracle in this example) into bronze.
#
# What this blueprint does (and what it does NOT):
#   - Pulls the ENTIRE current state of a source table on every run and
#     overwrites the dated bronze partition for AS_OF_DATE. Use when the
#     source can't (or won't) emit CDC and the table is small enough
#     that a full read is feasible.
#   - Different from BulkBackfill: BulkBackfill replays a closed historical
#     window into bronze, ONCE. SnapshotIngestion runs every day on a
#     schedule and only ever writes today's partition.
#   - Different from CDCIngestion: CDC writes per-event change records
#     (append-only). SnapshotIngestion writes per-day full state
#     (overwrite-by-ds). Downstream SCD2Dimension can consume either,
#     but the materialization patterns are different.
#   - Idempotent on AS_OF_DATE: re-running a given day's snapshot
#     overwrites only that ds partition (Delta replaceWhere).
#
# Performance notes:
#   - JDBC partition columns + lowerBound/upperBound/numPartitions are
#     mandatory for any source table > a few million rows. Without them,
#     Spark issues a single-threaded read. The agent SHOULD ask the user
#     for a numeric primary key OR a hash() expression to partition by.
#   - fetchsize=10000 is the practical lower bound for Oracle JDBC. The
#     default 10 row fetch is brutal on multi-million-row reads.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from pulse_pipeline_state import advance_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
PULSE_PROCESSING_TS = os.environ.get("PULSE_PROCESSING_TS", "{{ ts }}")
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

JDBC_URL = "__SOURCE_JDBC_URL__"
JDBC_DRIVER = "__SOURCE_JDBC_DRIVER__"            # 'oracle.jdbc.OracleDriver'
JDBC_TABLE = "__SOURCE_JDBC_TABLE__"              # 'SCHEMA.TABLE'
PRIMARY_KEY = "__SOURCE_PRIMARY_KEY__"            # numeric column for JDBC partitioning
JDBC_LOWER_BOUND = "__JDBC_LOWER_BOUND__"         # min(PK) — codegen-substituted
JDBC_UPPER_BOUND = "__JDBC_UPPER_BOUND__"         # max(PK)
JDBC_NUM_PARTITIONS = __JDBC_NUM_PARTITIONS__
FETCH_SIZE = __FETCH_SIZE__                       # default 10000

# Storage convention (#30) — resolved by StoragePlaceholderResolver.
LAKE_FORMAT = "__LAKE_FORMAT__"        # 'delta' | 'iceberg_external' | 'iceberg_bq_managed' | 'parquet'
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"          # bronze table directory URI
BQ_TABLE = "__BQ_TABLE__"              # populated only for iceberg_bq_managed


JDBC_USER = os.environ["__SOURCE_NAME___USERNAME"]
JDBC_PASSWORD = os.environ["__SOURCE_NAME___PASSWORD"]


log = logging.getLogger("pulse.snapshot_ingestion")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-snapshot-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )
    log.info("SnapshotIngestion start. table=%s as_of=%s pk=%s partitions=%d",
             JDBC_TABLE, AS_OF_DATE, PRIMARY_KEY, JDBC_NUM_PARTITIONS)

    df = (
        spark.read.format("jdbc")
        .option("url", JDBC_URL)
        .option("driver", JDBC_DRIVER)
        .option("user", JDBC_USER)
        .option("password", JDBC_PASSWORD)
        .option("dbtable", JDBC_TABLE)
        .option("partitionColumn", PRIMARY_KEY)
        .option("lowerBound", JDBC_LOWER_BOUND)
        .option("upperBound", JDBC_UPPER_BOUND)
        .option("numPartitions", JDBC_NUM_PARTITIONS)
        .option("fetchsize", str(FETCH_SIZE))
        .load()
    )

    audited = (
        df
        .withColumn("ds", F.lit(AS_OF_DATE.isoformat()))
        .withColumn("_pulse_business_date", F.lit(AS_OF_DATE.isoformat()))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/{JDBC_TABLE}"))
        .withColumn("_pulse_snapshot_kind", F.lit("FULL"))
    )

    replace_where = f"ds = '{AS_OF_DATE.isoformat()}'"
    written = audited.cache()
    row_count = written.count()
    log.info("writing snapshot rows=%d format=%s replaceWhere=%s",
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
         .option("writeMethod", "indirect")
         .mode("append").save())
    elif LAKE_FORMAT == "parquet":
        (written.write.format("parquet")
         .mode("overwrite").option("partitionOverwriteMode", "dynamic")
         .partitionBy("ds").save(TABLE_PATH))
    else:
        raise ValueError(
            f"Unsupported LAKE_FORMAT for SnapshotIngestion bronze: {LAKE_FORMAT}"
        )
    written.unpersist()

    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
    )
    log.info("SnapshotIngestion complete. table=%s rows=%d", JDBC_TABLE, row_count)
    spark.stop()


if __name__ == "__main__":
    main()
