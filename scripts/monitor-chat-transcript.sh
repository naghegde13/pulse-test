#!/usr/bin/env bash
set -euo pipefail

SESSION_ID="${1:-}"
INTERVAL="${PULSE_CHAT_MONITOR_INTERVAL:-1}"
WINDOW_HOURS="${PULSE_CHAT_MONITOR_WINDOW_HOURS:-6}"
SEEN_FILE="${PULSE_CHAT_MONITOR_SEEN_FILE:-/tmp/pulse-chat-monitor-seen.txt}"

touch "$SEEN_FILE"

if [[ -n "$SESSION_ID" && ! "$SESSION_ID" =~ ^[0-9A-Z]{26}$ ]]; then
  echo "Session id must be a 26-character ULID, got: $SESSION_ID" >&2
  exit 2
fi

echo "PULSE chat transcript monitor"
if [[ -n "$SESSION_ID" ]]; then
  echo "Filtering session: $SESSION_ID"
else
  echo "Filtering: all sessions updated in the last ${WINDOW_HOURS}h"
fi
echo "Poll interval: ${INTERVAL}s"
echo

query_all="
SELECT
  id,
  to_char(created_at AT TIME ZONE 'America/New_York', 'HH24:MI:SS.MS'),
  role,
  session_id,
  coalesce(plan_id, ''),
  left(regexp_replace(content, E'[\\n\\r\\t]+', ' ', 'g'), 700),
  CASE WHEN tool_calls IS NULL THEN '' ELSE 'tool_calls' END,
  CASE WHEN tool_results IS NULL THEN '' ELSE 'tool_results' END
FROM chat_messages
WHERE created_at >= now() - (:'window_hours' || ' hours')::interval
ORDER BY created_at ASC;
"

query_session="
SELECT
  id,
  to_char(created_at AT TIME ZONE 'America/New_York', 'HH24:MI:SS.MS'),
  role,
  session_id,
  coalesce(plan_id, ''),
  left(regexp_replace(content, E'[\\n\\r\\t]+', ' ', 'g'), 700),
  CASE WHEN tool_calls IS NULL THEN '' ELSE 'tool_calls' END,
  CASE WHEN tool_results IS NULL THEN '' ELSE 'tool_results' END
FROM chat_messages
WHERE session_id = :'session_id'
ORDER BY created_at ASC;
"

while true; do
  if [[ -n "$SESSION_ID" ]]; then
    rows="$(docker compose exec -T postgres psql -U pulse -d pulse \
      -v session_id="$SESSION_ID" \
      -A -F $'\x1f' -q -t -c "$query_session" 2>/dev/null || true)"
  else
    rows="$(docker compose exec -T postgres psql -U pulse -d pulse \
      -v window_hours="$WINDOW_HOURS" \
      -A -F $'\x1f' -q -t -c "$query_all" 2>/dev/null || true)"
  fi

  while IFS=$'\x1f' read -r id ts role session_id plan_id content tool_calls tool_results; do
    [[ -z "${id:-}" ]] && continue
    if grep -qx "$id" "$SEEN_FILE"; then
      continue
    fi
    echo "$id" >> "$SEEN_FILE"
    extras=""
    [[ -n "$plan_id" ]] && extras="${extras} plan=${plan_id}"
    [[ -n "$tool_calls" ]] && extras="${extras} ${tool_calls}"
    [[ -n "$tool_results" ]] && extras="${extras} ${tool_results}"
    printf '[%s] %-9s session=%s%s\n%s\n\n' "$ts" "$role" "$session_id" "$extras" "$content"
  done <<< "$rows"

  sleep "$INTERVAL"
done
