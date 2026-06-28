#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-firefox-web}"
REMOVE="${1:-}"

if docker ps --format '{{.Names}}' | awk -v n="${CONTAINER_NAME}" '$0 == n { found=1 } END { exit(found ? 0 : 1) }'; then
  echo "Stopping container '${CONTAINER_NAME}'..."
  docker stop "${CONTAINER_NAME}" >/dev/null
else
  echo "Container '${CONTAINER_NAME}' is not running."
fi

if [[ "${REMOVE}" == "--remove" ]]; then
  if docker ps -a --format '{{.Names}}' | awk -v n="${CONTAINER_NAME}" '$0 == n { found=1 } END { exit(found ? 0 : 1) }'; then
    echo "Removing container '${CONTAINER_NAME}'..."
    docker rm "${CONTAINER_NAME}" >/dev/null
  fi
fi

echo "Done."
