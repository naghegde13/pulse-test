#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE3 GCP Stop Script
# Stops Cloud SQL to save costs. Cloud Run auto-scales to 0 on its own.
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

echo "=== PULSE3 GCP Stop ==="

echo "--- Stopping Cloud SQL instance ---"
if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
  STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" \
    --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)
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
for SVC in pulse3-frontend pulse3-backend; do
  if gcloud run services describe "${SVC}" \
    --region="${REGION}" --project="${PROJECT_ID}" &>/dev/null; then
    gcloud run services update "${SVC}" \
      --region="${REGION}" --project="${PROJECT_ID}" \
      --min-instances=0 --quiet 2>/dev/null || true
    echo "[${SVC}] Will scale to 0 when idle."
  fi
done

echo ""
echo "=== Stopped (PULSE3) ==="
echo "Cloud SQL is stopped (no charges for compute)."
echo "Cloud Run services will auto-stop when no traffic."
echo "Run 'scripts/gcp-start-pulse3.sh' to resume."
