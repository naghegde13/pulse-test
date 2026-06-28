# hello-world-dag — pulse-home-lending tenant onboarding live proof

This directory contains a hand-authored Airflow DAG and supporting scripts used
by SU-FINAL-5 to prove that the `pulse-home-lending` tenant's GCP plumbing
(Composer + Dataproc Serverless + GCS + the tenant service account) actually
moves real bytes end-to-end. The DAG is intentionally trivial; the value is in
exercising the *integration*, not the workload.

## What this DAG proves

When `deploy.sh` exits 0, the following has all worked against live GCP:

1. The `pulse-home-lending` service account
   (`pulse-home-lending@pulse-proof-04261847.iam.gserviceaccount.com`) can
   submit a Dataproc Serverless batch.
2. Composer (`pulse-proof-composer` in `us-central1`) can read the DAG file
   from its DAG GCS bucket and discover it.
3. The DAG task (`run_hello_world_dataproc`) successfully calls
   `DataprocCreateBatchOperator` against project `pulse-proof-04261847`.
4. The PySpark stub at
   `gs://pulse-home-lending-dev-files/pulse-pyspark/main_dataproc.py` runs and
   writes a one-row Parquet file to
   `gs://pulse-home-lending-dev-files/proof/hello-world-<run_id>.parquet`.

## Files

| File | Purpose |
| ---- | ------- |
| `main.py` | The Airflow DAG. `dag_id=pulse_hello_world_home_lending`. Manual trigger only. |
| `main_dataproc.py` | PySpark stub the DAG submits to Dataproc Serverless. |
| `deploy.sh` | Provision Composer if needed, upload the DAG + PySpark, trigger and poll. |
| `teardown.sh` | DAG-only cleanup. Does not touch Composer env or tenant buckets. |
| `README.md` | This file. |

## Prerequisites

Before invoking `deploy.sh`:

- `gcloud` and `gsutil` are installed and authenticated as a principal with
  permission to manage Composer in project `pulse-proof-04261847`.
- The PULSE wizard's **category 8 (storage)** has run successfully against the
  `pulse-home-lending` tenant. That step provisions
  `gs://pulse-home-lending-dev-files`, which this DAG depends on. If the
  bucket is missing, `deploy.sh` aborts with `FATAL: bucket missing, wizard
  cat 8 did not provision it`. The fix is to re-drive SU-FINAL-3's wizard
  spec, not to create the bucket by hand.
- The `pulse-home-lending` service account exists and has Owner on the
  project (this is the SU-FINAL-1/2 precondition).

## Running

From the repo root:

```bash
./scripts/hello-world-dag/deploy.sh
```

The script logs every step to stderr with a UTC timestamp. Expect the first
run from a cold start (Composer absent) to take ~20-25 minutes; subsequent
runs against a RUNNING env are ~3-5 minutes including the DAG run itself.

## Exit codes

| Code | Meaning | Operator action |
| ---- | ------- | --------------- |
| 0 | DAG run succeeded. | Capture `RUN_ID` from stdout for the evidence packet. |
| 1 | DAG run failed, or a precondition (missing bucket, Composer ERROR) blocked progress. | Read the script's stderr log; the failing step is named. |
| 2 | Polled for 15 minutes without reaching success/failed. | Check Composer's Airflow UI directly; the run may still be queued because Dataproc Serverless is provisioning. Re-run is safe. |

## Re-running after a failed run

`deploy.sh` is safe to re-run:

- The `batch_id` for the Dataproc batch is templated on `{{ ts_nodash }}`, so
  re-triggering does not collide with the previous batch.
- The PySpark stub uses Airflow's `run_id` (passed as `argv[1]`) for the
  output Parquet path, so each run writes to its own file under
  `gs://pulse-home-lending-dev-files/proof/`.
- The DAG and PySpark files are re-uploaded each invocation (`gsutil cp`
  overwrites by default).

If the previous run left a stuck Dataproc batch, delete it manually with
`gcloud dataproc batches delete` and re-run.

## Expected cost

Rough order of magnitude when running this proof against a cold start:

- Composer (env-size `small`, 3 nodes) charges ~`$0.50/hour` of wall time.
  Provisioning + the proof + leaving it running for SU-FINAL-7 to tear down
  is typically <2 hours, so <`$1`.
- Dataproc Serverless: a single 1-DCU batch that runs for ~1-2 minutes is
  fractions of a cent.
- GCS storage: negligible (a single Parquet row).

Total expected spend per run: well under `$2`. If the deploy script is left
to time-out (exit 2) and Composer is left RUNNING, the env will accrue
~`$12/day` until SU-FINAL-7 tears it down.

## Teardown

After SU-FINAL-5 has captured evidence:

```bash
./scripts/hello-world-dag/teardown.sh
```

This deletes only the DAG and the PySpark file. Composer, the tenant bucket,
and IAM are left for SU-FINAL-7's full teardown lane.
