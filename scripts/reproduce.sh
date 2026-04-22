#!/usr/bin/env bash
# End-to-end reproduce script. To be filled in D2~D7 as each feature lands.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> [D1] Build bootJar"
./gradlew bootJar

echo "==> [D1] Start docker compose stack"
docker compose up -d --build

echo "==> [D1] Wait for health"
for port in 7081 7082; do
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health" >/dev/null; then
      echo "app@${port} is UP"
      break
    fi
    sleep 2
  done
done

echo "==> [D2+] Create session, append events, restore timeline (TBD)"
# curl -X POST http://localhost:7081/sessions ...

echo "All reproduce steps finished."
