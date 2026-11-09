#!/usr/bin/env bash
# Submits every question in a file to a board and prints whether it was merged.
# Usage: demo/submit-questions.sh [board] [questions-file] [base-url] [topic]
set -euo pipefail

BOARD="${1:-demo-k8s-talk}"
FILE="${2:-$(dirname "$0")/questions.txt}"
BASE_URL="${3:-http://localhost:8080}"
TOPIC="${4:-Kubernetes in production: lessons learned from running clusters at scale}"

# The board topic tells the engine which words every question will share, so they don't count as similarity.
curl -s -X PUT "$BASE_URL/api/boards/$BOARD" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg topic "$TOPIC" '{topic: $topic}')" | jq -r '"Board \(.board): \(.topic)\n"'

grep -vE '^\s*(#|$)' "$FILE" | while IFS= read -r question; do
  body=$(jq -n --arg text "$question" '{text: $text}')
  curl -s -X POST "$BASE_URL/api/boards/$BOARD/questions" \
    -H 'Content-Type: application/json' -d "$body" |
    jq -r --arg q "$question" \
      'if .merged then "MERGED  (\(.similarity * 100 | floor)%)  \($q)\n          -> \(.group.text)  [asks: \(.group.asks)]"
       else "NEW            \($q)" end'
done

echo
echo "Ranked board '$BOARD':"
curl -s "$BASE_URL/api/boards/$BOARD/questions" |
  jq -r '.[] | "  \(.popularity)  \(.text)  (\(.asks) asks, \(.votes) votes)"'
