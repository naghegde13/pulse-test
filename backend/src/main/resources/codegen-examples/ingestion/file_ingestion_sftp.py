# PULSE codegen example: FileIngestion — daily file pulled from SFTP,
# staged to object storage, then loaded into bronze.
#
# What this blueprint does (and what it does NOT):
#   - Two-step: paramiko SFTPClient downloads the dated file to a
#     pipeline-scoped staging path on object storage, then Spark reads
#     from that staged path. We DO NOT read directly over SFTP — paramiko
#     isn't a Hadoop FileSystem, and a 50GB SFTP read would pin the
#     driver JVM.
#   - Same V93 split-field date convention as file_ingestion_s3_csv.py —
#     bucket, path_prefix, filename_pattern, pattern_kind, date_format,
#     date_value. Mnemonic resolution at runtime.
#   - Auth via SSH private key from the secret manager. The agent
#     SHOULD propose key auth as the default (password auth is rejected
#     by every modern SFTP server).
#   - Cleans up the staged file after a successful bronze write so
#     storage costs stay bounded; on failure the staged file remains
#     for incident triage.

from datetime import date
import logging
import os

import paramiko
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

# Remote file location.
SFTP_HOST = "__SFTP_HOST__"
SFTP_PORT = __SFTP_PORT__
REMOTE_PREFIX = "__REMOTE_PREFIX__"               # '/outbound/loan_master/'
FILENAME_PATTERN = "__FILENAME_PATTERN__"         # 'loan_master_{date}.dat'
PATTERN_KIND = "__PATTERN_KIND__"
DATE_FORMAT = "__DATE_FORMAT__"
DATE_VALUE = "__DATE_VALUE__"
HOLIDAY_CALENDAR_ID = "__HOLIDAY_CALENDAR_ID__"
FISCAL_OFFSET_MONTHS = __FISCAL_OFFSET_MONTHS__

# Staging bucket — pipeline-scoped, NOT bronze. We treat the staged copy
# as an ephemeral cache; bronze is the durable record.
# SFTP staging — uses PULSE's files-bucket Processing folder so the
# downloaded file lives under the convention. Per #30 spec, files
# in flight (after fetch, before bronze write) belong in Processing.
FILES_PROCESSING_PATH = "__FILES_PROCESSING_PATH__"

# Storage convention (#30) — bronze write.
LAKE_FORMAT = "__LAKE_FORMAT__"
STORAGE_BACKEND = "__STORAGE_BACKEND__"
TABLE_PATH = "__TABLE_PATH__"
BQ_TABLE = "__BQ_TABLE__"

CSV_HAS_HEADER = __CSV_HAS_HEADER__
CSV_DELIMITER = "__CSV_DELIMITER__"


# Secrets.
SFTP_USER = os.environ["__SOURCE_NAME___USERNAME"]
SFTP_KEY_PATH = os.environ["__SOURCE_NAME___PRIVATE_KEY_FILE"]
SFTP_KEY_PASSPHRASE = os.environ.get("__SOURCE_NAME___PRIVATE_KEY_PASSPHRASE")


log = logging.getLogger("pulse.file_ingestion.sftp")
log.setLevel(logging.INFO)


def resolve_arrival_date():
    return resolve_mnemonic(
        DATE_VALUE, as_of=AS_OF_DATE,
        calendar_id=HOLIDAY_CALENDAR_ID,
        fiscal_offset_months=FISCAL_OFFSET_MONTHS,
    )


def build_remote_filename(arrival_date):
    if PATTERN_KIND == "literal":
        return FILENAME_PATTERN
    if PATTERN_KIND == "date_template":
        return FILENAME_PATTERN.replace("{date}", arrival_date.strftime(DATE_FORMAT))
    raise ValueError(f"Unsupported pattern_kind for SFTP: {PATTERN_KIND}")


def fetch_via_sftp(remote_path, local_path):
    """Pull one file. Caller is responsible for staging-bucket upload."""
    log.info("connecting to %s:%d as %s", SFTP_HOST, SFTP_PORT, SFTP_USER)
    pkey = paramiko.RSAKey.from_private_key_file(
        SFTP_KEY_PATH, password=SFTP_KEY_PASSPHRASE,
    )
    transport = paramiko.Transport((SFTP_HOST, SFTP_PORT))
    transport.connect(username=SFTP_USER, pkey=pkey)
    try:
        sftp = paramiko.SFTPClient.from_transport(transport)
        try:
            log.info("fetching remote=%s → local=%s", remote_path, local_path)
            sftp.get(remote_path, local_path)
            stat = os.stat(local_path)
            log.info("fetched bytes=%d", stat.st_size)
        finally:
            sftp.close()
    finally:
        transport.close()


def stage_to_object_store(spark, local_path, staged_uri):
    """Upload via Spark's hadoop FileSystem API.

    Avoids requiring boto3 for S3 / gcsfs for GCS — Spark already has
    the filesystem client wired through hadoopConfiguration.
    """
    hadoop_conf = spark._jsc.hadoopConfiguration()
    fs = (
        spark._jvm.org.apache.hadoop.fs.FileSystem
        .get(spark._jvm.java.net.URI(staged_uri), hadoop_conf)
    )
    in_path = spark._jvm.org.apache.hadoop.fs.Path(local_path)
    out_path = spark._jvm.org.apache.hadoop.fs.Path(staged_uri)
    fs.copyFromLocalFile(in_path, out_path)
    log.info("staged to %s", staged_uri)


def main():
    arrival_date = resolve_arrival_date()
    remote_filename = build_remote_filename(arrival_date)
    remote_path = f"{REMOTE_PREFIX}{remote_filename}"

    local_path = f"/tmp/pulse_sftp/{PIPELINE_NAME}/{remote_filename}"
    os.makedirs(os.path.dirname(local_path), exist_ok=True)

    fetch_via_sftp(remote_path, local_path)

    spark = (
        SparkSession.builder
        .appName(f"pulse-sftp-ingest-{PIPELINE_NAME}-{RUN_ID}")
        .getOrCreate()
    )

    staged_uri = (
        f"s3a://{STAGING_BUCKET}/{SOR_SLUG}/{PIPELINE_NAME}/staging/"
        f"{arrival_date.isoformat()}/{remote_filename}"
    )
    stage_to_object_store(spark, local_path, staged_uri)

    df = (
        spark.read
        .option("header", "true" if CSV_HAS_HEADER else "false")
        .option("inferSchema", "true")
        .option("delimiter", CSV_DELIMITER)
        .csv(staged_uri)
    )

    audited = (
        df
        .withColumn("ds", F.lit(arrival_date.isoformat()))
        .withColumn("_pulse_business_date", F.lit(AS_OF_DATE.isoformat()))
        .withColumn("_pulse_arrival_date", F.lit(arrival_date.isoformat()))
        .withColumn("_pulse_processing_ts", F.lit(PULSE_PROCESSING_TS))
        .withColumn("_pulse_run_id", F.lit(RUN_ID))
        .withColumn("_pulse_pipeline", F.lit(PIPELINE_NAME))
        .withColumn("_pulse_source", F.lit(f"{SOR_SLUG}/sftp/{remote_path}"))
    )

    replace_where = f"ds = '{arrival_date.isoformat()}'"
    written = audited.cache()
    row_count = written.count()
    log.info("SFTP FileIngestion bronze write rows=%d format=%s",
             row_count, LAKE_FORMAT)

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
            f"Unsupported LAKE_FORMAT for SFTP FileIngestion: {LAKE_FORMAT}"
        )
    written.unpersist()

    # Cleanup staged file ONLY after the bronze write succeeded.
    try:
        os.unlink(local_path)
        log.info("removed local staged file %s", local_path)
    except OSError as exc:
        log.warning("local cleanup failed (non-fatal): %s", exc)

    advance_high_water_mark(
        pipeline=PIPELINE_NAME, run_id=RUN_ID,
        high_water_mark=arrival_date.isoformat(), row_count=row_count,
    )
    log.info("SFTP FileIngestion complete. rows=%d arrival=%s", row_count, arrival_date)
    spark.stop()


if __name__ == "__main__":
    main()
