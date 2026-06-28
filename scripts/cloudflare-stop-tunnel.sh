#!/usr/bin/env bash
set -euo pipefail

# Stops running cloudflared tunnel processes.
# Usage:
#   ./scripts/cloudflare-stop-tunnel.sh
#   ./scripts/cloudflare-stop-tunnel.sh <tunnel-name>

TUNNEL_NAME="${1:-}"

if [[ -n "${TUNNEL_NAME}" ]]; then
  PATTERN="cloudflared.*tunnel.*run.*${TUNNEL_NAME}|cloudflared.*--config.*${TUNNEL_NAME}"
  TARGET_DESC="tunnel '${TUNNEL_NAME}'"
else
  PATTERN="cloudflared.*tunnel.*run|cloudflared.*--config.*/.cloudflared/config\\.frontend\\.yml.*run"
  TARGET_DESC="all running cloudflared tunnel processes"
fi

PIDS="$(pgrep -f "${PATTERN}" || true)"

if [[ -z "${PIDS}" ]]; then
  echo "No matching process found for ${TARGET_DESC}."
  exit 0
fi

echo "Stopping ${TARGET_DESC}..."
echo "${PIDS}" | xargs kill -TERM
sleep 1

REMAINING="$(echo "${PIDS}" | xargs -I{} sh -c 'kill -0 "{}" 2>/dev/null && echo "{}"' || true)"
if [[ -n "${REMAINING}" ]]; then
  echo "Some processes still running; forcing stop..."
  echo "${REMAINING}" | xargs kill -KILL
fi

echo "Tunnel process(es) stopped."
