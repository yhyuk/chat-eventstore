#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE_URL:-http://localhost:7081}"
OUT="${OUT_FILE:-openapi/openapi.yaml}"

# Ensure output dir exists
mkdir -p "$(dirname "$OUT")"

echo "Checking springdoc endpoint at $BASE/v3/api-docs.yaml ..."
if ! curl -sf -o /dev/null "$BASE/v3/api-docs.yaml"; then
  echo "ERROR: $BASE/v3/api-docs.yaml not reachable" >&2
  echo "Hint: start the app with 'docker compose up -d app1' or './gradlew bootRun'" >&2
  exit 1
fi

echo "Downloading OpenAPI spec ..."
curl -sf "$BASE/v3/api-docs.yaml" -o "$OUT"

# Summary
SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo
echo "Saved OpenAPI spec:"
echo "  File: $OUT"
echo "  Size: $SIZE bytes"

# If yq installed, print path count
if command -v yq >/dev/null 2>&1; then
  PATHS=$(yq '.paths | length' "$OUT" 2>/dev/null || echo "?")
  TAGS=$(yq '.tags | length' "$OUT" 2>/dev/null || echo "?")
  echo "  Paths: $PATHS"
  echo "  Tags:  $TAGS"
else
  echo "  (install 'yq' to see path/tag counts)"
fi

echo "OK"
