# Optional Git Init And Push Guide

Use this only if you decide to move from local-only source to Git-backed OpenShift builds.

## 1) Initialize Git Locally

Run from the `pulse` folder:

```powershell
git init
git branch -M main
git add .
git commit -m "Initial PULSE import"
```

## 2) Create Remote Repository

Create an empty remote repository in your approved Git hosting platform.

You will need:

- Remote HTTPS or SSH URL
- Credentials or SSH key access

## 3) Add Remote And Push

Example:

```powershell
git remote add origin <remote-url>
git push -u origin main
```

## 4) Update OpenShift Git BuildConfigs

After push succeeds, use:

- `ocp/backend/01-buildconfig.yaml`
- `ocp/frontend/01-buildconfig.yaml`

instead of the binary build configs.
