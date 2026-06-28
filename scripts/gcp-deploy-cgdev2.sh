#!/usr/bin/env bash
set -euo pipefail

########################################################################
# PULSE CG DEV2 GCP Cloud Run Deploy Script
# Deploys: Cloud SQL (Postgres), Backend (Spring Boot), Frontend (Next.js)
# Hardcoded to Capgemini-provided project: wf-pulse-agentic-dev2
########################################################################

PROJECT_ID="wf-pulse-agentic-dev2"
REGION="us-central1"
CONFIG_NAME="cgdev2"
DEPLOY_SA_EMAIL="pulse-handoff-sa@wf-pulse-agentic-dev2.iam.gserviceaccount.com"
DEPLOY_SA_KEY="${CGDEV2_DEPLOY_SA_KEY:-/Users/aameradam/projects/dev/PULSE-integration/.secrets/gcp/wf-pulse-agentic-dev2-5b6e39d376d5.json}"
RUNTIME_SA="sa-pulse-cgdev2-controlplane@wf-pulse-agentic-dev2.iam.gserviceaccount.com"
REPO="pulse-cgdev2-repo"
REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
SQL_INSTANCE="pulse-cgdev2-db"
FRONTEND_SERVICE="pulse-cgdev2-frontend"
BACKEND_SERVICE="pulse-cgdev2-backend"
SECRET_DIR="${CGDEV2_SECRET_DIR:-/Users/aameradam/projects/dev/PULSE-integration/.secrets/gcp}"
DB_PASSWORD_FILE="${SECRET_DIR}/cgdev2-db-password.txt"
JWT_SECRET_FILE="${SECRET_DIR}/cgdev2-jwt-secret.txt"
DB_PASSWORD_SECRET="pulse-cgdev2-db-password"
JWT_SECRET_SECRET="pulse-cgdev2-jwt-secret"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_MODE="${CGDEV2_BUILD_MODE:-auto}" # auto | docker | cloudbuild

COMPONENT="${1:-all}" # all | frontend | backend | db | bootstrap

export CLOUDSDK_ACTIVE_CONFIG_NAME="${CONFIG_NAME}"
gcloud config configurations create "${CONFIG_NAME}" 2>/dev/null || true
gcloud auth activate-service-account "${DEPLOY_SA_EMAIL}" \
  --key-file="${DEPLOY_SA_KEY}" --configuration="${CONFIG_NAME}" --quiet
gcloud config set project "${PROJECT_ID}" --configuration="${CONFIG_NAME}" --quiet

echo "=== PULSE CG DEV2 GCP Deploy ==="
echo "Project:   ${PROJECT_ID}"
echo "Region:    ${REGION}"
echo "Component: ${COMPONENT}"
echo "Build:     ${BUILD_MODE}"
echo ""

########################################################################
# Secrets
########################################################################
get_or_create_secret_file() {
  local file="$1"
  local length="$2"
  mkdir -p "$(dirname "${file}")"
  if [ -f "${file}" ]; then
    cat "${file}"
  else
    local value
    value="$(openssl rand -base64 48 | tr -d '/+=' | head -c "${length}")"
    echo -n "${value}" > "${file}"
    chmod 600 "${file}"
    echo "${value}"
  fi
}

DB_PASSWORD="$(get_or_create_secret_file "${DB_PASSWORD_FILE}" 24)"
JWT_SECRET="$(get_or_create_secret_file "${JWT_SECRET_FILE}" 64)"

sync_cloud_secret() {
  local name="$1"
  local value="$2"
  if gcloud secrets describe "${name}" --project="${PROJECT_ID}" &>/dev/null; then
    printf "%s" "${value}" | gcloud secrets versions add "${name}" \
      --project="${PROJECT_ID}" \
      --data-file=- \
      --quiet >/dev/null
  else
    printf "%s" "${value}" | gcloud secrets create "${name}" \
      --project="${PROJECT_ID}" \
      --replication-policy=automatic \
      --data-file=- \
      --quiet >/dev/null
  fi
}

########################################################################
# Bootstrap
########################################################################
bootstrap() {
  echo "--- [Bootstrap] Enabling required APIs ---"
  gcloud services enable \
    run.googleapis.com \
    sqladmin.googleapis.com \
    sql-component.googleapis.com \
    artifactregistry.googleapis.com \
    cloudbuild.googleapis.com \
    iam.googleapis.com \
    iamcredentials.googleapis.com \
    cloudresourcemanager.googleapis.com \
    logging.googleapis.com \
    secretmanager.googleapis.com \
    aiplatform.googleapis.com \
    --project="${PROJECT_ID}" --quiet

  echo "--- [Bootstrap] Ensuring runtime service account roles ---"
  for role in \
    roles/cloudsql.client \
    roles/aiplatform.user \
    roles/logging.logWriter \
    roles/secretmanager.secretAccessor; do
    gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
      --member="serviceAccount:${RUNTIME_SA}" \
      --role="${role}" \
      --quiet --no-user-output-enabled
  done

  echo "--- [Bootstrap] Ensuring deployer can attach runtime service account ---"
  gcloud iam service-accounts add-iam-policy-binding "${RUNTIME_SA}" \
    --project="${PROJECT_ID}" \
    --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
    --role="roles/iam.serviceAccountUser" \
    --quiet --no-user-output-enabled

  echo "--- [Bootstrap] Ensuring Artifact Registry repository ---"
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

  echo "--- [Bootstrap] Granting Cloud Build Artifact Registry access ---"
  local project_number
  project_number="$(gcloud projects describe "${PROJECT_ID}" --format="value(projectNumber)")"
  for build_sa in \
    "${project_number}@cloudbuild.gserviceaccount.com" \
    "${project_number}-compute@developer.gserviceaccount.com"; do
    for role in \
      roles/cloudbuild.builds.builder \
      roles/artifactregistry.writer \
      roles/storage.objectAdmin \
      roles/logging.logWriter; do
      gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
        --member="serviceAccount:${build_sa}" \
        --role="${role}" \
        --quiet --no-user-output-enabled || true
    done
  done

  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet
  echo "[Bootstrap] Done."
}

########################################################################
# Build helpers
########################################################################
use_cloudbuild() {
  case "${BUILD_MODE}" in
    cloudbuild)
      return 0
      ;;
    docker)
      return 1
      ;;
    auto)
      if docker info >/dev/null 2>&1; then
        return 1
      fi
      return 0
      ;;
    *)
      echo "Invalid CGDEV2_BUILD_MODE=${BUILD_MODE}; expected auto, docker, or cloudbuild" >&2
      exit 1
      ;;
  esac
}

build_image() {
  local name="$1"
  local context_dir="$2"
  local image="$3"
  shift 3
  local docker_args=("$@")

  if use_cloudbuild; then
    echo "--- [${name}] Building image with Cloud Build ---"
    local config
    config="$(mktemp)"
    {
      echo "steps:"
      echo "- name: gcr.io/cloud-builders/docker"
      printf "  args: ['build'"
      for arg in "${docker_args[@]}"; do
        printf ", '%s'" "${arg}"
      done
      printf ", '-t', '%s', '.']\n" "${image}"
      echo "images:"
      echo "- ${image}"
    } > "${config}"
    gcloud builds submit "${context_dir}" \
      --config="${config}" \
      --project="${PROJECT_ID}" \
      --quiet
    rm -f "${config}"
  else
    echo "--- [${name}] Building image with local Docker ---"
    docker build "${docker_args[@]}" -t "${image}" "${context_dir}"
    echo "--- [${name}] Pushing image ---"
    docker push "${image}"
  fi
}

frontend_cors_origins() {
  local frontend_url="$1"
  if [ -z "${frontend_url}" ]; then
    echo "http://localhost:3000"
    return
  fi

  local project_number
  project_number="$(gcloud projects describe "${PROJECT_ID}" --format="value(projectNumber)")"
  echo "${frontend_url},https://${FRONTEND_SERVICE}-${project_number}.${REGION}.run.app"
}

########################################################################
# DB (Cloud SQL)
########################################################################
deploy_db() {
  echo "--- [DB] Checking Cloud SQL instance ---"
  if gcloud sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
    local status
    status="$(gcloud sql instances describe "${SQL_INSTANCE}" \
      --project="${PROJECT_ID}" --format="value(state)" 2>/dev/null)"
    if [ "${status}" = "STOPPED" ] || [ "${status}" = "SUSPENDED" ]; then
      echo "[DB] Instance is ${status}, starting..."
      gcloud sql instances patch "${SQL_INSTANCE}" \
        --activation-policy=ALWAYS --project="${PROJECT_ID}" --quiet
      echo "[DB] Waiting for instance to be ready..."
      sleep 30
    else
      echo "[DB] Instance already running (${status})"
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
  fi

  if gcloud sql databases describe pulse \
    --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" &>/dev/null; then
    echo "[DB] Database pulse already exists."
  else
    echo "[DB] Creating database pulse..."
    gcloud sql databases create pulse \
      --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" --quiet
  fi

  echo "[DB] Setting postgres password..."
  gcloud sql users set-password postgres \
    --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" \
    --password="${DB_PASSWORD}" --quiet

  if gcloud sql users list \
    --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" \
    --format="value(name)" | grep -qx "pulse"; then
    echo "[DB] Updating app user password..."
    gcloud sql users set-password pulse \
      --instance="${SQL_INSTANCE}" --project="${PROJECT_ID}" \
      --password="${DB_PASSWORD}" --quiet
  else
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
  local image="${REGISTRY}/pulse-backend:latest"
  build_image "Backend" "${REPO_ROOT}/backend" "${image}" --platform linux/amd64

  local sql_connection
  sql_connection="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}"

  local frontend_url
  frontend_url="$(gcloud run services describe "${FRONTEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")"
  local cors_origins
  cors_origins="$(frontend_cors_origins "${frontend_url}")"

  echo "--- [Backend] Syncing Cloud Run secrets ---"
  sync_cloud_secret "${DB_PASSWORD_SECRET}" "${DB_PASSWORD}"
  sync_cloud_secret "${JWT_SECRET_SECRET}" "${JWT_SECRET}"

  echo "--- [Backend] Deploying to Cloud Run ---"
  gcloud run deploy "${BACKEND_SERVICE}" \
    --image="${image}" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --service-account="${RUNTIME_SA}" \
    --port=8080 \
    --allow-unauthenticated \
    --add-cloudsql-instances="${sql_connection}" \
    --memory=2Gi \
    --cpu=1 \
    --min-instances=0 \
    --set-env-vars="SPRING_PROFILES_ACTIVE=dev" \
    --set-env-vars="SPRING_CLOUD_GCP_SQL_INSTANCE_CONNECTION_NAME=${sql_connection}" \
    --set-env-vars="DB_NAME=pulse" \
    --set-env-vars="DB_USER=pulse" \
    --set-env-vars="^|^CORS_ORIGINS=${cors_origins}" \
    --set-env-vars="AUTH_ENABLED=false" \
    --set-env-vars="GCP_SECRET_MANAGER_MODE=gcp-secret-manager" \
    --set-env-vars="GCP_DEV_PROJECT=${PROJECT_ID}" \
    --set-env-vars="GCP_DEV_REGION=${REGION}" \
    --set-env-vars="PULSE_LLM_PROVIDER=vertex" \
    --set-env-vars="VERTEX_PROJECT_ID=${PROJECT_ID}" \
    --set-env-vars="VERTEX_LOCATION=global" \
    --set-env-vars="PULSE_GIT_CLONE_BASE=/tmp/pulse/repos" \
    --set-env-vars="PULSE_SECRET_LOCAL_STUB_BASE=/tmp/pulse/secrets" \
    --set-env-vars="DBT_TARGET=dev" \
    --set-secrets="DB_PASSWORD=${DB_PASSWORD_SECRET}:latest,JWT_SECRET=${JWT_SECRET_SECRET}:latest" \
    --quiet

  echo "[Backend] Done."
}

########################################################################
# Frontend
########################################################################
deploy_frontend() {
  local backend_url
  backend_url="$(gcloud run services describe "${BACKEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")"

  if [ -z "${backend_url}" ]; then
    echo "[Frontend] WARNING: Backend URL not found. Deploy backend first."
    echo "[Frontend] Building without NEXT_PUBLIC_API_URL (will default to localhost:8080)."
  else
    echo "[Frontend] Backend URL: ${backend_url}"
    echo "NEXT_PUBLIC_API_URL=${backend_url}" > "${REPO_ROOT}/frontend/.env.cgdev2"
    echo "[Frontend] Wrote frontend/.env.cgdev2"
  fi

  local image="${REGISTRY}/pulse-frontend:latest"
  build_image "Frontend" "${REPO_ROOT}/frontend" "${image}" \
    --platform linux/amd64 \
    --build-arg BUILD_ENV=cgdev2 \
    --build-arg "NEXT_PUBLIC_API_URL=${backend_url:-http://localhost:8080}"

  echo "--- [Frontend] Deploying to Cloud Run ---"
  gcloud run deploy "${FRONTEND_SERVICE}" \
    --image="${image}" \
    --region="${REGION}" \
    --project="${PROJECT_ID}" \
    --port=3000 \
    --memory=1Gi \
    --cpu=1 \
    --min-instances=0 \
    --allow-unauthenticated \
    --set-env-vars="NEXT_PUBLIC_API_URL=${backend_url:-http://localhost:8080}" \
    --quiet

  echo "[Frontend] Done."
}

########################################################################
# Post-deploy: update backend CORS with frontend URL
########################################################################
update_cors() {
  local frontend_url
  frontend_url="$(gcloud run services describe "${FRONTEND_SERVICE}" \
    --region="${REGION}" --project="${PROJECT_ID}" \
    --format="value(status.url)" 2>/dev/null || echo "")"

  if [ -n "${frontend_url}" ]; then
    local cors_origins
    cors_origins="$(frontend_cors_origins "${frontend_url}")"
    echo "--- [CORS] Updating backend CORS_ORIGINS ---"
    gcloud run services update "${BACKEND_SERVICE}" \
      --region="${REGION}" \
      --project="${PROJECT_ID}" \
      --update-env-vars="^|^CORS_ORIGINS=${cors_origins}" \
      --quiet
    echo "[CORS] Backend CORS set to ${cors_origins}"
  else
    echo "[CORS] WARNING: Could not determine frontend URL. CORS not updated."
  fi
}

########################################################################
# Main
########################################################################
case "${COMPONENT}" in
  all)
    bootstrap
    deploy_db
    deploy_backend
    deploy_frontend
    update_cors
    ;;
  bootstrap)
    bootstrap
    ;;
  db)
    bootstrap
    deploy_db
    ;;
  backend)
    bootstrap
    deploy_db
    deploy_backend
    ;;
  frontend)
    bootstrap
    deploy_frontend
    update_cors
    ;;
  *)
    echo "Usage: $0 [all|frontend|backend|db|bootstrap]"
    exit 1
    ;;
esac

echo ""
echo "=== Deploy Complete (CG DEV2) ==="
FRONTEND_URL="$(gcloud run services describe "${FRONTEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")"
BACKEND_URL="$(gcloud run services describe "${BACKEND_SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" \
  --format="value(status.url)" 2>/dev/null || echo "N/A")"
echo "Frontend: ${FRONTEND_URL}"
echo "Backend:  ${BACKEND_URL}"
echo ""
echo "DB password saved to: ${DB_PASSWORD_FILE}"
echo "JWT secret saved to:  ${JWT_SECRET_FILE}"
