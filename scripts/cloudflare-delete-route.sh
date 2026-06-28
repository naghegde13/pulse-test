#!/usr/bin/env bash
set -euo pipefail

# Removes Cloudflare tunnel DNS hostname mapping (via Cloudflare API) and
# optionally deletes the tunnel itself.
#
# Usage:
#   ./scripts/cloudflare-delete-route.sh <tunnel-name> <hostname>
#   ./scripts/cloudflare-delete-route.sh <tunnel-name> <hostname> --delete-tunnel
#
# Example:
#   ./scripts/cloudflare-delete-route.sh PULSE pulse.aamer.net
#   ./scripts/cloudflare-delete-route.sh PULSE pulse.aamer.net --delete-tunnel
#
# Optional env vars to auto-delete the DNS record:
#   CF_API_TOKEN=<cloudflare api token with Zone.DNS:Edit>
#   CF_ZONE_ID=<cloudflare zone id for your domain>

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <tunnel-name> <hostname> [--delete-tunnel]"
  exit 1
fi

TUNNEL_NAME="$1"
HOSTNAME="$(echo "$2" | tr '[:upper:]' '[:lower:]')"
DELETE_TUNNEL="${3:-}"

if [[ "${HOSTNAME}" != *.* ]]; then
  echo "Error: hostname must be a fully-qualified domain name (e.g. app.example.com)."
  echo "Received: ${HOSTNAME}"
  exit 1
fi

if [[ -n "${DELETE_TUNNEL}" && "${DELETE_TUNNEL}" != "--delete-tunnel" ]]; then
  echo "Error: unsupported option '${DELETE_TUNNEL}'."
  echo "Only '--delete-tunnel' is supported."
  exit 1
fi

TUNNEL_ID="$(cloudflared tunnel list | awk -v name="${TUNNEL_NAME}" '$2 == name { print $1; exit }')"
if [[ -z "${TUNNEL_ID}" ]]; then
  echo "Tunnel '${TUNNEL_NAME}' not found."
  exit 1
fi

echo "Removing DNS route for ${HOSTNAME} from tunnel ${TUNNEL_NAME} (${TUNNEL_ID})..."
if [[ -n "${CF_API_TOKEN:-}" && -n "${CF_ZONE_ID:-}" ]]; then
  LIST_JSON="$(curl -sS -X GET \
    "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records?type=CNAME&name=${HOSTNAME}" \
    -H "Authorization: Bearer ${CF_API_TOKEN}" \
    -H "Content-Type: application/json")"

  RECORD_ID="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("result") or [{}])[0].get("id",""))' <<< "${LIST_JSON}")"

  if [[ -z "${RECORD_ID}" ]]; then
    echo "No CNAME record found for ${HOSTNAME} in zone ${CF_ZONE_ID}."
  else
    curl -sS -X DELETE \
      "https://api.cloudflare.com/client/v4/zones/${CF_ZONE_ID}/dns_records/${RECORD_ID}" \
      -H "Authorization: Bearer ${CF_API_TOKEN}" \
      -H "Content-Type: application/json" >/dev/null
    echo "DNS route removed: ${HOSTNAME}"
  fi
else
  echo "Skipping DNS deletion: CF_API_TOKEN and CF_ZONE_ID not set."
  echo "Delete this CNAME manually in Cloudflare DNS: ${HOSTNAME}"
fi

if [[ "${DELETE_TUNNEL}" == "--delete-tunnel" ]]; then
  echo "Deleting tunnel ${TUNNEL_NAME} (${TUNNEL_ID})..."
  cloudflared tunnel delete "${TUNNEL_ID}"
  echo "Tunnel deleted."
fi
