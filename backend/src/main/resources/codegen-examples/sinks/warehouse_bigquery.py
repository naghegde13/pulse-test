# PULSE codegen example: WarehouseWriter — gold table → BigQuery via the
# spark-bigquery-connector.
#
# What this blueprint does (and what it does NOT):
#   - Use case: analytical queries from Looker/PowerBI/dbt-cloud, ad-hoc
#     BI exploration, cross-domain reporting where the warehouse is the
#     analytical SoR. Different from DatabaseWriter (Postgres for
#     operational reads).
#   - Two write modes:
#       'overwrite_partition' — replace today's partition. The connector
#                               rewrites only the matched partition,
#                               not the full table.
#       'merge_on_pk'         — uses MERGE statement via direct write
#                               method; requires the destination
#                               table to have clustering on the PK
#                               for reasonable runtime.
#   - Partitioning + clustering MUST be set on the destination at DDL
#     time. The codegen-emitted DDL artifact handles that; this job
#     assumes the destination is already correctly partitioned.
#   - GCP credentials come from the workload-identity-bound service
#     account (Cloud Run / GKE) OR from a key file resolved by the
#     Airflow GCP connection. Plaintext keys forbidden.

from datetime import date
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

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

GCP_PROJECT = "__GCP_PROJECT__"
BQ_DATASET = "__BQ_DATASET__"
BQ_TABLE = "__BQ_TABLE__"
WRITE_MODE = "__WRITE_MODE__"               # 'overwrite_partition' | 'merge_on_pk'
PRIMARY_KEY = "__PRIMARY_KEY__"
PARTITION_COLUMN = "__PARTITION_COLUMN__"   # MUST match BQ table's _PARTITIONTIME / column
CLUSTERING_COLUMNS = "__CLUSTERING_COLUMNS__"  # comma-separated, e.g. 'customer_id,region'

# Connector-required staging bucket — used during write for upload of
# Avro intermediate files. Keep this bucket lifecycle-bound (delete
# after 1 day) to avoid cost creep.
TEMP_GCS_BUCKET = "__TEMP_GCS_BUCKET__"

# Service account for credentials — workload-identity-bound at deploy.
GOOGLE_APPLICATION_CREDENTIALS = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")


log = logging.getLogger("pulse.sink.warehouse_bigquery")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-sink-bq-{PIPELINE_NAME}-{RUN_ID}")
        .config("temporaryGcsBucket", TEMP_GCS_BUCKET)
        .getOrCreate()
    )

    full_dest = f"{GCP_PROJECT}.{BQ_DATASET}.{BQ_TABLE}"
    log.info("WarehouseWriter(bq) start. source_format=%s dest=%s mode=%s",
             LAKE_FORMAT, full_dest, WRITE_MODE)

    if LAKE_FORMAT == "delta":
        df = spark.read.format("delta").load(TABLE_PATH)
    elif LAKE_FORMAT in ("iceberg_external", "iceberg_bq_managed"):
        df = spark.read.format("iceberg").load(TABLE_PATH)
    elif LAKE_FORMAT == "parquet":
        df = spark.read.format("parquet").load(TABLE_PATH)
    elif LAKE_FORMAT == "bq_native":
        # Reading bq_native into Spark uses the BQ-Storage-API; the dest
        # is the same project so this reduces to a project-internal copy.
        df = spark.read.format("bigquery").option("table", BQ_TABLE).load()
    else:
        raise ValueError(f"Unsupported source LAKE_FORMAT: {LAKE_FORMAT}")
    if WRITE_MODE == "overwrite_partition":
        df = df.filter(f"{PARTITION_COLUMN} = '{AS_OF_DATE.isoformat()}'")
    df = df.cache()
    row_count = df.count()
    log.info("publishing rows=%d", row_count)

    if WRITE_MODE == "overwrite_partition":
        # The 'direct' write method MERGEs the data through BQ's
        # streaming buffer; for partition-wise overwrite we use the
        # 'indirect' method which uploads Avro to GCS then BQ-loads,
        # because direct does not support overwrite-by-partition.
        (
            df.write.format("bigquery")
            .option("table", full_dest)
            .option("writeMethod", "indirect")
            .option("partitionField", PARTITION_COLUMN)
            .option("partitionType", "DAY")
            .option("clusteredFields", CLUSTERING_COLUMNS)
            # datePartition forces overwrite to a single partition.
            .option("datePartition", AS_OF_DATE.isoformat().replace("-", ""))
            .mode("overwrite")
            .save()
        )
    elif WRITE_MODE == "merge_on_pk":
        # Stage to a session-scoped table, then MERGE.
        staging = f"{GCP_PROJECT}.{BQ_DATASET}.{BQ_TABLE}_pulse_stage_{RUN_ID.replace('-','_')}"
        log.info("staging to %s", staging)
        (
            df.write.format("bigquery")
            .option("table", staging)
            .option("writeMethod", "indirect")
            .mode("overwrite")
            .save()
        )
        columns = df.columns
        update_set = ", ".join(
            f"{c} = s.{c}" for c in columns if c != PRIMARY_KEY
        )
        col_list = ", ".join(columns)
        s_col_list = ", ".join(f"s.{c}" for c in columns)
        merge_sql = f"""
            MERGE INTO `{full_dest}` t
            USING `{staging}` s
                ON t.{PRIMARY_KEY} = s.{PRIMARY_KEY}
            WHEN MATCHED THEN UPDATE SET {update_set}
            WHEN NOT MATCHED THEN INSERT ({col_list}) VALUES ({s_col_list});
            DROP TABLE `{staging}`;
        """
        log.info("issuing BQ merge")
        # Issue the MERGE via the BigQuery Python client (no Spark session
        # needed for a single statement).
        from google.cloud import bigquery
        client = bigquery.Client(project=GCP_PROJECT)
        for stmt in [s.strip() for s in merge_sql.split(";") if s.strip()]:
            client.query(stmt).result()
    else:
        raise ValueError(f"Unsupported write_mode: {WRITE_MODE}")

    df.unpersist()
    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=AS_OF_DATE.isoformat(), row_count=row_count,
    )
    log.info("WarehouseWriter(bq) complete. dest=%s rows=%d", full_dest, row_count)
    spark.stop()


if __name__ == "__main__":
    main()
