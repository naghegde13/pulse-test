#!/usr/bin/env bash
set -euo pipefail

gcloud auth activate-service-account --key-file=/Users/aameradam/projects/dev/capgkey.json
gcloud config set project gcp-mat --quiet
gcloud auth configure-docker us-central1-docker.pkg.dev --quiet

echo "Authenticated to DEV2 (gcp-mat)"
