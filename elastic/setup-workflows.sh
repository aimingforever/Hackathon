#!/usr/bin/env bash
# Creates the askonce-board-snapshots index and the "AskOnce board snapshot" Elastic Workflow.
# Usage: set -a; source ~/elastic-start-local/.env; set +a; elastic/setup-workflows.sh [es-url] [kibana-url]
set -euo pipefail
ES="${1:-http://localhost:9200}"
KIBANA="${2:-http://localhost:5601}"
AUTH="elastic:${ES_LOCAL_PASSWORD:?source ~/elastic-start-local/.env first}"
DIR="$(dirname "$0")/workflows"

if ! curl -sf -u "$AUTH" -o /dev/null "$ES/askonce-board-snapshots"; then
  curl -sf -u "$AUTH" -X PUT "$ES/askonce-board-snapshots" -H 'Content-Type: application/json' \
    -d @"$DIR/askonce-board-snapshots.mapping.json" >/dev/null && echo "created index askonce-board-snapshots"
fi

jq -n --rawfile yaml "$DIR/askonce-board-snapshot.yaml" '{workflows: [{yaml: $yaml}]}' |
  curl -sf -u "$AUTH" -X POST "$KIBANA/api/workflows" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' -d @- |
  jq -r '.created[]? | "created workflow \(.id)"'
