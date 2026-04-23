#!/usr/bin/env bash
# E2E Reproduction Script: Full Event-Sourcing Pipeline
#
# Usage:
#   ./scripts/reproduce.sh              # Builds JAR, starts docker compose, runs E2E scenario
#   BASE_URL=http://localhost:8080 ./scripts/reproduce.sh  # Custom base URL
#
# Prerequisites:
#   - curl, jq installed
#   - docker & docker compose available
#   - Gradle build works
#
# This script demonstrates:
#   - Session creation and join
#   - Ordered event append
#   - Duplicate detection (idempotency)
#   - Out-of-order resilience
#   - Projection rebuild
#   - Timeline restore
#   - DLQ monitoring

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

BASE="${BASE_URL:-http://localhost:7081}"
USER1="alice"
USER2="bob"

# macOS compatible ISO-8601 timestamp with milliseconds
now_iso() {
  python3 -c "from datetime import datetime, timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.') + f\"{datetime.now(timezone.utc).microsecond // 1000:03d}Z\")"
}

# Check dependencies
if ! command -v curl &> /dev/null; then
  echo "ERROR: curl not found. Please install curl."
  exit 1
fi
if ! command -v jq &> /dev/null; then
  echo "ERROR: jq not found. Please install jq."
  exit 1
fi

echo "==> [Bootstrap] Build bootJar"
./gradlew bootJar

echo "==> [Bootstrap] Start docker compose stack"
docker compose up -d --build

echo "==> [Bootstrap] Wait for health checks (60s timeout per instance)"
for port in 7081 7082; do
  for i in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health" >/dev/null 2>&1; then
      echo "✓ app@${port} is UP"
      break
    fi
    if [ $i -eq 60 ]; then
      echo "✗ app@${port} failed to start"
      exit 1
    fi
    sleep 2
  done
done

echo

# ============================================================================
# Step 1: Create session
# ============================================================================
echo "=== Step 1: Create session ==="
SESSION_RESPONSE=$(curl -sf -X POST "$BASE/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"createdBy\": \"$USER1\", \"metadata\": {\"title\": \"E2E Test\"}}")
SESSION_ID=$(echo "$SESSION_RESPONSE" | jq -r '.sessionId')
echo "✓ Session created: ID=$SESSION_ID"
echo "  Response: $(echo "$SESSION_RESPONSE" | jq '.')"
echo

# ============================================================================
# Step 2: Join participants
# ============================================================================
echo "=== Step 2: Join (alice + bob) ==="
curl -sf -X POST "$BASE/sessions/$SESSION_ID/join" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\": \"$USER1\"}" | jq '.userId' >/dev/null
echo "✓ $USER1 joined"

curl -sf -X POST "$BASE/sessions/$SESSION_ID/join" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\": \"$USER2\"}" | jq '.userId' >/dev/null
echo "✓ $USER2 joined"
echo

# ============================================================================
# Step 3: Send 5 ordered events in sequence
# ============================================================================
echo "=== Step 3: Send 5 ordered events ==="
for i in 1 2 3 4 5; do
  curl -sf -X POST "$BASE/sessions/$SESSION_ID/events" \
    -H 'Content-Type: application/json' \
    -d "{
      \"clientEventId\": \"evt-$i\",
      \"userId\": \"$USER1\",
      \"sequence\": $i,
      \"type\": \"MESSAGE\",
      \"payload\": {\"text\": \"message $i\"},
      \"clientTimestamp\": \"$(now_iso)\"
    }" > /dev/null
  echo "✓ Event $i appended"
done
echo

# ============================================================================
# Step 4: Send duplicate (same clientEventId, different sequence)
# ============================================================================
echo "=== Step 4: Send duplicate (clientEventId='evt-1') ==="
DUP_RESPONSE=$(curl -s -X POST "$BASE/sessions/$SESSION_ID/events" \
  -H 'Content-Type: application/json' \
  -d "{
    \"clientEventId\": \"evt-1\",
    \"userId\": \"$USER1\",
    \"sequence\": 99,
    \"type\": \"MESSAGE\",
    \"payload\": {\"text\": \"duplicate!\"},
    \"clientTimestamp\": \"$(now_iso)\"
  }")
DUP_STATUS=$(echo "$DUP_RESPONSE" | jq -r '.status')
echo "✓ Duplicate response status: $DUP_STATUS (expected: DUPLICATE_IGNORED or ACCEPTED)"
echo "  Response: $(echo "$DUP_RESPONSE" | jq '.')"
echo

# ============================================================================
# Step 5: Send out-of-order events (sequence 8, 6, 7)
# ============================================================================
echo "=== Step 5: Send out-of-order events (sequences 8, 6, 7) ==="
for seq in 8 6 7; do
  curl -sf -X POST "$BASE/sessions/$SESSION_ID/events" \
    -H 'Content-Type: application/json' \
    -d "{
      \"clientEventId\": \"ooo-$seq\",
      \"userId\": \"$USER2\",
      \"sequence\": $seq,
      \"type\": \"MESSAGE\",
      \"payload\": {\"text\": \"out-of-order seq=$seq\"},
      \"clientTimestamp\": \"$(now_iso)\"
    }" > /dev/null
  echo "✓ Out-of-order event (sequence=$seq) appended"
done
echo

# ============================================================================
# Step 6: Wait for projection worker to process
# ============================================================================
echo "=== Step 6: Wait 3s for projection worker ==="
sleep 3
echo "✓ Projection processing window elapsed"
echo

# ============================================================================
# Step 7: Restore timeline at current time
# ============================================================================
echo "=== Step 7: Restore timeline at current time ==="
AT=$(now_iso)
TIMELINE=$(curl -sf "$BASE/sessions/$SESSION_ID/timeline?at=$AT")
echo "✓ Timeline restored"
echo "$TIMELINE" | jq '{
  sessionId,
  at,
  participantCount: (.participants | length),
  messageCount: (.messages | length),
  restoredFromSnapshot,
  replayedEventCount
}'
echo

# ============================================================================
# Step 8: Rebuild projection (admin operation)
# ============================================================================
echo "=== Step 8: Rebuild projection ==="
REBUILD=$(curl -sf -X POST "$BASE/admin/projections/rebuild?sessionId=$SESSION_ID")
echo "✓ Projection rebuild triggered"
echo "$REBUILD" | jq '.'
echo

# ============================================================================
# Step 9: Session list with filters
# ============================================================================
echo "=== Step 9: Session list query ==="
SESSIONS=$(curl -sf "$BASE/sessions?status=ACTIVE&limit=10")
ITEM_COUNT=$(echo "$SESSIONS" | jq '.items | length')
TOTAL=$(echo "$SESSIONS" | jq '.total')
echo "✓ Sessions queried: total=$TOTAL, returned=$ITEM_COUNT"
echo "$SESSIONS" | jq '{total, itemCount: (.items | length)}'
echo

# ============================================================================
# Step 10: DLQ status
# ============================================================================
echo "=== Step 10: DLQ status ==="
DLQ=$(curl -sf "$BASE/admin/dlq")
DLQ_COUNT=$(echo "$DLQ" | jq 'length')
echo "✓ DLQ items: $DLQ_COUNT"
if [ "$DLQ_COUNT" -gt 0 ]; then
  echo "$DLQ" | jq '.[0:2]'  # Show first 2 items
else
  echo "  (empty)"
fi
echo

# ============================================================================
# Summary
# ============================================================================
echo "========================================"
echo "=== reproduce.sh COMPLETE ==="
echo "========================================"
echo "Session ID: $SESSION_ID"
echo "Participants: 2 ($USER1, $USER2)"
echo "Events appended: 8 (5 ordered + 1 duplicate + 2 out-of-order)"
echo "Timeline restored: YES"
echo "Projection rebuild: YES"
echo "DLQ monitored: YES"
echo
echo "Next: Check docker logs, Prometheus, Grafana, or Zipkin for traces."
echo "Stop: docker compose down"
