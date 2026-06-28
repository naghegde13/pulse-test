#!/usr/bin/env bash
# DAG-only teardown for the hello-world proof.
#
# Removes:
#   - The DAG file from the Composer DAG bucket.
#   - The PySpark stub from the tenant dev files bucket.
#
# Does NOT touch:
#   - The Composer env itself (SU-FINAL-7 owns the full teardown).
#   - The tenant dev files bucket (wizard cat 8 owns its lifecycle).
#   - Service-account roles or any other IAM.
#
# Idempotent: missing objects are not fatal.

set -euo pipefail

PROJECT="pulse-proof-04261847"
REGION="us-central1"
COMPOSER_ENV="pulse-proof-composer"
TENANT_BUCKET="gs://pulse-home-lending-dev-files"

log() { printf '[teardown.sh %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*" >&2; }

DAG_GCS_PREFIX="$(gcloud composer environments describe "${COMPOSER_ENV}" \
    --location="${REGION}" \
    --project="${PROJECT}" \
    --format='value(config.dagGcsPrefix)' 2>/dev/null || true)"

if [ -n "${DAG_GCS_PREFIX}" ]; then
    DAG_BUCKET="${DAG_GCS_PREFIX%/dags}"
    log "Removing ${DAG_BUCKET}/dags/main.py"
    gsutil rm -f "${DAG_BUCKET}/dags/main.py" 2>/dev/null || \
        log "main.py was already absent from DAG bucket"
else
    log "Composer env ${COMPOSER_ENV} not found; skipping DAG file removal"
fi

log "Removing ${TENANT_BUCKET}/pulse-pyspark/main_dataproc.py"
gsutil rm -f "${TENANT_BUCKET}/pulse-pyspark/main_dataproc.py" 2>/dev/null || \
    log "main_dataproc.py was already absent from tenant bucket"

log "DAG-only teardown complete. Composer env, tenant bucket, and SA roles untouched."
