#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE CG DEV2 GCP Authentication
# Authenticates the administrative setup service account for deploy scripts.
########################################################################

PROJECT_ID="wf-pulse-agentic-dev2"
REGION="us-central1"
CONFIG_NAME="cgdev2"
DEPLOY_SA_EMAIL="pulse-handoff-sa@wf-pulse-agentic-dev2.iam.gserviceaccount.com"
DEPLOY_SA_KEY="${CGDEV2_DEPLOY_SA_KEY:-/Users/aameradam/projects/dev/PULSE-integration/.secrets/gcp/wf-pulse-agentic-dev2-5b6e39d376d5.json}"

export CLOUDSDK_ACTIVE_CONFIG_NAME="${CONFIG_NAME}"
gcloud config configurations create "${CONFIG_NAME}" 2>/dev/null || true
gcloud auth activate-service-account "${DEPLOY_SA_EMAIL}" \
  --key-file="${DEPLOY_SA_KEY}" --configuration="${CONFIG_NAME}" --quiet
gcloud config set project "${PROJECT_ID}" --configuration="${CONFIG_NAME}" --quiet
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

echo "Authenticated to PULSE CG DEV2 (${PROJECT_ID}) as ${DEPLOY_SA_EMAIL}"
