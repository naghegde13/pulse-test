#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE GCP Start Script
# Starts Cloud SQL. Cloud Run auto-starts on incoming requests.
########################################################################

PROJECT_ID="pulse-489421"
REGION="us-central1"
SQL_INSTANCE="pulse-db"

echo "=== PULSE GCP Start ==="
gcloud config set project "${PROJECT_ID}" --quiet

# Start Cloud SQL
echo "--- Starting Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" --format="value(state)" 2>/dev/null)
  if [ "${STATUS}" = "STOPPED" ] || [ "${STATUS}" = "SUSPENDED" ]; then
    gcloud sql instances patch "${SQL_INSTANCE}" --activation-policy=ALWAYS --quiet
    echo "[DB] Starting... (may take 1-2 minutes)"
    echo "[DB] Waiting for instance to be ready..."
    while true; do
      STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" --format="value(state)" 2>/dev/null)
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
  echo "[DB] Instance not found. Run 'scripts/gcp-deploy.sh' first."
  exit 1
fi

echo ""
echo "=== Started ==="
FRONTEND_URL=$(gcloud run services describe pulse-frontend --region="${REGION}" --format="value(status.url)" 2>/dev/null || echo "N/A")
BACKEND_URL=$(gcloud run services describe pulse-backend --region="${REGION}" --format="value(status.url)" 2>/dev/null || echo "N/A")
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
echo "Cloud Run services will auto-start on first request."
