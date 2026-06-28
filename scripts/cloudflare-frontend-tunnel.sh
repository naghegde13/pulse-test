#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <tunnel-name> <frontend-hostname>"
  echo "Example: $0 pulse-frontend app.example.com"
  exit 1
fi

TUNNEL_NAME="$1"
FRONTEND_HOSTNAME="$(echo "$2" | tr '[:upper:]' '[:lower:]')"

if [[ "${FRONTEND_HOSTNAME}" != *.* ]]; then
  echo "Error: frontend hostname must be a fully-qualified domain name (e.g. app.example.com)."
  echo "Received: ${FRONTEND_HOSTNAME}"
  exit 1
fi

echo "1) Login to Cloudflare (opens browser if needed)"
if [[ -f "/Users/aameradam/.cloudflared/cert.pem" ]]; then
  echo "Existing Cloudflare cert found at /Users/aameradam/.cloudflared/cert.pem; skipping login."
else
  cloudflared tunnel login
fi

echo "2) Create tunnel: ${TUNNEL_NAME}"
if ! cloudflared tunnel list | awk -v name="${TUNNEL_NAME}" '$2 == name { found=1 } END { exit(found ? 0 : 1) }'; then
  cloudflared tunnel create "${TUNNEL_NAME}"
fi

TUNNEL_ID="$(cloudflared tunnel list | awk -v name="${TUNNEL_NAME}" '$2 == name { print $1; exit }')"

if [[ -z "${TUNNEL_ID}" ]]; then
  echo "Could not resolve tunnel id for ${TUNNEL_NAME}"
  exit 1
fi

echo "3) Map DNS hostname: ${FRONTEND_HOSTNAME}"
ROUTE_OUTPUT="$(cloudflared tunnel route dns "${TUNNEL_ID}" "${FRONTEND_HOSTNAME}" 2>&1 || true)"
echo "${ROUTE_OUTPUT}"

MAPPED_HOSTNAME="$(
  echo "${ROUTE_OUTPUT}" | awk '
    /Added CNAME/ {
      for (i = 1; i <= NF; i++) {
        if ($i == "CNAME" && (i + 1) <= NF) {
          print $(i + 1);
          exit
        }
      }
    }
  '
)"
if [[ -z "${MAPPED_HOSTNAME}" ]]; then
  MAPPED_HOSTNAME="${FRONTEND_HOSTNAME}"
fi

# Some Cloudflare setups append the zone when a full hostname is provided.
# Example: input pulse.aamer.net -> created pulse.aamer.net.aamer.net
DUPLICATE_SUFFIX=".${FRONTEND_HOSTNAME#*.}"
if [[ "${MAPPED_HOSTNAME}" == "${FRONTEND_HOSTNAME}${DUPLICATE_SUFFIX}" ]]; then
  SHORT_HOST="${FRONTEND_HOSTNAME%%.*}"
  echo "Detected duplicated zone suffix in mapped hostname (${MAPPED_HOSTNAME}). Retrying with short host label: ${SHORT_HOST}"
  ROUTE_OUTPUT="$(cloudflared tunnel route dns --overwrite-dns "${TUNNEL_ID}" "${SHORT_HOST}" 2>&1 || true)"
  echo "${ROUTE_OUTPUT}"
  RETRIED_MAPPED="$(
    echo "${ROUTE_OUTPUT}" | awk '
      /Added CNAME/ {
        for (i = 1; i <= NF; i++) {
          if ($i == "CNAME" && (i + 1) <= NF) {
            print $(i + 1);
            exit
          }
        }
      }
    '
  )"
  if [[ -n "${RETRIED_MAPPED}" ]]; then
    MAPPED_HOSTNAME="${RETRIED_MAPPED}"
  fi
fi

CONFIG_PATH="/Users/aameradam/projects/dev/PULSE/.cloudflared/config.frontend.yml"

cat > "${CONFIG_PATH}" <<EOF
tunnel: ${TUNNEL_ID}
credentials-file: /Users/aameradam/.cloudflared/${TUNNEL_ID}.json

ingress:
  - hostname: ${MAPPED_HOSTNAME}
    service: http://localhost:5800
  - service: http_status:404
EOF

echo "4) Starting tunnel with ${CONFIG_PATH}"
echo "Public URL: https://${MAPPED_HOSTNAME}"
cloudflared tunnel --config "${CONFIG_PATH}" run
