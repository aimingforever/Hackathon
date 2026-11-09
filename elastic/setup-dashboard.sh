#!/usr/bin/env bash
# Creates the "AskOnce: live Q&A" Kibana dashboard (ES|QL panels) through the Dashboards API.
# Usage: set -a; source ~/elastic-start-local/.env; set +a; elastic/setup-dashboard.sh [kibana-url]
set -euo pipefail
KIBANA="${1:-http://localhost:5601}"
AUTH="elastic:${ES_LOCAL_PASSWORD:?source ~/elastic-start-local/.env first}"
curl -sf -u "$AUTH" -X POST "$KIBANA/api/dashboards" -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
  -H 'elastic-api-version: 2023-10-31' -d @"$(dirname "$0")/dashboards/askonce-dashboard.json" |
  jq -r '"created dashboard: '"$KIBANA"'/app/dashboards#/view/\(.id)"'
