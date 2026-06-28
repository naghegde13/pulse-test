#!/usr/bin/env bash
# Deploy + trigger the hello-world DAG against the live pulse-proof Composer env.
#
# Lifecycle:
#   1. Verify Composer env state; provision if absent; wait if DELETING.
#   2. Resolve the DAG GCS bucket from the env.
#   3. Upload main.py to that bucket's dags/ prefix.
#   4. Fail loudly if the tenant dev files bucket is missing (cat 8 of the
#      onboarding wizard is supposed to create it — do NOT silently mb here).
#   5. Upload the PySpark stub.
#   6. Wait for the DAG to be discoverable in Airflow.
#   7. Trigger the DAG, capture RUN_ID.
#   8. Poll DAG run state; exit 0 on success, 1 on failure, 2 on timeout.
#
# Exit codes:
#   0  DAG run succeeded.
#   1  DAG run failed, or a precondition (e.g. tenant bucket) was not met.
#   2  Polling timed out before the DAG run reached a terminal state.
#
# This script is intended to be invoked by the SU-FINAL-5 lane after the
# SU-FINAL-4 branch has merged. Do NOT run it from SU-FINAL-4.

set -euo pipefail

PROJECT="pulse-proof-04261847"
REGION="us-central1"
COMPOSER_ENV="pulse-proof-composer"
SA_EMAIL="pulse-home-lending@pulse-proof-04261847.iam.gserviceaccount.com"
DAG_ID="pulse_hello_world_home_lending"
TENANT_BUCKET="gs://pulse-home-lending-dev-files"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DAG_FILE="${SCRIPT_DIR}/main.py"
PYSPARK_FILE="${SCRIPT_DIR}/main_dataproc.py"

log() { printf '[deploy.sh %s] %s\n' "$(date -u +%H:%M:%SZ)" "$*" >&2; }
fatal() { log "FATAL: $*"; exit 1; }

# ---------------------------------------------------------------------------
# Step 1: Composer state check / provision
# ---------------------------------------------------------------------------
log "Step 1: checking Composer env ${COMPOSER_ENV} state"

describe_state() {
    gcloud composer environments describe "${COMPOSER_ENV}" \
        --location="${REGION}" \
        --project="${PROJECT}" \
        --format='value(state)' 2>/dev/null || echo "absent"
}

STATE="$(describe_state)"
log "Initial Composer state: ${STATE}"

# If env is in DELETING, wait up to 10 minutes for it to disappear.
if [ "${STATE}" = "DELETING" ]; then
    log "Composer env is DELETING; waiting up to 10 min for it to disappear"
    DELETING_DEADLINE=$(( $(date +%s) + 600 ))
    while [ "${STATE}" = "DELETING" ]; do
        if [ "$(date +%s)" -ge "${DELETING_DEADLINE}" ]; then
            fatal "Composer env still DELETING after 10 min"
        fi
        sleep 30
        STATE="$(describe_state)"
        log "still deleting: state=${STATE}"
    done
fi

# Provision if absent.
if [ "${STATE}" = "absent" ]; then
    log "Composer env absent; provisioning ${COMPOSER_ENV}"
    gcloud composer environments create "${COMPOSER_ENV}" \
        --location="${REGION}" \
        --project="${PROJECT}" \
        --service-account="${SA_EMAIL}" \
        --image-version=composer-2-airflow-2 \
        --environment-size=small \
        --node-count=3 &
    CREATE_PID=$!

    CREATE_DEADLINE=$(( $(date +%s) + 1500 ))  # 25 min
    while true; do
        if [ "$(date +%s)" -ge "${CREATE_DEADLINE}" ]; then
            STATE="$(describe_state)"
            fatal "Composer create timed out after 25 min; last state=${STATE}"
        fi
        sleep 60
        STATE="$(describe_state)"
        log "still creating: state=${STATE}"
        if [ "${STATE}" = "RUNNING" ]; then
            log "Composer reached RUNNING"
            break
        fi
        if [ "${STATE}" = "ERROR" ]; then
            fatal "Composer create entered ERROR state"
        fi
    done

    # Reap the background create if it's still around (it should have exited).
    wait "${CREATE_PID}" 2>/dev/null || true
elif [ "${STATE}" = "RUNNING" ]; then
    log "Composer env is already RUNNING; skipping provision"
else
    fatal "Composer env in unexpected state: ${STATE}"
fi

# ---------------------------------------------------------------------------
# Step 2: Resolve DAG bucket
# ---------------------------------------------------------------------------
log "Step 2: resolving DAG GCS bucket"
DAG_GCS_PREFIX="$(gcloud composer environments describe "${COMPOSER_ENV}" \
    --location="${REGION}" \
    --project="${PROJECT}" \
    --format='value(config.dagGcsPrefix)')"
if [ -z "${DAG_GCS_PREFIX}" ]; then
    fatal "Could not resolve dagGcsPrefix from Composer env"
fi
# dagGcsPrefix is typically gs://<bucket>/dags; strip the trailing /dags.
DAG_BUCKET="${DAG_GCS_PREFIX%/dags}"
log "DAG bucket: ${DAG_BUCKET}"

# ---------------------------------------------------------------------------
# Step 3: Upload DAG
# ---------------------------------------------------------------------------
log "Step 3: uploading DAG main.py to ${DAG_BUCKET}/dags/"
gsutil cp "${DAG_FILE}" "${DAG_BUCKET}/dags/main.py"

# ---------------------------------------------------------------------------
# Step 4: Tenant dev files bucket precondition
# ---------------------------------------------------------------------------
log "Step 4: checking tenant dev files bucket ${TENANT_BUCKET}"
gsutil ls "${TENANT_BUCKET}" >/dev/null 2>&1 || {
    fatal "bucket missing, wizard cat 8 did not provision it"
}

# ---------------------------------------------------------------------------
# Step 5: Upload PySpark stub
# ---------------------------------------------------------------------------
log "Step 5: uploading PySpark stub to ${TENANT_BUCKET}/pulse-pyspark/main_dataproc.py"
gsutil cp "${PYSPARK_FILE}" "${TENANT_BUCKET}/pulse-pyspark/main_dataproc.py"

# ---------------------------------------------------------------------------
# Step 6: Wait for DAG to be discoverable
# ---------------------------------------------------------------------------
log "Step 6: waiting for DAG ${DAG_ID} to be discoverable (up to 5 min)"
DISCOVER_DEADLINE=$(( $(date +%s) + 300 ))
while true; do
    if gcloud composer environments run "${COMPOSER_ENV}" \
        --location="${REGION}" \
        --project="${PROJECT}" \
        dags list 2>&1 | grep -q "${DAG_ID}"; then
        log "DAG ${DAG_ID} is discoverable"
        break
    fi
    if [ "$(date +%s)" -ge "${DISCOVER_DEADLINE}" ]; then
        fatal "DAG ${DAG_ID} did not appear in dags list within 5 min"
    fi
    log "DAG not yet discoverable; sleeping 30s"
    sleep 30
done

# ---------------------------------------------------------------------------
# Step 7: Trigger DAG
# ---------------------------------------------------------------------------
log "Step 7: triggering DAG ${DAG_ID}"
TRIGGER_OUTPUT="$(gcloud composer environments run "${COMPOSER_ENV}" \
    --location="${REGION}" \
    --project="${PROJECT}" \
    dags trigger -- "${DAG_ID}" 2>&1 || true)"
log "trigger output: ${TRIGGER_OUTPUT}"

RUN_ID="$(printf '%s\n' "${TRIGGER_OUTPUT}" | grep -oE 'manual__[0-9TZ:.+-]+' | head -1 || true)"
if [ -z "${RUN_ID}" ]; then
    fatal "could not extract RUN_ID from trigger output"
fi
echo "RUN_ID=${RUN_ID}"

# ---------------------------------------------------------------------------
# Step 8: Poll DAG run state
# ---------------------------------------------------------------------------
log "Step 8: polling DAG run state for up to 15 min"
POLL_DEADLINE=$(( $(date +%s) + 900 ))
LAST_STATE="unknown"
while true; do
    if [ "$(date +%s)" -ge "${POLL_DEADLINE}" ]; then
        log "Timed out polling; last state=${LAST_STATE}"
        exit 2
    fi

    STATE_OUTPUT="$(gcloud composer environments run "${COMPOSER_ENV}" \
        --location="${REGION}" \
        --project="${PROJECT}" \
        dags state -- "${DAG_ID}" "${RUN_ID}" 2>&1 || true)"
    LAST_STATE="$(printf '%s\n' "${STATE_OUTPUT}" | tail -n 5 | grep -oE '(success|failed|running|queued|up_for_retry)' | tail -1 || echo unknown)"
    log "current DAG run state: ${LAST_STATE}"

    case "${LAST_STATE}" in
        success)
            log "DAG run ${RUN_ID} succeeded"
            exit 0
            ;;
        failed)
            log "DAG run ${RUN_ID} failed"
            log "state output tail: ${STATE_OUTPUT}"
            exit 1
            ;;
        *)
            sleep 30
            ;;
    esac
done
