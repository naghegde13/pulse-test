# PULSE Server Startup

This is the local startup reference for the PULSE frontend, backend, and compose-backed runtime substrate.

## Prerequisites

- Docker Desktop or a compatible Docker runtime
- Java 21
- Node.js with npm

## Default Local Ports

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Postgres: `localhost:5432`
- Redis: `localhost:6379`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`
- Airflow UI: `http://localhost:8081`
- Spark Master: `spark://localhost:7077`
- Spark Master UI: `http://localhost:8082`

The frontend defaults to calling the backend at `http://localhost:8080`, so no extra frontend env var is required for a standard local run.

## Start Everything

Use three terminals.

### 1. Start infrastructure

From the repo root:

```bash
docker compose up -d
```

This starts the local runtime substrate used by the agentic E2E harness:

- Postgres 16
- Redis 7
- MinIO + bucket bootstrap
- Airflow 2.10.4 (webserver + scheduler in one container)
- Spark master + worker

### 2. Start the backend

From `backend/`:

```bash
./gradlew bootRun
```

Or use the helper:

```bash
scripts/start-backend.sh
```

Notes:

- The backend is a Spring Boot app.
- It listens on `http://localhost:8080`.
- It expects Postgres and Redis to be running locally on their default ports.
- Local startup should use the default Spring profile, not `dev`.
- If `SPRING_PROFILES_ACTIVE=dev` is set in your shell, unset it before starting locally.
- The local agentic E2E target is the compose-backed runtime substrate above; static deployability alone is not the completion bar.

### 3. Start the frontend

From `frontend/`:

First run or after dependency changes:

```bash
npm ci
```

Then start the dev server:

```bash
npm run dev
```

Or use the helper:

```bash
scripts/start-frontend.sh
```

Notes:

- The frontend is a Next.js app.
- It listens on `http://localhost:3000`.

## Copy/Paste Startup Sequence

```bash
cd /Users/aameradam/projects/dev/PULSE
docker compose up -d

cd backend
./gradlew bootRun

cd ../frontend
npm ci
npm run dev
```

## Common Startup Problems

Check these first if something fails:

- Docker is running
- Java 21 is installed
- Ports `3000`, `5432`, `6379`, `8080`, `8081`, `8082`, `9000`, and `9001` are free
- Postgres, Redis, MinIO, Airflow, and Spark containers are healthy
- `SPRING_PROFILES_ACTIVE` is not set to `dev` for a local backend run

If you see `A database name must be provided`, you are likely booting with the GCP Cloud SQL profile enabled. Fix it with:

```bash
unset SPRING_PROFILES_ACTIVE
unset SPRING_CLOUD_GCP_SQL_INSTANCE_CONNECTION_NAME
./gradlew bootRun
```

### macOS: `/data: Read-only file system` in backend logs

On macOS the `/data` mount is read-only by default, so the local-stub Secret Manager
cannot create its `/data/pulse/repos/.secrets` directory. PKT-FINAL-3 (BUG-07) added
an auto-fallback to `${java.io.tmpdir}/pulse/secrets` with a loud boot-time WARN —
in most cases you can just ignore the WARN and PAT registration works. If you want
to pin the location explicitly:

```bash
export PULSE_GIT_CLONE_BASE=~/.pulse/repos   # or /tmp/pulse/repos
mkdir -p "$PULSE_GIT_CLONE_BASE"
./gradlew bootRun
```

The legacy env var `PULSE_GIT_LOCAL_BASE` is still honored for one release, but
the new canonical name is `PULSE_GIT_CLONE_BASE` (it describes a directory used
for cloning *remote* repos plus the dev-only secret stub — not a tenant-config
option). The Airflow runtime substrate mount in `docker-compose.yml` also reads
either variable.

### GitHub PAT validation always returns PROVIDER_UNAVAILABLE

By default PULSE uses a stub GitHub client that returns 503 for every call —
PAT validation cannot succeed in this mode. The boot log shows
`GitHub client mode: STUB` and the tenant readiness verdict surfaces a
`GITHUB_CLIENT_STUB_ACTIVE` blocker. Enable the real client:

```bash
export PULSE_GIT_GITHUB_ENABLED=true
./gradlew bootRun
```

The boot log should now show `GitHub client mode: REAL`. PAT validation will
make outbound HTTPS calls to `https://api.github.com/user`.

## Stop Services

Infrastructure from the repo root:

```bash
docker compose down
```

If you only need to recycle runtime services during E2E work, you can also use:

```bash
docker compose restart minio airflow spark-master spark-worker
```

Frontend and backend:

- Stop each dev server with `Ctrl+C`
