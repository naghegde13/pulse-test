#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE CG DEV2 GCP Start Script
# Starts Cloud SQL. Cloud Run auto-starts on incoming requests.
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

echo "=== PULSE CG DEV2 GCP Start ==="

echo "--- Starting Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS="$(gcloud sql instances describe "${SQL_INSTANCE}" \
    --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)"
  if [ "${STATUS}" = "STOPPED" ] || [ "${STATUS}" = "SUSPENDED" ]; then
    gcloud sql instances patch "${SQL_INSTANCE}" \
      --activation-policy=ALWAYS --project="${PROJECT_ID}" --quiet
    echo "[DB] Starting... (may take 1-2 minutes)"
    while true; do
      STATUS="$(gcloud sql instances describe "${SQL_INSTANCE}" \
        --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)"
      if [ "${STATUS}" = "RUNNABLE" ]; then
        break
      fi
      sleep 5
      echo -n "."
    done
    echo ""
    echo "[DB] Running."
  else
    echo "[DB] Already running (${STATUS})"
  fi
else
  echo "[DB] Instance not found. Run 'scripts/gcp-deploy-cgdev2.sh db' first."
  exit 1
fi

echo ""
echo "=== Started (CG DEV2) ==="
FRONTEND_URL="$(gcloud run services describe "${FRONTEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")"
BACKEND_URL="$(gcloud run services describe "${BACKEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")"
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
echo "Cloud Run services will auto-start on first request."
