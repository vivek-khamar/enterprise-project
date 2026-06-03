#!/usr/bin/env bash
# Seed the running application with N test users.
# Usage: ./seed-data.sh [count] [base_url]
#   count    — number of users to create (default 5000)
#   base_url — API base (default http://localhost:8080/enterprise/api/v1)

set -euo pipefail

COUNT="${1:-5000}"
BASE="${2:-http://localhost:8080/enterprise/api/v1}"
BATCH=50      # parallel curl calls per batch

echo "Seeding ${COUNT} users to ${BASE}/users …"

created=0
failed=0

for start in $(seq 1 $BATCH $COUNT); do
  end=$(( start + BATCH - 1 ))
  [ $end -gt $COUNT ] && end=$COUNT

  # Fire BATCH requests in parallel
  pids=()
  for i in $(seq $start $end); do
    curl -s -o /dev/null -w "%{http_code}" \
         -X POST "${BASE}/users" \
         -H "Content-Type: application/json" \
         -d "{\"username\":\"user${i}\",\"email\":\"user${i}@loadtest.example.com\"}" \
    | grep -q "201" && (( created++ )) || (( failed++ )) &
    pids+=($!)
  done

  # Wait for the batch
  for pid in "${pids[@]}"; do
    wait "$pid" 2>/dev/null || true
  done

  pct=$(( (end * 100) / COUNT ))
  printf "\r  Progress: %d/%d (%d%%)  " "$end" "$COUNT" "$pct"
done

echo ""
echo "Done — created: ${created}, failed: ${failed}"
