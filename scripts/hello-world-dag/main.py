"""Hand-authored hello-world DAG for the pulse-home-lending tenant onboarding proof.

Shape matches PULSE codegen conventions per docs/AIRFLOW_INTEGRATION_DECISIONS.md
(dag_id = ``{verb}_{noun}_{tenant_slug}``, tag-stamped with tenant slug, single
TaskGroup-free task because there is no sub-pipeline composition here — this is
the smallest plausible artifact that exercises Composer + Dataproc Serverless +
GCS + the tenant service account).

PULSE codegen is intentionally NOT invoked here. Per the PKT-FINAL-8 plan
(§SU-FINAL-4), codegen is out of scope and too unstable to test against in
this rehearsal; this file is hand-authored to *match* what codegen would emit
once it is stabilised.

Manual-trigger only (``schedule_interval=None``) so the proof never racks up
spurious paid runs.
"""

from __future__ import annotations

from airflow import DAG
from airflow.providers.google.cloud.operators.dataproc import (
    DataprocCreateBatchOperator,
)
from airflow.utils.dates import days_ago

PROJECT_ID = "pulse-proof-04261847"
REGION = "us-central1"
TENANT_BUCKET = "pulse-home-lending-dev-files"
PYSPARK_URI = f"gs://{TENANT_BUCKET}/pulse-pyspark/main_dataproc.py"

DAG_ID = "pulse_hello_world_home_lending"

default_args = {
    "owner": "pulse-home-lending",
    "retries": 0,
    "start_date": days_ago(1),
}

with DAG(
    dag_id=DAG_ID,
    default_args=default_args,
    schedule_interval=None,
    catchup=False,
    max_active_runs=1,
    tags=["pulse", "hello-world", "home-lending"],
    description=(
        "Hello-world proof DAG for the pulse-home-lending tenant. Runs a "
        "single Dataproc Serverless PySpark batch that writes a one-row "
        "Parquet file into the tenant's dev files bucket."
    ),
) as dag:

    run_hello_world_dataproc = DataprocCreateBatchOperator(
        task_id="run_hello_world_dataproc",
        project_id=PROJECT_ID,
        region=REGION,
        # batch_id must be unique per run; templating on ts_nodash keeps
        # repeated manual triggers from colliding.
        batch_id="pulse-hello-world-{{ ts_nodash | lower }}",
        batch={
            "pyspark_batch": {
                "main_python_file_uri": PYSPARK_URI,
                # The PySpark job uses argv[1] as the run_id so the output
                # Parquet path is also unique per Airflow run.
                "args": ["{{ run_id }}"],
            },
            "runtime_config": {
                "version": "2.1",
            },
            "environment_config": {
                "execution_config": {
                    "service_account": (
                        "pulse-home-lending@"
                        "pulse-proof-04261847.iam.gserviceaccount.com"
                    ),
                },
            },
        },
    )
