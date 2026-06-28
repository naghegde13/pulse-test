# PULSE OCP Starter Kit

This folder contains a practical OpenShift deployment starter for:

- Backend (Spring Boot)
- Frontend (Next.js standalone)

Goal: build both images on OpenShift from this repository using Source-to-Image, deploy them, get both apps reachable for your org, then harden in follow-up.

## Folder Layout

- backend: backend build and runtime manifests
- frontend: frontend build and runtime manifests
- runbooks: step-by-step deployment guide
- ENV_CHECKLIST.md: values you must fill before deploy

## Choose Your Build Path

### Path A: Local-only source, no Git remote yet

Use this now if the code only exists on your machine.

- BuildConfigs:
	- `backend/01-buildconfig-binary.yaml`
	- `frontend/01-buildconfig-binary.yaml`
- Runbook:
	- `runbooks/binary-build.md`

This path uploads source from your local machine to OpenShift with `oc start-build --from-dir`.

### Path B: Git-backed OpenShift builds

Use this later when the repository is pushed to a remote that OpenShift can access.

- BuildConfigs:
	- `backend/01-buildconfig.yaml`
	- `frontend/01-buildconfig.yaml`
- Runbooks:
	- `runbooks/deploy.md`
	- `runbooks/git-init-and-push.md`

## Important Notes

- This starter uses native OpenShift BuildConfig + ImageStream resources so the cluster can build images from this repository without a Docker-style local image build step.
- Secrets in template files are placeholders only. Replace before applying.
- Initial backend config keeps Redis health disabled to avoid false DOWN while Redis is not provisioned.
- The frontend API base URL is a build-time value in this repo. Rebuild the frontend image whenever the backend route changes.
- The primary OCP path here is Source-to-Image with custom `.s2i/bin/assemble` and `.s2i/bin/run` scripts in each app directory.

## First Steps

1. Fill values in ENV_CHECKLIST.md.
2. Choose binary-build or Git-build path.
3. Update route hosts, backend/frontend build settings, and builder image settings.
4. Create backend secret from template.
5. Follow the matching runbook.
