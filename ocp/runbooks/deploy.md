# PULSE OCP Deployment Runbook

Use this order for first deployment.

This runbook is for the Git-backed build path. If the code only exists locally and has not been pushed to a remote repository yet, use `runbooks/binary-build.md` instead.

## 0) Pre-Checks

1. Login and target project.
2. Confirm OpenShift can reach the Git repository.
3. Confirm OpenShift can pull the selected builder images.
4. Confirm backend secret values are ready.
5. Confirm DB is reachable from OCP namespace.

Commands:

- oc login <api-server>
- oc project <namespace>
- oc get project

## 1) Update Templates

1. Edit backend Git URI/ref in backend/01-buildconfig.yaml.
2. Edit frontend Git URI/ref in frontend/01-buildconfig.yaml.
3. Edit backend route host in backend/05-route.yaml.
4. Edit frontend route host in frontend/04-route.yaml.
5. Set frontend NEXT_PUBLIC_API_URL build env in frontend/01-buildconfig.yaml to backend route.
6. Set backend CORS_ORIGINS in backend/01-configmap.yaml to frontend route.
7. If needed, change builder image names in the BuildConfigs to images approved in your cluster.
8. If needed, adjust deployment image references to your project namespace.

## 2) Create Build Resources

Commands:

- oc apply -f ocp/backend/00-imagestream.yaml
- oc apply -f ocp/backend/01-buildconfig.yaml
- oc apply -f ocp/frontend/00-imagestream.yaml
- oc apply -f ocp/frontend/01-buildconfig.yaml

What happens here:

- Backend build uses Source-to-Image and runs `backend/.s2i/bin/assemble`, which executes Gradle `bootJar`.
- Frontend build uses Source-to-Image and runs `frontend/.s2i/bin/assemble`, which executes `npm ci`, `npm run build`, and prunes dev dependencies.

## 3) Create Backend Secret

## 2) Create Backend Secret

1. Copy backend/02-secret.template.yaml to backend/02-secret.yaml.
2. Replace placeholder values.
3. Apply secret.

Commands:

- oc apply -f ocp/backend/02-secret.yaml

## 4) Build Images On OpenShift

Commands:

- oc start-build pulse-backend --follow
- oc start-build pulse-frontend --follow

Validation:

- oc get builds
- oc describe build pulse-backend-1
- oc describe build pulse-frontend-1
- oc get is

Expected:

- `pulse-backend:latest` and `pulse-frontend:latest` image stream tags exist.
- If the frontend backend URL changes later, rebuild `pulse-frontend` because `NEXT_PUBLIC_API_URL` is baked at build time.

## 5) Deploy Backend

Commands:

- oc apply -f ocp/backend/01-configmap.yaml
- oc apply -f ocp/backend/03-deployment.yaml
- oc apply -f ocp/backend/04-service.yaml
- oc apply -f ocp/backend/05-route.yaml
- oc rollout status deploy/pulse-backend

Validation:

- oc get pods -l app=pulse-backend
- oc logs deploy/pulse-backend --tail=100
- curl -k https://<backend-route>/actuator/health

Expected:

- Route returns 200 and status UP.

## 6) Deploy Frontend

Commands:

- oc apply -f ocp/frontend/01-configmap.yaml
- oc apply -f ocp/frontend/02-deployment.yaml
- oc apply -f ocp/frontend/03-service.yaml
- oc apply -f ocp/frontend/04-route.yaml
- oc rollout status deploy/pulse-frontend

Validation:

- oc get pods -l app=pulse-frontend
- oc logs deploy/pulse-frontend --tail=100
- curl -k https://<frontend-route>

Expected:

- Frontend loads and can reach backend APIs from browser.

## 7) Smoke Tests

1. Open frontend route in browser from non-dev machine/network.
2. Login or hit a known backend API call through frontend.
3. Confirm no CORS errors in browser console.
4. Confirm backend health endpoint remains UP.

## 8) Day-2 Hardening (next pass)

1. Add HPA for both deployments.
2. Add PodDisruptionBudget.
3. Add NetworkPolicy (frontend -> backend only, backend -> DB/Redis only).
4. Move secrets to External Secrets or Sealed Secrets.
5. Replace temporary MANAGEMENT_HEALTH_REDIS_ENABLED=false once Redis is provisioned.
