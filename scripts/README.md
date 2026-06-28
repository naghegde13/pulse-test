# PULSE GCP Deployment

## CG DEV2 (Capgemini Dev 2)

The Capgemini-provided development project is `wf-pulse-agentic-dev2` in
`us-central1`. Use the `cgdev2` scripts for this environment.

Canonical live URLs:

- Frontend: https://pulse-cgdev2-frontend-7zwfresdqa-uc.a.run.app
- Backend: https://pulse-cgdev2-backend-7zwfresdqa-uc.a.run.app

Secure Source Manager:

- Web UI: https://pulse-cgdev2-47401359945.us-central1.sourcemanager.dev/wf-pulse-agentic-dev2/pulse
- Git HTTPS: https://pulse-cgdev2-47401359945-git.us-central1.sourcemanager.dev/wf-pulse-agentic-dev2/pulse.git
- Primary branch: `cg-env-transition`

The SSM instance is public-network reachable and protected by IAM. Current
repo writers are `kodumuru-nagasai.lohitha@capgemini.com`,
`anuhya.pulakandam@capgemini.com`, and
`kasi-lakshmi.kasi-lakshmi@capgemini.com`. Google IAM did not recognize
`vanga.srikanth@capgemini.com` or `munuswamy.loganathan@capgemini.com` when
access was attempted; Capgemini must confirm or provision those identities
before they can be granted repo access.

Cloud Run may also print project-number hostnames during deploy; these are also
allowed by backend CORS:

- Frontend: https://pulse-cgdev2-frontend-47401359945.us-central1.run.app
- Backend: https://pulse-cgdev2-backend-47401359945.us-central1.run.app

Primary commands:

```bash
scripts/authenticate-cgdev2.sh
CGDEV2_BUILD_MODE=cloudbuild scripts/gcp-deploy-cgdev2.sh all
scripts/gcp-stop-cgdev2.sh
scripts/gcp-start-cgdev2.sh
```

Current CG DEV2 resources:

- Cloud Run backend: `pulse-cgdev2-backend`
- Cloud Run frontend: `pulse-cgdev2-frontend`
- Cloud SQL instance: `pulse-cgdev2-db`
- Artifact Registry repo: `pulse-cgdev2-repo`
- Backend runtime service account: `sa-pulse-cgdev2-controlplane@wf-pulse-agentic-dev2.iam.gserviceaccount.com`

The deployed backend uses Vertex AI (`PULSE_LLM_PROVIDER=vertex`) and stores
database/JWT secrets in Secret Manager. `scripts/gcp-stop-cgdev2.sh` stops Cloud
SQL for cost control; Cloud Run is configured to scale to zero when idle.

## Legacy Default Environment

The original scripts below target the older/default GCP project. Prefer the
CG DEV2 scripts above for the Capgemini-sponsored development environment.

## Prerequisites

- `gcloud` CLI installed and authenticated (`gcloud auth login`)
- Docker running
- GCP project: `pulse-489421`, region: `us-central1`

## Deploy

```bash
# Deploy everything (DB + backend + frontend)
scripts/gcp-deploy.sh all

# Deploy individual components
scripts/gcp-deploy.sh frontend
scripts/gcp-deploy.sh backend
scripts/gcp-deploy.sh db
```

Note: `backend` also ensures the DB is running before deploying.

## Stop / Start (cost saving)

```bash
# Stop Cloud SQL (Cloud Run auto-scales to 0 on its own)
scripts/gcp-stop.sh

# Start Cloud SQL back up (Cloud Run auto-starts on first request)
scripts/gcp-start.sh
```

## Live URLs

| Service  | URL |
|----------|-----|
| Frontend | https://pulse-frontend-tdogzj5zrq-uc.a.run.app |
| Backend  | https://pulse-backend-tdogzj5zrq-uc.a.run.app |

URLs are permanent and do not change between deploys.

## Environment Configuration

| | Local | GCP Dev |
|---|---|---|
| Frontend env | `.env.local` | `.env.dev` (loaded via `BUILD_ENV=dev` Docker arg) |
| Backend profile | `application.yml` (default) | `application-dev.yml` (activated via `SPRING_PROFILES_ACTIVE=dev`) |
| Database | Local Postgres (`localhost:5432`) | Cloud SQL (`pulse-db` instance) |

The deploy script handles all environment selection automatically.

## Flyway Migrations

Migrations run automatically on backend startup. No manual step needed. All scripts in `backend/src/main/resources/db/migration/` are applied in order, including seed data.
