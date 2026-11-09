#!/usr/bin/env bash
# Creates (or updates) the AskOnce Agent Builder tools and agent in Kibana.
# Usage: set -a; source ~/elastic-start-local/.env; set +a; elastic/setup-agent-builder.sh [kibana-url]
set -euo pipefail
KIBANA="${1:-http://localhost:5601}"
AUTH="elastic:${ES_LOCAL_PASSWORD:?source ~/elastic-start-local/.env first}"
DIR="$(dirname "$0")/agent-builder"

upsert() { # $1 = api path (tools|agents), $2 = json file
  id=$(jq -r .id "$2")
  if curl -sf -u "$AUTH" -H 'kbn-xsrf: true' "$KIBANA/api/agent_builder/$1/$id" >/dev/null; then
    jq 'del(.id, .type)' "$2" | curl -sf -u "$AUTH" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
      -X PUT "$KIBANA/api/agent_builder/$1/$id" -d @- >/dev/null && echo "updated $1/$id"
  else
    curl -sf -u "$AUTH" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
      -X POST "$KIBANA/api/agent_builder/$1" -d @"$2" >/dev/null && echo "created $1/$id"
  fi
}

for f in "$DIR"/askonce.*.json; do upsert tools "$f"; done
upsert agents "$DIR/agent.askonce-speaker-assistant.json"
