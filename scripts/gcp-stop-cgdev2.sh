#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE CG DEV2 GCP Stop Script
# Stops Cloud SQL to save costs. Cloud Run auto-scales to 0 on its own.
########################################################################

PROJECT_ID="wf-pulse-agentic-dev2"
REGION="us-central1"
CONFIG_NAME="cgdev2"
DEPLOY_SA_EMAIL="pulse-handoff-sa@wf-pulse-agentic-dev2.iam.gserviceaccount.com"
DEPLOY_SA_KEY="${CGDEV2_DEPLOY_SA_KEY:-/Users/aameradam/projects/dev/PULSE-integration/.secrets/gcp/wf-pulse-agentic-dev2-5b6e39d376d5.json}"
SQL_INSTANCE="pulse-cgdev2-db"
FRONTEND_SERVICE="pulse-cgdev2-frontend"
BACKEND_SERVICE="pulse-cgdev2-backend"

export CLOUDSDK_ACTIVE_CONFIG_NAME="${CONFIG_NAME}"
gcloud config configurations create "${CONFIG_NAME}" 2>/dev/null || true
gcloud auth activate-service-account "${DEPLOY_SA_EMAIL}" \
  --key-file="${DEPLOY_SA_KEY}" --configuration="${CONFIG_NAME}" --quiet
gcloud config set project "${PROJECT_ID}" --configuration="${CONFIG_NAME}" --quiet

echo "=== PULSE CG DEV2 GCP Stop ==="

echo "--- Stopping Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS="$(gcloud sql instances describe "${SQL_INSTANCE}" \
    --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)"
  if [ "${STATUS}" = "RUNNABLE" ]; then
    gcloud sql instances patch "${SQL_INSTANCE}" \
      --activation-policy=NEVER --project="${PROJECT_ID}" --quiet
    echo "[DB] Stopped. (Data is preserved)"
  else
    echo "[DB] Already stopped (${STATUS})"
  fi
else
  echo "[DB] Instance not found, nothing to stop."
fi

echo "--- Ensuring Cloud Run services scale to zero ---"
for SVC in "${FRONTEND_SERVICE}" "${BACKEND_SERVICE}"; do
  if gcloud run services describe "${SVC}" \
    --region="${REGION}" --project="${PROJECT_ID}" &>/dev/null; then
    gcloud run services update "${SVC}" \
      --region="${REGION}" --project="${PROJECT_ID}" \
      --min-instances=0 --quiet 2>/dev/null || true
    echo "[${SVC}] Will scale to 0 when idle."
  fi
done

echo ""
echo "=== Stopped (CG DEV2) ==="
echo "Cloud SQL is stopped (no charges for compute)."
echo "Cloud Run services will auto-stop when no traffic."
echo "Run 'scripts/gcp-start-cgdev2.sh' to resume."
