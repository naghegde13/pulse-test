#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE3 GCP Start Script
# Starts Cloud SQL. Cloud Run auto-starts on incoming requests.
# Hardcoded to project: pulse3-497602
########################################################################

PROJECT_ID="pulse3-497602"
REGION="us-central1"
SA_KEY="/Users/aameradam/projects/dev/pulse3/pulse_sa_credentials.json"
SA_EMAIL="pulse-sa@pulse3-497602.iam.gserviceaccount.com"
SQL_INSTANCE="pulse3-db"

# -- gcloud isolation: process-local config, no global mutation --
export CLOUDSDK_ACTIVE_CONFIG_NAME=pulse3
gcloud config configurations create pulse3 2>/dev/null || true
gcloud auth activate-service-account "${SA_EMAIL}" \
  --key-file="${SA_KEY}" --configuration=pulse3 --quiet
gcloud config set project "${PROJECT_ID}" --configuration=pulse3 --quiet

echo "=== PULSE3 GCP Start ==="

echo "--- Starting Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" \
    --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)
  if [ "${STATUS}" = "STOPPED" ] || [ "${STATUS}" = "SUSPENDED" ]; then
    gcloud sql instances patch "${SQL_INSTANCE}" \
      --activation-policy=ALWAYS --project="${PROJECT_ID}" --quiet
    echo "[DB] Starting... (may take 1-2 minutes)"
    while true; do
      STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" \
        --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)
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
  echo "[DB] Instance not found. Run 'scripts/gcp-deploy-pulse3.sh' first."
  exit 1
fi

echo ""
echo "=== Started (PULSE3) ==="
FRONTEND_URL=$(gcloud run services describe pulse3-frontend \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")
BACKEND_URL=$(gcloud run services describe pulse3-backend \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
echo "Cloud Run services will auto-start on first request."
