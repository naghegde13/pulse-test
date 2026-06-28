# PULSE codegen example: LakeWriter — Apache Iceberg variant. Publishes
# a gold model to an Iceberg table via the Spark catalog plugin.
#
# What this blueprint does (and what it does NOT):
#   - Same blueprint (LakeWriter), different table format. The
#     blueprint param `lake_format` selects which file the codegen
#     layer emits.
#   - Iceberg vs Delta: choose Iceberg when downstream consumers run on
#     non-Spark engines (Trino, Flink, Athena) that prefer Iceberg's
#     catalog model. Choose Delta when consumers are Spark/Databricks-
#     dominant.
#   - MERGE INTO is Iceberg's idiomatic upsert — it integrates with the
#     catalog so the merge is durable across schema evolution.
#   - Idempotency: WHEN MATCHED uses the PK predicate; the write is
#     re-runnable on the same business date without duplicating rows.
#   - Does NOT auto-create the destination table. The PR-reviewed DDL
#     artifact creates it; this job assumes existence.

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

# Source gold-layer placeholders (format-branched read).
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"

# Iceberg destination — fully-qualified catalog.namespace.table.
ICEBERG_CATALOG = "__ICEBERG_CATALOG__"     # 'analytics' (configured at session level)
ICEBERG_NAMESPACE = "__ICEBERG_NAMESPACE__" # 'gold'
ICEBERG_TABLE = "__ICEBERG_TABLE__"
WRITE_MODE = "__WRITE_MODE__"               # 'merge_on_pk' | 'overwrite_partition'
PRIMARY_KEY = "__PRIMARY_KEY__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"

# Iceberg-specific catalog config. Resolved at session bootstrap from
# Airflow Variables OR baked into the Spark conf at deploy time.
CATALOG_TYPE = "__CATALOG_TYPE__"           # 'glue' | 'rest' | 'hadoop'
CATALOG_WAREHOUSE = "__CATALOG_WAREHOUSE__" # 's3://warehouse-bucket'


log = logging.getLogger("pulse.sink.lake_iceberg")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-sink-iceberg-{PIPELINE_NAME}-{RUN_ID}")
        .config("spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .config(f"spark.sql.catalog.{ICEBERG_CATALOG}",
                "org.apache.iceberg.spark.SparkCatalog")
        .config(f"spark.sql.catalog.{ICEBERG_CATALOG}.type", CATALOG_TYPE)
        .config(f"spark.sql.catalog.{ICEBERG_CATALOG}.warehouse",
                CATALOG_WAREHOUSE)
        .getOrCreate()
    )

    qualified = f"{ICEBERG_CATALOG}.{ICEBERG_NAMESPACE}.{ICEBERG_TABLE}"
    log.info("LakeWriter(iceberg) start. source_format=%s dest=%s mode=%s",
             LAKE_FORMAT, qualified, WRITE_MODE)

    if LAKE_FORMAT == "delta":
        df = spark.read.format("delta").load(TABLE_PATH)
    elif LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
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

    df.createOrReplaceTempView("pulse_updates")

    if WRITE_MODE == "merge_on_pk":
        merge_sql = f"""
            MERGE INTO {qualified} AS t
            USING pulse_updates AS s
                ON t.{PRIMARY_KEY} = s.{PRIMARY_KEY}
                {f"AND t.{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'" if PARTITION_COLUMN else ""}
            WHEN MATCHED THEN UPDATE SET *
            WHEN NOT MATCHED THEN INSERT *
        """
        log.info("issuing iceberg merge")
        spark.sql(merge_sql)
    elif WRITE_MODE == "overwrite_partition":
        # Iceberg's INSERT OVERWRITE replaces only the matched partition.
        spark.sql(
            f"INSERT OVERWRITE {qualified} "
            f"SELECT * FROM pulse_updates "
            f"WHERE {PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"
        )
    else:
        raise ValueError(f"Unsupported write_mode: {WRITE_MODE}")

    df.unpersist()
    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
    )
    log.info("LakeWriter(iceberg) complete. dest=%s rows=%d", qualified, row_count)
    spark.stop()


if __name__ == "__main__":
    main()
