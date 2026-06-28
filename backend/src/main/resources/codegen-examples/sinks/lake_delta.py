# PULSE codegen example: LakeWriter — Delta Lake variant. Publishes a
# gold model to a Delta table at a different physical path (e.g., a
# downstream consumer's bucket, a separate bronze for cross-pipeline
# fan-out, or a partner-shared lake).
#
# What this blueprint does (and what it does NOT):
#   - Two write modes:
#       'merge_on_pk' — DeltaTable.merge upsert on the declared PK.
#                       Use when the destination keeps a rolling
#                       current-state view.
#       'append_partition' — write today's ds with replaceWhere; the
#                       destination keeps history. Idempotent on
#                       re-run.
#   - Different from lake_iceberg.py: same blueprint (LakeWriter),
#     different table format. The blueprint param `lake_format`
#     selects which file the codegen layer emits. Same intent,
#     different physical store.
#   - Optimize-after-write: when row volume warrants, the codegen
#     layer emits an OPTIMIZE + Z-ORDER step after the merge to keep
#     the destination's read latency bounded.
#   - Does NOT auto-create the destination table. The PR-reviewed DDL
#     artifact creates it; this job assumes existence.

from datetime import date
import logging
import os

from delta.tables import DeltaTable
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

DEST_BUCKET = "__DEST_BUCKET__"
DEST_TABLE = "__DEST_TABLE__"
WRITE_MODE = "__WRITE_MODE__"               # 'merge_on_pk' | 'append_partition'
PRIMARY_KEY = "__PRIMARY_KEY__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"   # 'ds' for medallion convention

# When True, run OPTIMIZE+Z-ORDER after the write. Adds 30s-2m to job
# runtime but keeps query latency bounded as files accumulate.
OPTIMIZE_AFTER_WRITE = __OPTIMIZE_AFTER_WRITE__
Z_ORDER_COLS = __Z_ORDER_COLS__             # list of column names


log = logging.getLogger("pulse.sink.lake_delta")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-sink-delta-{PIPELINE_NAME}-{RUN_ID}")
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog",
                "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        .getOrCreate()
    )

    dest_path = f"s3a://{DEST_BUCKET}/{DOMAIN_SLUG}/{DEST_TABLE}"
    log.info("LakeWriter(delta) start. source_format=%s dest=%s mode=%s",
             LAKE_FORMAT, dest_path, WRITE_MODE)

    if LAKE_FORMAT == "delta":
        updates = spark.read.format("delta").load(TABLE_PATH)
    elif LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
        updates = spark.read.format("iceberg").load(TABLE_PATH)
    elif LAKE_FORMAT == "parquet":
        updates = spark.read.format("parquet").load(TABLE_PATH)
    elif LAKE_FORMAT == "bq_native":
        updates = spark.read.format("bigquery").option("table", BQ_TABLE).load()
    else:
        raise ValueError(f"Unsupported source LAKE_FORMAT: {LAKE_FORMAT}")
    if WRITE_MODE == "append_partition":
        updates = updates.filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'")

    updates = updates.cache()
    row_count = updates.count()
    log.info("publishing rows=%d", row_count)

    if not DeltaTable.isDeltaTable(spark, dest_path):
        # Bootstrap the destination on first run.
        log.info("destination not initialized — writing first version")
        (updates.write.format("delta").mode("overwrite")
         .partitionBy(PARTITION_COLUMN if PARTITION_COLUMN else None)
         .save(dest_path))
    elif WRITE_MODE == "merge_on_pk":
        target = DeltaTable.forPath(spark, dest_path)
        merge_predicate = f"t.{PRIMARY_KEY} = s.{PRIMARY_KEY}"
        if PARTITION_COLUMN:
            # Add partition pruning to the merge predicate. Without it,
            # Delta scans every file in the destination on every merge.
            merge_predicate += (
                f" AND t.{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}' "
                f"AND s.{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"
            )
        log.info("merging with predicate: %s", merge_predicate)
        (target.alias("t")
         .merge(updates.alias("s"), merge_predicate)
         .whenMatchedUpdateAll()
         .whenNotMatchedInsertAll()
         .execute())
    elif WRITE_MODE == "append_partition":
        replace_where = f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'"
        log.info("append_partition with replaceWhere: %s", replace_where)
        (updates.write.format("delta")
         .mode("overwrite")
         .option("replaceWhere", replace_where)
         .partitionBy(PARTITION_COLUMN)
         .save(dest_path))
    else:
        raise ValueError(f"Unsupported write_mode: {WRITE_MODE}")

    if OPTIMIZE_AFTER_WRITE:
        if Z_ORDER_COLS:
            zo_cols = ", ".join(Z_ORDER_COLS)
            log.info("OPTIMIZE %s ZORDER BY (%s)", dest_path, zo_cols)
            spark.sql(f"OPTIMIZE delta.`{dest_path}` ZORDER BY ({zo_cols})")
        else:
            log.info("OPTIMIZE %s", dest_path)
            spark.sql(f"OPTIMIZE delta.`{dest_path}`")

    updates.unpersist()
    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
    )
    log.info("LakeWriter(delta) complete. dest=%s rows=%d", dest_path, row_count)
    spark.stop()


if __name__ == "__main__":
    main()
