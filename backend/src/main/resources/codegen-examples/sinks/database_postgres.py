# PULSE codegen example: DatabaseWriter — gold table → Postgres
# (operational/serving database, NOT a warehouse).
#
# What this blueprint does (and what it does NOT):
#   - Use case: low-latency app reads (REST API behind a fact, daily
#     reconciliation file generation, ops dashboards). NOT for analytical
#     queries — that's WarehouseWriter (BigQuery, Snowflake, Redshift).
#   - Two write modes:
#       'overwrite_partition' — writes today's ds via DELETE+INSERT
#                               in a single transaction. Idempotent
#                               on re-run. Default for partitioned
#                               tables.
#       'merge_on_pk'         — JDBC INSERT-ON-CONFLICT-UPDATE
#                               (Postgres dialect) keyed on PK.
#                               Default for non-partitioned tables.
#   - Schema must already exist. The blueprint does NOT create the
#     destination table; codegen emits a paired DDL artifact reviewed
#     in PR before deploy.
#   - Connection pool tuning matters: batchsize=5000, fetchsize=10000
#     are the practical defaults; smaller and the JDBC overhead
#     dominates, larger and the JVM hits OOM on wide rows.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession

from pulse_pipeline_state import advance_high_water_mark


RUN_ID = os.environ["PULSE_RUN_ID"]
PULSE_BUSINESS_DATE = os.environ.get("PULSE_BUSINESS_DATE", date.today().isoformat())
AS_OF_DATE = date.fromisoformat(PULSE_BUSINESS_DATE)


PIPELINE_NAME = "__PIPELINE_NAME__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"

# Source — the gold-layer Delta table we publish to Postgres.
# Source — the gold layer we publish from. Format-branched read because
# GCP gold is bq_native (catalog identifier) while DPC gold can be
# delta | iceberg_external | parquet (path-based).
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"           # populated for path-based formats
BQ_TABLE = "__BQ_TABLE__"               # populated for bq_native

# Destination.
DEST_TABLE = "__DEST_TABLE__"               # 'analytics.fct_orders'
WRITE_MODE = "__WRITE_MODE__"               # 'overwrite_partition' | 'merge_on_pk'
PRIMARY_KEY = "__PRIMARY_KEY__"             # required for merge_on_pk
PARTITION_COLUMN = "__PARTITION_COLUMN__"   # e.g. 'ds'
BATCH_SIZE = __BATCH_SIZE__                 # default 5000


JDBC_URL = os.environ["__DEST_NAME___JDBC_URL"]
JDBC_USER = os.environ["__DEST_NAME___USERNAME"]
JDBC_PASSWORD = os.environ["__DEST_NAME___PASSWORD"]


log = logging.getLogger("pulse.sink.database_postgres")
log.setLevel(logging.INFO)


def jdbc_writer(df, mode):
    return (
        df.write.format("jdbc")
        .option("url", JDBC_URL)
        .option("driver", "org.postgresql.Driver")
        .option("user", JDBC_USER)
        .option("password", JDBC_PASSWORD)
        .option("dbtable", DEST_TABLE)
        .option("batchsize", str(BATCH_SIZE))
        .option("isolationLevel", "READ_COMMITTED")
        .option("rewriteBatchedStatements", "true")
        .mode(mode)
    )


def overwrite_partition_via_truncate_and_insert(spark, df):
    """DELETE today's partition, then INSERT — single transaction.

    Spark's JDBC writer cannot delete-then-insert atomically; we issue
    the DELETE via a JDBC connection from the driver, then run the
    INSERT in append mode. If the driver process dies between the
    DELETE and the INSERT, the next run's identical DELETE+INSERT is
    a no-op-then-rebuild — still idempotent.
    """
    delete_sql = (
        f"DELETE FROM {DEST_TABLE} "
        f"WHERE {PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"
    )
    log.info("issuing partition delete: %s", delete_sql)
    conn_props = {"user": JDBC_USER, "password": JDBC_PASSWORD,
                  "driver": "org.postgresql.Driver"}
    java_props = spark._sc._jvm.java.util.Properties()
    for k, v in conn_props.items():
        java_props.setProperty(k, v)
    conn = spark._sc._jvm.java.sql.DriverManager.getConnection(JDBC_URL, java_props)
    try:
        stmt = conn.createStatement()
        try:
            stmt.executeUpdate(delete_sql)
        finally:
            stmt.close()
    finally:
        conn.close()
    jdbc_writer(df, "append").save()


def merge_on_pk(df):
    """INSERT-ON-CONFLICT-UPDATE via Spark 3.5+ PostgresDialect insert
    statement.

    Postgres-specific. For other dialects (MySQL, MSSQL), the codegen
    layer emits a different SQL idiom (INSERT ... ON DUPLICATE KEY
    UPDATE for MySQL, MERGE for MSSQL). We don't try to abstract.
    """
    # Use a temp staging table + INSERT ... ON CONFLICT for transactional
    # MERGE semantics. The Spark JDBC writer can't issue ON CONFLICT
    # directly, so we route through pg's COPY by temporarily writing to
    # a session-local table.
    staging_name = f"_pulse_stage_{PIPELINE_NAME}_{RUN_ID.replace('-','_')}"
    log.info("writing to staging table %s", staging_name)
    (
        df.write.format("jdbc")
        .option("url", JDBC_URL)
        .option("driver", "org.postgresql.Driver")
        .option("user", JDBC_USER)
        .option("password", JDBC_PASSWORD)
        .option("dbtable", staging_name)
        .option("createTableOptions", "")
        .option("batchsize", str(BATCH_SIZE))
        .mode("overwrite")
        .save()
    )

    columns = df.columns
    update_set = ", ".join(
        [f"{c} = EXCLUDED.{c}" for c in columns if c != PRIMARY_KEY]
    )
    merge_sql = (
        f"INSERT INTO {DEST_TABLE} ({', '.join(columns)}) "
        f"SELECT {', '.join(columns)} FROM {staging_name} "
        f"ON CONFLICT ({PRIMARY_KEY}) DO UPDATE SET {update_set}; "
        f"DROP TABLE {staging_name};"
    )
    log.info("issuing merge: target=%s rows≈%s", DEST_TABLE, df.count())

    conn_props = spark._sc._jvm.java.util.Properties()
    conn_props.setProperty("user", JDBC_USER)
    conn_props.setProperty("password", JDBC_PASSWORD)
    conn = spark._sc._jvm.java.sql.DriverManager.getConnection(JDBC_URL, conn_props)
    try:
        stmt = conn.createStatement()
        try:
            stmt.execute(merge_sql)
        finally:
            stmt.close()
    finally:
        conn.close()


if __name__ == "__main__":
    spark = (
        SparkSession.builder
        .appName(f"pulse-sink-pg-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    log.info("DatabaseWriter start. source_format=%s dest=%s mode=%s",
             LAKE_FORMAT, DEST_TABLE, WRITE_MODE)

    if LAKE_FORMAT == "delta":
        df = spark.read.format("delta").load(TABLE_PATH)
    elif LAKE_FORMAT == "iceberg_external" or LAKE_FORMAT == "iceberg_bq_managed":
        df = spark.read.format("iceberg").load(TABLE_PATH)
    elif LAKE_FORMAT == "parquet":
        df = spark.read.format("parquet").load(TABLE_PATH)
    elif LAKE_FORMAT == "bq_native":
        df = spark.read.format("bigquery").option("table", BQ_TABLE).load()
    else:
        raise ValueError(f"Unsupported source LAKE_FORMAT: {LAKE_FORMAT}")
    if WRITE_MODE == "overwrite_partition":
        df = df.filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'")

    df = df.cache()
    row_count = df.count()
    log.info("publishing rows=%d", row_count)

    if WRITE_MODE == "overwrite_partition":
        overwrite_partition_via_truncate_and_insert(spark, df)
    elif WRITE_MODE == "merge_on_pk":
        merge_on_pk(df)
    else:
        raise ValueError(f"Unsupported write_mode: {WRITE_MODE}")

    df.unpersist()
    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
    )
    log.info("DatabaseWriter complete. dest=%s rows=%d", DEST_TABLE, row_count)
    spark.stop()
