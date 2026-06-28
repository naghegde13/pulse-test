#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-firefox-web}"
HOST_PORT="${HOST_PORT:-5800}"
CONTAINER_PORT="5800"
CONFIG_DIR="${CONFIG_DIR:-$HOME/docker/firefox}"
IMAGE="${IMAGE:-jlesage/firefox}"

mkdir -p "${CONFIG_DIR}"

if docker ps --format '{{.Names}}' | awk -v n="${CONTAINER_NAME}" '$0 == n { found=1 } END { exit(found ? 0 : 1) }'; then
  echo "Container '${CONTAINER_NAME}' is already running."
  echo "Open: http://localhost:${HOST_PORT}"
  exit 0
fi

if docker ps -a --format '{{.Names}}' | awk -v n="${CONTAINER_NAME}" '$0 == n { found=1 } END { exit(found ? 0 : 1) }'; then
  echo "Starting existing container '${CONTAINER_NAME}'..."
  docker start "${CONTAINER_NAME}" >/dev/null
else
  echo "Creating and starting container '${CONTAINER_NAME}'..."
  docker run -d \
    --name "${CONTAINER_NAME}" \
    -p "${HOST_PORT}:${CONTAINER_PORT}" \
    -v "${CONFIG_DIR}:/config:rw" \
    --shm-size=2g \
    "${IMAGE}" >/dev/null
fi

echo "Firefox web container is running."
echo "Open: http://localhost:${HOST_PORT}"
