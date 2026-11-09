#!/usr/bin/env bash
# Simulates the end of a talk: many attendees submit questions at the same moment.
# Prints latency percentiles and how many groups the board ends up with.
# Usage: demo/load-test.sh [attendees] [concurrency] [board] [base-url]
set -euo pipefail

ATTENDEES="${1:-60}"
CONCURRENCY="${2:-30}"
BOARD="${3:-ebpf-talk}"
BASE_URL="${4:-http://localhost:8080}"
QUESTIONS="$(dirname "$0")/questions.txt"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# Each attendee picks a random question from the demo set.
grep -vE '^\s*(#|$)' "$QUESTIONS" > "$workdir/pool.txt"
for _ in $(seq 1 "$ATTENDEES"); do
  sed -n "$(( (RANDOM % $(wc -l < "$workdir/pool.txt")) + 1 ))p" "$workdir/pool.txt"
done > "$workdir/requests.txt"

echo "Talk just ended: $ATTENDEES attendees asking, $CONCURRENCY at a time, board '$BOARD'..."
start=$(date +%s)
tr '\n' '\0' < "$workdir/requests.txt" |
  xargs -0 -P "$CONCURRENCY" -I{} sh -c '
    body=$(jq -n --arg text "$1" "{text: \$text}")
    curl -s -o /dev/null -w "%{http_code} %{time_total}\n" -X POST "$2/api/boards/$3/questions" \
      -H "Content-Type: application/json" -d "$body"' _ {} "$BASE_URL" "$BOARD" > "$workdir/results.txt"
elapsed=$(( $(date +%s) - start ))

errors=$(awk '$1 >= 400 || $1 == 0' "$workdir/results.txt" | wc -l | tr -d ' ')
sort -k2 -n "$workdir/results.txt" | awk -v total="$ATTENDEES" -v elapsed="$elapsed" -v errors="$errors" '
  { t[NR] = $2 }
  END {
    printf "Done in %ds, errors: %d\n", elapsed, errors
    printf "Latency  p50 %.2fs   p95 %.2fs   max %.2fs\n", t[int(NR * 0.5)], t[int(NR * 0.95)], t[NR]
  }'

curl -s "$BASE_URL/api/boards/$BOARD/questions?limit=100" |
  jq -r '"Board now has \(length) groups from \(map(.asks) | add) questions"'
