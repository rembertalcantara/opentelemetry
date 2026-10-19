#!/usr/bin/env bash
# Sends a mix of successful and failing orders so every signal has something to show.
# Usage: scripts/generate-traffic.sh [count] [base-url]
set -euo pipefail

COUNT="${1:-50}"
BASE_URL="${2:-http://localhost:8080}"
SKUS=(SKU-BOOK SKU-MUG SKU-PHONE SKU-LAPTOP SKU-UNKNOWN)
CUSTOMERS=(alice bob gold-carol vip-dave fraudster-eve)

for i in $(seq 1 "$COUNT"); do
  sku="${SKUS[$((RANDOM % ${#SKUS[@]}))]}"
  customer="${CUSTOMERS[$((RANDOM % ${#CUSTOMERS[@]}))]}"
  quantity=$(( (RANDOM % 4) + 1 ))
  status=$(curl -s -o /dev/null -w '%{http_code}' -D /tmp/otel-demo-headers \
    -H 'Content-Type: application/json' \
    -d "{\"sku\":\"$sku\",\"quantity\":$quantity,\"customerId\":\"$customer\"}" \
    "$BASE_URL/orders")
  trace=$(grep -i '^x-trace-id' /tmp/otel-demo-headers | tr -d '\r' | awk '{print $2}')
  printf '%3d  %s  %-11s x%d  %-14s trace=%s\n' "$i" "$status" "$sku" "$quantity" "$customer" "$trace"
  sleep 0.2
done
