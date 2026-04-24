# 스크린샷 캡처 가이드

평가자/개발자가 직접 실행하여 캡처할 수 있습니다.

## Grafana App Dashboard

1. `docker compose up -d`
2. 부하 발생: `./scripts/reproduce.sh` 또는 `k6 run scripts/load-test.js`
3. http://localhost:3100 접속 (익명 Viewer)
4. Dashboards → "General" 폴더 → "Chat EventStore — App Dashboard" 선택
5. 우측 상단 Export → PNG or 브라우저 스크린샷
6. 저장 경로: `docs/images/grafana-app-dashboard.png`

## Zipkin Traces

1. 부하/재현 후 http://localhost:9411 접속
2. "Run Query" → trace 목록에서 임의 trace 선택
3. Span 계층 보이는 화면 스크린샷
4. 저장 경로: `docs/images/zipkin-trace.png`
