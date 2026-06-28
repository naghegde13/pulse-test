# PULSE codegen example: FileArrivalSensor — Airflow sensor that blocks
# the DAG until a daily source file lands on object storage, then hands
# off to the bronze ingest task.
#
# What this blueprint does (and what it does NOT):
#   - Builds the expected object key from the V92 split fields:
#     bucket / path_prefix / filename_pattern + pattern_kind +
#     date_format + date_value. The agent SHOULD propose split fields
#     instead of one opaque path string so the DAG can target a specific
#     dated file each run.
#   - Uses Airflow's reschedule mode so a 4-hour wait doesn't pin a
#     worker slot. With poke_interval=300s, the worker holds for ~5s
#     per probe, then releases until the next interval.
#   - Renders into ONE DAG file per pipeline (per the locked decision
#     "one DAG per Business Pipeline"). The sensor task and the bronze
#     ingest task ARE in the same DAG — they don't communicate via
#     XCom or external events.
#   - Does NOT poll the file ITSELF for content; that's DQValidator's
#     job once the file has been ingested into bronze.
#
# Convention notes:
#   - __PLACEHOLDER__ tokens are substituted at codegen time from
#     SubPipelineInstance.params.
#   - DATE_VALUE is a date mnemonic ("BOM-1", "PBD", "EOM-2", or an ISO
#     literal) — pulse_dates resolves it at runtime against the tenant's
#     holiday calendar so a Mon-after-holiday run still picks the right
#     business date.
#   - SCHEDULE_CRON, CATCHUP_ENABLED, MAX_ACTIVE_RUNS, DEPENDS_ON_PAST
#     come from a co-instantiated ScheduleAndTriggers blueprint in the
#     same pipeline (V93 audit). Agent SHOULD propose explicit values
#     for all four — never accept defaults silently.
#   - Credentials use Airflow connections (aws_default / gcp_default).
#     Plaintext secrets in DAG code are forbidden.

from datetime import datetime, timedelta

from airflow import DAG
from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor
from airflow.providers.google.cloud.sensors.gcs import GCSObjectExistenceSensor
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator


# ---------------------------------------------------------------------
# Static config — substituted at codegen time from blueprint params.
# ---------------------------------------------------------------------
PIPELINE_NAME = "__PIPELINE_NAME__"
DAG_OWNER = "__DAG_OWNER__"
TENANT_SLUG = "__TENANT_SLUG__"
DOMAIN_SLUG = "__DOMAIN_SLUG__"
SOR_SLUG = "__SOR_SLUG__"

# V92 split fields — drive a dated key build, not a single opaque path.
SOURCE_KIND = "__SOURCE_KIND__"             # 's3' | 'gcs'

# Storage convention (#30): the SRC folder lives in the files-bucket
# under {domain}/{sor}/{pipeline}/SRC/. PathConventionService produces
# the full URI; we split it into bucket + key for the Airflow sensor.
FILES_SRC_PATH = "__FILES_SRC_PATH__"       # 'gs://pulse-{tenant}-{env}-files/{domain}/{sor}/{pipeline}/SRC/'
_scheme_split = FILES_SRC_PATH.split("://", 1)
_bucket_split = _scheme_split[1].split("/", 1) if len(_scheme_split) == 2 else ["", ""]
BUCKET = _bucket_split[0]
KEY_BASE = _bucket_split[1] if len(_bucket_split) > 1 else ""

PATH_PREFIX = KEY_BASE                       # 'tenant-{domain}/{sor}/{pipeline}/SRC/'
FILENAME_PATTERN = "__FILENAME_PATTERN__"   # 'loan_master_{date}.csv'
PATTERN_KIND = "__PATTERN_KIND__"           # 'glob' | 'literal' | 'date_template'
DATE_FORMAT = "__DATE_FORMAT__"             # strftime, e.g. '%Y%m%d'
DATE_VALUE = "__DATE_VALUE__"               # mnemonic OR ISO literal (V93 accepts_mnemonic)
HOLIDAY_CALENDAR_ID = "__HOLIDAY_CALENDAR_ID__"
FISCAL_OFFSET_MONTHS = __FISCAL_OFFSET_MONTHS__

POKE_INTERVAL_S = __POKE_INTERVAL_S__
TIMEOUT_S = __TIMEOUT_S__
EXPECTED_SIZE_MIN = __EXPECTED_SIZE_MIN__   # bytes; 0 disables size guard

# V93 ScheduleAndTriggers — agent MUST propose explicit values.
SCHEDULE_CRON = "__SCHEDULE_CRON__"
CATCHUP_ENABLED = __CATCHUP_ENABLED__       # bool; default FALSE per #28
MAX_ACTIVE_RUNS = __MAX_ACTIVE_RUNS__       # int; usually 1 for per-day pipelines
DEPENDS_ON_PAST = __DEPENDS_ON_PAST__       # bool

# Generated bronze ingest job (one per pipeline). Layout follows the
# tenant-monorepo convention: /pipelines/<id>/jobs/ingestion/<name>.py
INGEST_JOB_FILENAME = "__INGEST_JOB_FILENAME__"


def build_object_key():
    """Build the templated key string the sensor probes.

    For pattern_kind='date_template' the key contains the run-date
    formatted via DATE_FORMAT. We use Airflow's `{{ ds }}`/`{{ ds_nodash }}`
    macros where possible so the resolved key matches the run date.
    A pulse_dates mnemonic (e.g., 'PBD' for previous business day) is
    resolved by a small upstream PythonOperator that pushes the literal
    date into XCom; the sensor then templates against that XCom value.

    For 'glob' / 'literal' kinds, FILENAME_PATTERN is used verbatim.
    """
    if PATTERN_KIND == "date_template":
        # When DATE_VALUE is a mnemonic, the upstream resolver task
        # writes the literal date to XCom under key 'arrival_date'.
        # When it's an ISO literal, the codegen layer substitutes the
        # literal directly and we don't need XCom — both paths reduce
        # to the same templated string here.
        return (
            f"{PATH_PREFIX}"
            + FILENAME_PATTERN.replace(
                "{date}",
                "{{ ti.xcom_pull(task_ids='resolve_arrival_date', key='arrival_date') }}",
            )
        )
    return f"{PATH_PREFIX}{FILENAME_PATTERN}"


def resolve_arrival_date_fn(**context):
    """Translate DATE_VALUE (mnemonic or ISO) into a literal YYYY-MM-DD
    string using the tenant's holiday calendar.

    Pushed to XCom so the sensor's templated bucket_key picks it up.
    """
    from datetime import date as _date

    from pulse_dates import resolve_mnemonic

    as_of = _date.fromisoformat(context["ds"])
    resolved = resolve_mnemonic(
        DATE_VALUE,
        as_of=as_of,
        calendar_id=HOLIDAY_CALENDAR_ID,
        fiscal_offset_months=FISCAL_OFFSET_MONTHS,
    )
    formatted = resolved.strftime(DATE_FORMAT)
    context["ti"].log.info(
        "resolved arrival_date: input=%r as_of=%s → %s (formatted=%s)",
        DATE_VALUE, as_of, resolved.isoformat(), formatted,
    )
    return formatted


default_args = {
    "owner": DAG_OWNER,
    "retries": 2,
    "retry_delay": timedelta(minutes=2),
    "email_on_failure": False,
    "depends_on_past": DEPENDS_ON_PAST,
    "start_date": datetime(2026, 1, 1),
}


with DAG(
    dag_id=f"{PIPELINE_NAME}_dag",
    description=f"PULSE pipeline {PIPELINE_NAME}: file arrival → bronze ingest.",
    default_args=default_args,
    schedule=SCHEDULE_CRON,
    catchup=CATCHUP_ENABLED,
    max_active_runs=MAX_ACTIVE_RUNS,
    tags=["pulse", "ingest", "bronze", SOR_SLUG],
) as dag:

    # ------------------------------------------------------------------
    # 1. Resolve the mnemonic-aware arrival date once per run.
    #    Skipped (no-op operator) if DATE_VALUE is already an ISO
    #    literal — but we always emit it so the DAG shape is stable
    #    across re-codegens regardless of the user's choice.
    # ------------------------------------------------------------------
    from airflow.operators.python import PythonOperator

    resolve_arrival_date = PythonOperator(
        task_id="resolve_arrival_date",
        python_callable=resolve_arrival_date_fn,
    )

    # ------------------------------------------------------------------
    # 2. Sensor — wait for the dated file to land. Reschedule mode
    #    releases the worker slot between pokes so a 4-hour wait
    #    doesn't pin a slot.
    # ------------------------------------------------------------------
    if SOURCE_KIND == "s3":
        wait_for_file = S3KeySensor(
            task_id="wait_for_source_file",
            bucket_name=BUCKET,
            bucket_key=build_object_key(),
            # check_fn enforces a minimum file size so a partial upload
            # doesn't trigger downstream. The sensor returns False (keep
            # waiting) until the file is at least EXPECTED_SIZE_MIN bytes.
            check_fn=(
                (lambda files: any(f["Size"] >= EXPECTED_SIZE_MIN for f in files)
                               if files else False)
                if EXPECTED_SIZE_MIN > 0 else None
            ),
            poke_interval=POKE_INTERVAL_S,
            timeout=TIMEOUT_S,
            mode="reschedule",
            soft_fail=False,
            aws_conn_id="aws_default",
        )
    elif SOURCE_KIND == "gcs":
        wait_for_file = GCSObjectExistenceSensor(
            task_id="wait_for_source_file",
            bucket=BUCKET,
            object=build_object_key(),
            poke_interval=POKE_INTERVAL_S,
            timeout=TIMEOUT_S,
            mode="reschedule",
            soft_fail=False,
            google_cloud_conn_id="gcp_default",
        )
    else:
        raise ValueError(f"Unsupported source_kind: {SOURCE_KIND}")

    # ------------------------------------------------------------------
    # 3. Bronze ingest — runs the codegen-emitted PySpark job. PULSE_*
    #    env vars carry per-run state. SparkSubmitOperator points at
    #    the tenant-repo path resolved from the Airflow Variable
    #    PULSE_REPO_ROOT (set at deploy time).
    # ------------------------------------------------------------------
    ingest_to_bronze = SparkSubmitOperator(
        task_id="ingest_to_bronze",
        application=(
            f"{{{{ var.value.PULSE_REPO_ROOT }}}}/{TENANT_SLUG}/pipelines/"
            f"{PIPELINE_NAME}/jobs/ingestion/{INGEST_JOB_FILENAME}"
        ),
        conn_id="spark_default",
        env_vars={
            "PULSE_PIPELINE_NAME": PIPELINE_NAME,
            "PULSE_RUN_ID": "{{ run_id }}",
            "PULSE_BUSINESS_DATE": "{{ ds }}",
            "PULSE_PROCESSING_TS": "{{ ts }}",
            "PULSE_TENANT_SLUG": TENANT_SLUG,
            "PULSE_DOMAIN_SLUG": DOMAIN_SLUG,
            "PULSE_SOR_SLUG": SOR_SLUG,
            "PULSE_BRONZE_BUCKET": BUCKET,
            # The resolved arrival date — bronze ingest reads it to
            # know which dated key to load.
            "PULSE_ARRIVAL_DATE": (
                "{{ ti.xcom_pull(task_ids='resolve_arrival_date', "
                "key='arrival_date') }}"
            ),
        },
        verbose=False,
    )

    resolve_arrival_date >> wait_for_file >> ingest_to_bronze
