#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE GCP Cloud Run Deploy Script
# Deploys: Cloud SQL (Postgres), Backend (Spring Boot), Frontend (Next.js)
########################################################################

PROJECT_ID="pulse-489421"
REGION="us-central1"
REPO="pulse-repo"
REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
SQL_INSTANCE="pulse-db"
FRONTEND_SERVICE="pulse-frontend"
BACKEND_SERVICE="pulse-backend"

# Parse flags
COMPONENT="${1:-all}"  # all | frontend | backend | db

echo "=== PULSE GCP Deploy ==="
echo "Project:   ${PROJECT_ID}"
echo "Region:    ${REGION}"
echo "Component: ${COMPONENT}"
echo ""

gcloud config set project "${PROJECT_ID}" --quiet

########################################################################
# DB (Cloud SQL)
########################################################################
deploy_db() {
  echo "--- [DB] Checking Cloud SQL instance ---"
  if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
    STATUS=$(gcloud sql instances describe "${SQL_INSTANCE}" --format="value(state)" 2>/dev/null)
    if [ "${STATUS}" = "STOPPED" ] || [ "${STATUS}" = "SUSPENDED" ]; then
      echo "[DB] Instance is ${STATUS}, starting..."
      gcloud sql instances patch "${SQL_INSTANCE}" --activation-policy=ALWAYS --quiet
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
      --quiet

    echo "[DB] Creating database..."
    gcloud sql databases create pulse --instance="${SQL_INSTANCE}" --quiet

    echo "[DB] Setting password..."
    gcloud sql users set-password postgres \
      --instance="${SQL_INSTANCE}" \
      --password=pulse \
      --quiet

    echo "[DB] Creating app user..."
    gcloud sql users create pulse \
      --instance="${SQL_INSTANCE}" \
      --password=pulse \
      --quiet
  fi
  echo "[DB] Done."
}

########################################################################
# Backend
########################################################################
deploy_backend() {
  echo "--- [Backend] Building image ---"
  docker build --platform linux/amd64 \
    -t "${REGISTRY}/pulse-backend:latest" \
    ../backend

  echo "--- [Backend] Pushing image ---"
  docker push "${REGISTRY}/pulse-backend:latest"

  SQL_CONNECTION="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}"

  # Get frontend URL for CORS
  FRONTEND_URL=$(gcloud run services describe "${FRONTEND_SERVICE}" \
    --region="${REGION}" --format="value(status.url)" 2>/dev/null || echo "")

  echo "--- [Backend] Deploying to Cloud Run ---"
  gcloud run deploy "${BACKEND_SERVICE}" \
    --image="${REGISTRY}/pulse-backend:latest" \
    --region="${REGION}" \
    --port=8080 \
    --allow-unauthenticated \
    --add-cloudsql-instances="${SQL_CONNECTION}" \
    --memory=1Gi \
    --set-env-vars="SPRING_PROFILES_ACTIVE=dev" \
    --set-env-vars="SPRING_CLOUD_GCP_SQL_INSTANCE_CONNECTION_NAME=${SQL_CONNECTION}" \
    --set-env-vars="DB_NAME=pulse" \
    --set-env-vars="DB_USER=pulse" \
    --set-env-vars="DB_PASSWORD=pulse" \
    --set-env-vars="CORS_ORIGINS=${FRONTEND_URL:-http://localhost:3000}" \
    --set-env-vars="AUTH_ENABLED=false" \
    --set-env-vars="GCP_SECRET_MANAGER_MODE=gcp-secret-manager" \
    --set-env-vars="GCP_DEV_PROJECT=${PROJECT_ID}" \
    --set-env-vars="DBT_TARGET=dev" \
    --quiet

  echo "[Backend] Done."
}

########################################################################
# Frontend
########################################################################
deploy_frontend() {
  echo "--- [Frontend] Building image (BUILD_ENV=dev) ---"
  docker build --platform linux/amd64 \
    --build-arg BUILD_ENV=dev \
    -t "${REGISTRY}/pulse-frontend:latest" \
    ../frontend

  echo "--- [Frontend] Pushing image ---"
  docker push "${REGISTRY}/pulse-frontend:latest"

  echo "--- [Frontend] Deploying to Cloud Run ---"
  gcloud run deploy "${FRONTEND_SERVICE}" \
    --image="${REGISTRY}/pulse-frontend:latest" \
    --region="${REGION}" \
    --port=3000 \
    --memory=1Gi \
    --allow-unauthenticated \
    --quiet

  echo "[Frontend] Done."
}

########################################################################
# Main
########################################################################
cd "$(dirname "$0")"

case "${COMPONENT}" in
  all)
    deploy_db
    deploy_backend
    deploy_frontend
    ;;
  db)
    deploy_db
    ;;
  backend)
    deploy_db  # ensure DB is running
    deploy_backend
    ;;
  frontend)
    deploy_frontend
    ;;
  *)
    echo "Usage: $0 [all|frontend|backend|db]"
    exit 1
    ;;
esac

echo ""
echo "=== Deploy Complete ==="
FRONTEND_URL=$(gcloud run services describe "${FRONTEND_SERVICE}" --region="${REGION}" --format="value(status.url)" 2>/dev/null || echo "N/A")
BACKEND_URL=$(gcloud run services describe "${BACKEND_SERVICE}" --region="${REGION}" --format="value(status.url)" 2>/dev/null || echo "N/A")
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
