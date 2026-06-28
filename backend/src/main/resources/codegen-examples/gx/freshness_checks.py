# PULSE codegen example: FreshnessChecks — assert a dataset's most recent
# partition is no older than max_age_business_days from the run date.
#
# What this blueprint does (and what it does NOT):
#   - Probes a silver/gold table's MAX(partition_column) and compares it
#     to AS_OF_DATE using the holiday-aware business-day clock (NOT
#     calendar days). A 3-business-day SLA tolerates weekends + Memorial
#     Day correctly without per-region branching.
#   - Different from DQValidator: freshness is about WHEN data arrived,
#     not WHAT shape it has. They run independently.
#   - Different from FileArrivalSensor: that's an Airflow sensor that
#     blocks the DAG until a file shows up. FreshnessChecks runs late in
#     the DAG and decides whether to publish/page based on age.
#
# Convention notes:
#   - Calendar arithmetic is via pulse_dates.business_days_between, NOT
#     a homegrown loop. The platform's calendar tables are the source of
#     truth for holidays.
#   - On freshness failure: severity controls behavior — PAGE (raises),
#     WARN (logs + structured event), SUPPRESS (logs only). Default WARN.

from datetime import date
import json
import logging
import os

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from pulse_dates import business_days_between, resolve_mnemonic


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
PARTITION_COLUMN = "__PARTITION_COLUMN__"     # usually 'ds'

MAX_AGE_BUSINESS_DAYS = __MAX_AGE_BUSINESS_DAYS__   # int
HOLIDAY_CALENDAR_ID = "__HOLIDAY_CALENDAR_ID__"     # 'US-FED' | 'US-NYSE'
FISCAL_OFFSET_MONTHS = __FISCAL_OFFSET_MONTHS__

# Severity: "PAGE" | "WARN" | "SUPPRESS"
SEVERITY = "__SEVERITY__"


log = logging.getLogger("pulse.freshness_checks")
log.setLevel(logging.INFO)


def main():
    spark = (
        SparkSession.builder
        .appName(f"pulse-freshness-{PIPELINE_NAME}-{RUN_ID}")
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
    log.info("FreshnessChecks start. path=%s as_of=%s sla=%d business days calendar=%s",
             LAKE_FORMAT, AS_OF_DATE, MAX_AGE_BUSINESS_DAYS, HOLIDAY_CALENDAR_ID)

    max_partition_row = (
        _read_target()
        .agg(F.max(PARTITION_COLUMN).alias("max_partition"))
        .first()
    )
    max_partition_str = max_partition_row["max_partition"] if max_partition_row else None
    if max_partition_str is None:
        # Empty table is a freshness failure regardless of SLA.
        _emit_failure(
            reason="empty_table",
            details={"max_partition": None,
                     "as_of": AS_OF_DATE.isoformat()},
        )
        spark.stop()
        return

    last_loaded = date.fromisoformat(str(max_partition_str))
    age_business_days = business_days_between(
        last_loaded, AS_OF_DATE, calendar_id=HOLIDAY_CALENDAR_ID,
    )

    summary = {
        "pipeline": PIPELINE_NAME,
        "run_id": RUN_ID,
        "table": TARGET_TABLE,
        "as_of": AS_OF_DATE.isoformat(),
        "last_loaded": last_loaded.isoformat(),
        "age_business_days": age_business_days,
        "sla_business_days": MAX_AGE_BUSINESS_DAYS,
        "calendar_id": HOLIDAY_CALENDAR_ID,
    }
    log.info("freshness probe: %s", json.dumps(summary, default=str))

    if age_business_days > MAX_AGE_BUSINESS_DAYS:
        _emit_failure(reason="stale", details=summary)
    else:
        log.info("freshness OK — table %s is %d business day(s) old (SLA %d).",
                 TARGET_TABLE, age_business_days, MAX_AGE_BUSINESS_DAYS)

    spark.stop()


def _emit_failure(reason: str, details: dict):
    payload = {"event": "pulse.freshness_failure", "reason": reason, **details}
    if SEVERITY == "PAGE":
        log.error("FRESHNESS FAILURE — PAGE: %s", json.dumps(payload, default=str))
        raise RuntimeError(f"Freshness SLA breached: {payload}")
    if SEVERITY == "WARN":
        log.warning("FRESHNESS FAILURE — WARN: %s", json.dumps(payload, default=str))
        return
    # SUPPRESS — only structured-log so observability still records the breach
    # without firing pages or breaking the DAG.
    log.info("FRESHNESS FAILURE — SUPPRESSED: %s", json.dumps(payload, default=str))


if __name__ == "__main__":
    main()
