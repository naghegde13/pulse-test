#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE3 GCP Cloud Run Deploy Script
# Deploys: Cloud SQL (Postgres), Backend (Spring Boot), Frontend (Next.js)
# Hardcoded to project: pulse3-497602
########################################################################

PROJECT_ID="pulse3-497602"
REGION="us-central1"
SA_KEY="/Users/aameradam/projects/dev/pulse3/pulse_sa_credentials.json"
SA_EMAIL="pulse-sa@pulse3-497602.iam.gserviceaccount.com"
REPO="pulse3-repo"
REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
SQL_INSTANCE="pulse3-db"
FRONTEND_SERVICE="pulse3-frontend"
BACKEND_SERVICE="pulse3-backend"
DB_PASSWORD_FILE="/Users/aameradam/projects/dev/pulse3/pulse3-db-password.txt"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

COMPONENT="${1:-all}"

# -- gcloud isolation: process-local config, no global mutation --
export CLOUDSDK_ACTIVE_CONFIG_NAME=pulse3
gcloud config configurations create pulse3 2>/dev/null || true
gcloud auth activate-service-account "${SA_EMAIL}" \
  --key-file="${SA_KEY}" --configuration=pulse3 --quiet
gcloud config set project "${PROJECT_ID}" --configuration=pulse3 --quiet

echo "=== PULSE3 GCP Deploy ==="
echo "Project:   ${PROJECT_ID}"
echo "Region:    ${REGION}"
echo "Component: ${COMPONENT}"
echo ""

########################################################################
# DB Password
########################################################################
get_or_create_password() {
  if [ -f "${DB_PASSWORD_FILE}" ]; then
    cat "${DB_PASSWORD_FILE}"
  else
    PW=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)
    echo -n "${PW}" > "${DB_PASSWORD_FILE}"
    chmod 600 "${DB_PASSWORD_FILE}"
    echo "${PW}"
  fi
}

DB_PASSWORD="$(get_or_create_password)"

########################################################################
# DB (Cloud SQL)
########################################################################
deploy_db() {
  echo "--- [DB] Checking Cloud SQL instance ---"
  if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
    STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" \
      --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)
    if [ "${STATUS}" = "STOPPED" ] || [ "${STATUS}" = "SUSPENDED" ]; then
      echo "[DB] Instance is ${STATUS}, starting..."
      gcloud sql instances patch "${SQL_INSTANCE}" \
        --activation-policy=ALWAYS --project="${PROJECT_ID}" --quiet
      echo "[DB] Waiting for instance to be ready..."
      sleep 30
    else
      echo "[DB] Instance already running (${STATUS})"
    fi
  else
    echo "[DB] Creating Cloud SQL instance..."
    gcloud sql instances create "${SQL_INSTANCE}" \
      --database-version=POSTGRES_16 \
      --tier=db-f1-micro \
      --region="${REGION}" \
      --edition=ENTERPRISE \
      --project="${PROJECT_ID}" \
      --quiet

    echo "[DB] Creating database..."
    gcloud sql databases create pulse \
      --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" --quiet

    echo "[DB] Setting postgres password..."
    gcloud sql users set-password postgres \
      --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" \
      --password="${DB_PASSWORD}" --quiet

    echo "[DB] Creating app user..."
    gcloud sql users create pulse \
      --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" \
      --password="${DB_PASSWORD}" --quiet
  fi
  echo "[DB] Done."
}

########################################################################
# Backend
########################################################################
deploy_backend() {
  echo "--- [Backend] Building image (cross-compile arm64->amd64) ---"
  docker build --platform linux/amd64 \
    -t "${REGISTRY}/pulse-backend:latest" \
    "${REPO_ROOT}/backend"

  echo "--- [Backend] Pushing image ---"
  docker push "${REGISTRY}/pulse-backend:latest"

  SQL_CONNECTION="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}"

  FRONTEND_URL=$(gcloud run services describe "${FRONTEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")

  echo "--- [Backend] Deploying to Cloud Run ---"
  gcloud run deploy "${BACKEND_SERVICE}" \
    --image="${REGISTRY}/pulse-backend:latest" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --port=8080 \
    --allow-unauthenticated \
    --add-cloudsql-instances="${SQL_CONNECTION}" \
    --memory=1Gi \
    --set-env-vars="SPRING_PROFILES_ACTIVE=dev" \
    --set-env-vars="SPRING_CLOUD_GCP_SQL_INSTANCE_CONNECTION_NAME=${SQL_CONNECTION}" \
    --set-env-vars="DB_NAME=pulse" \
    --set-env-vars="DB_USER=pulse" \
    --set-env-vars="DB_PASSWORD=${DB_PASSWORD}" \
    --set-env-vars="CORS_ORIGINS=${FRONTEND_URL:-http://localhost:3000}" \
    --set-env-vars="AUTH_ENABLED=true" \
    --set-env-vars="GCP_SECRET_MANAGER_MODE=local-stub" \
    --set-env-vars="GCP_DEV_PROJECT=${PROJECT_ID}" \
    --set-env-vars="DBT_TARGET=dev" \
    --quiet

  echo "[Backend] Done."
}

########################################################################
# Frontend
########################################################################
deploy_frontend() {
  BACKEND_URL=$(gcloud run services describe "${BACKEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")

  if [ -z "${BACKEND_URL}" ]; then
    echo "[Frontend] WARNING: Backend URL not found. Deploy backend first."
    echo "[Frontend] Building without NEXT_PUBLIC_API_URL (will default to localhost:8080)."
  else
    echo "[Frontend] Backend URL: ${BACKEND_URL}"
    echo "NEXT_PUBLIC_API_URL=${BACKEND_URL}" > "${REPO_ROOT}/frontend/.env.pulse3"
    echo "[Frontend] Wrote frontend/.env.pulse3"
  fi

  echo "--- [Frontend] Building image (BUILD_ENV=pulse3) ---"
  docker build --platform linux/amd64 \
    --build-arg BUILD_ENV=pulse3 \
    -t "${REGISTRY}/pulse-frontend:latest" \
    "${REPO_ROOT}/frontend"

  echo "--- [Frontend] Pushing image ---"
  docker push "${REGISTRY}/pulse-frontend:latest"

  echo "--- [Frontend] Deploying to Cloud Run ---"
  gcloud run deploy "${FRONTEND_SERVICE}" \
    --image="${REGISTRY}/pulse-frontend:latest" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --port=3000 \
    --memory=1Gi \
    --allow-unauthenticated \
    --set-env-vars="NEXT_PUBLIC_API_URL=${BACKEND_URL:-http://localhost:8080}" \
    --quiet

  echo "[Frontend] Done."
}

########################################################################
# Post-deploy: update backend CORS with frontend URL
########################################################################
update_cors() {
  FRONTEND_URL=$(gcloud run services describe "${FRONTEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")

  if [ -n "${FRONTEND_URL}" ]; then
    echo "--- [CORS] Updating backend CORS_ORIGINS ---"
    gcloud run services update "${BACKEND_SERVICE}" \
      --region="${REGION}" \
      --project="${PROJECT_ID}" \
      --update-env-vars="CORS_ORIGINS=${FRONTEND_URL}" \
      --quiet
    echo "[CORS] Backend CORS set to ${FRONTEND_URL}"
  else
    echo "[CORS] WARNING: Could not determine frontend URL. CORS not updated."
  fi
}

########################################################################
# Main
########################################################################
case "${COMPONENT}" in
  all)
    deploy_db
    deploy_backend
    deploy_frontend
    update_cors
    ;;
  db)
    deploy_db
    ;;
  backend)
    deploy_db
    deploy_backend
    ;;
  frontend)
    deploy_frontend
    update_cors
    ;;
  *)
    echo "Usage: $0 [all|frontend|backend|db]"
    exit 1
    ;;
esac

echo ""
echo "=== Deploy Complete (PULSE3) ==="
FRONTEND_URL=$(gcloud run services describe "${FRONTEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")
BACKEND_URL=$(gcloud run services describe "${BACKEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
echo ""
echo "DB password saved to: ${DB_PASSWORD_FILE}"
