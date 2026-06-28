# PULSE OCP Binary Build Runbook

Use this runbook when the code exists only on your local machine and is not yet in a remote Git repository.

## Why this path

OpenShift can build directly from a local directory upload using `oc start-build --from-dir`. This avoids creating a Git repo first.

## 0) Pre-Checks

1. `oc` CLI is installed locally.
2. You can log in to the cluster.
3. The cluster can pull the selected builder images.
4. Your local folder contains the full `pulse` repo content.
5. Database target details and backend secrets are ready.

## 1) Update Templates

1. Edit backend route host in `ocp/backend/05-route.yaml`.
2. Edit frontend route host in `ocp/frontend/04-route.yaml`.
3. Set frontend `NEXT_PUBLIC_API_URL` in `ocp/frontend/01-buildconfig-binary.yaml` to the backend route.
4. Set backend `CORS_ORIGINS` in `ocp/backend/01-configmap.yaml` to the frontend route.
5. If needed, change builder image names to images approved in your cluster.

## 2) Create Build Resources

Commands:

- oc apply -f ocp/backend/00-imagestream.yaml
- oc apply -f ocp/backend/01-buildconfig-binary.yaml
- oc apply -f ocp/frontend/00-imagestream.yaml
- oc apply -f ocp/frontend/01-buildconfig-binary.yaml

## 3) Create Backend Secret

1. Copy `ocp/backend/02-secret.template.yaml` to `ocp/backend/02-secret.yaml`.
2. Replace placeholder values.
3. Apply it.

Command:

- oc apply -f ocp/backend/02-secret.yaml

## 4) Start Binary Builds From Local Source

Run these commands from the repository root folder, which should be the folder containing `backend/`, `frontend/`, and `ocp/`.

Commands:

- oc start-build pulse-backend --from-dir=backend --follow
- oc start-build pulse-frontend --from-dir=frontend --follow

Expected:

- Backend build runs Gradle `bootJar` through `.s2i/bin/assemble`.
- Frontend build runs `npm ci`, `npm run build`, and `npm prune --omit=dev` through `.s2i/bin/assemble`.

## 5) Deploy Backend

Commands:

- oc apply -f ocp/backend/01-configmap.yaml
- oc apply -f ocp/backend/03-deployment.yaml
- oc apply -f ocp/backend/04-service.yaml
- oc apply -f ocp/backend/05-route.yaml
- oc rollout status deploy/pulse-backend

Validation:

- oc logs deploy/pulse-backend --tail=100
- curl -k https://<backend-route>/actuator/health

## 6) Deploy Frontend

Commands:

- oc apply -f ocp/frontend/01-configmap.yaml
- oc apply -f ocp/frontend/02-deployment.yaml
- oc apply -f ocp/frontend/03-service.yaml
- oc apply -f ocp/frontend/04-route.yaml
- oc rollout status deploy/pulse-frontend

Validation:

- oc logs deploy/pulse-frontend --tail=100
- curl -k https://<frontend-route>

## 7) When to move to Git-based builds later

Switch to the Git-based BuildConfigs once:

1. The repo is initialized with Git.
2. A remote is created and reachable by OpenShift.
3. You want reproducible rebuilds without uploading source from your laptop each time.
