#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE3 GCP Bootstrap Script
# One-time setup: enable APIs, grant SA roles, create Artifact Registry
# Hardcoded to project: pulse3-497602
# Must be run with personal account (aamer@aamer.net) that owns the project.
########################################################################

PROJECT_ID="pulse3-497602"
REGION="us-central1"
SA_EMAIL="pulse-sa@pulse3-497602.iam.gserviceaccount.com"
REPO="pulse3-repo"

export CLOUDSDK_ACTIVE_CONFIG_NAME=pulse3
gcloud config configurations create pulse3 2>/dev/null || true
gcloud config set account aamer@aamer.net --configuration=pulse3
gcloud config set project "${PROJECT_ID}" --configuration=pulse3

echo "=== PULSE3 Bootstrap ==="
echo "Project: ${PROJECT_ID}"
echo "Region:  ${REGION}"
echo ""

echo "--- Enabling APIs ---"
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  artifactregistry.googleapis.com \
  sql-component.googleapis.com \
  iam.googleapis.com \
  cloudresourcemanager.googleapis.com \
  logging.googleapis.com \
  --project="${PROJECT_ID}" --quiet
echo "[APIs] Enabled. Waiting 30s for propagation..."
sleep 30

PROJECT_NUMBER=$(gcloud projects describe "${PROJECT_ID}" --format="value(projectNumber)")

echo "--- Granting SA roles ---"
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/editor" \
  --quiet --no-user-output-enabled
echo "[IAM] Granted roles/editor to ${SA_EMAIL}"

echo "--- Granting Cloud SQL Client to default compute SA ---"
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/cloudsql.client" \
  --quiet --no-user-output-enabled
echo "[IAM] Granted cloudsql.client to default compute SA"

echo "--- Creating Artifact Registry repo ---"
if gcloud artifacts repositories describe "${REPO}" \
  --location="${REGION}" --project="${PROJECT_ID}" &>/dev/null; then
  echo "[AR] Repository ${REPO} already exists."
else
  gcloud artifacts repositories create "${REPO}" \
    --repository-format=docker \
    --location="${REGION}" \
    --project="${PROJECT_ID}" \
    --quiet
  echo "[AR] Created ${REPO}."
fi

echo "--- Configuring Docker auth ---"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
echo "[Docker] Auth configured for ${REGION}-docker.pkg.dev"

echo ""
echo "=== Bootstrap Complete ==="
echo "Next: run scripts/gcp-deploy-pulse3.sh all"
