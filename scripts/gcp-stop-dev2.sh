#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE GCP Stop Script — DEV2
# Stops Cloud SQL to save costs. Cloud Run auto-scales to 0 on its own.
########################################################################

PROJECT_ID="gcp-mat"
REGION="us-central1"
SQL_INSTANCE="pulse-dev2-db"

echo "=== PULSE GCP Stop (DEV2) ==="
gcloud config set project "${PROJECT_ID}" --quiet

# Stop Cloud SQL (main cost saver)
echo "--- Stopping Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" --format="value(state)" 2>/dev/null)
  if [ "${STATUS}" = "RUNNABLE" ]; then
    gcloud sql instances patch "${SQL_INSTANCE}" --activation-policy=NEVER --quiet
    echo "[DB] Stopped. (Data is preserved)"
  else
    echo "[DB] Already stopped (${STATUS})"
  fi
else
  echo "[DB] Instance not found, nothing to stop."
fi

# Cloud Run services auto-scale to 0 when no traffic, so no action needed.
# But we can set min-instances to 0 explicitly to be safe.
echo "--- Ensuring Cloud Run services scale to zero ---"
for SVC in pulse-dev2-frontend pulse-dev2-backend; do
  if gcloud run services describe "${SVC}" --region="${REGION}" &>/dev/null; then
    gcloud run services update "${SVC}" --region="${REGION}" --min-instances=0 --quiet 2>/dev/null || true
    echo "[${SVC}] Will scale to 0 when idle."
  fi
done

echo ""
echo "=== Stopped (DEV2) ==="
echo "Cloud SQL is stopped (no charges for compute)."
echo "Cloud Run services will auto-stop when no traffic."
echo "Run 'scripts/gcp-start-dev2.sh' to resume."
