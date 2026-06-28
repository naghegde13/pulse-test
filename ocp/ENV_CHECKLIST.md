# OCP Environment Checklist

Fill this before deployment.

## Cluster and Access

- OCP API server URL:
- OCP project (namespace):
- Route base domain (example: apps.cluster.company.com):
- Access mode:
  - public routes
  - internal + SSO

## Container Images

- Build path selected:
  - binary build from local source
  - git build from remote repository
- Git repository URL accessible by OpenShift build:
- Git branch/tag to build:
- Backend image stream tag: pulse-backend:latest
- Frontend image stream tag: pulse-frontend:latest
- Can the cluster pull Red Hat builder images directly? yes/no
- Image pull secret required? yes/no
- If yes, image pull secret name:

## Backend Runtime

- DB_HOST:
- DB_PORT: 5432
- DB_NAME:
- DB_USER:
- DB_PASSWORD:
- JWT_SECRET:
- CORS_ORIGINS (must include frontend route URL):

## Optional Backend Integrations

- REDIS_HOST:
- REDIS_PORT:
- Enable Redis health now? yes/no
- PULSE_LLM_PROVIDER (vertex or openrouter):
- OPENROUTER_API_KEY or LLM_API_KEY:
- VERTEX_PROJECT_ID:
- VERTEX_CREDENTIALS_PATH or ADC strategy:

## Frontend Runtime

- NEXT_PUBLIC_API_URL (backend route URL, baked in at frontend image build time):

## OpenShift Sizing (initial)

- Backend replicas:
- Frontend replicas:
- Backend requests/limits CPU:
- Backend requests/limits memory:
- Frontend requests/limits CPU:
- Frontend requests/limits memory:

## DNS and TLS

- Backend route host:
- Frontend route host:
- Custom certificate needed? yes/no
- If yes, secret name:

## Validation Owners

- App owner:
- OCP platform owner:
- DBA owner:
- Security owner:
