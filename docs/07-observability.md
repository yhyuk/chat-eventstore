# 07. 관측 가능성 (Metrics + Logs + Traces)

## 1. 스택 구성

| 구성 요소 | 도구 | 용도 |
|---|---|---|
| 메트릭 수집 | Micrometer + Prometheus | 시스템/애플리케이션 메트릭 |
| 메트릭 시각화 | Grafana | 대시보드 |
| 로그 수집 | Logback JSON encoder | 구조화 로그 (stdout) |
| 로그 뷰어 | Docker logs / 터미널 | 경량 유지 (Loki 미도입) |
| 추적 수집 | Micrometer Tracing + OpenTelemetry SDK | 분산 추적 |
| 추적 백엔드 | Zipkin | 경량 UI |

## 2. 메트릭

### 2.1 기본 (Spring Boot Actuator 자동)
- JVM: heap, gc, threads
- HTTP: request count, duration, status
- DB: HikariCP active/idle connections
- Tomcat: thread pool, sessions

### 2.2 커스텀 메트릭 (`ChatMetrics` 실제 구현 6종)

| 메트릭 | 타입 | 태그 | 목적 |
|---|---|---|---|
| `chat.events.received` | Counter | `type`, `result` | 수집 이벤트 수 (`result=ACCEPTED/DUPLICATE_IGNORED/...`) |
| `chat.events.duplicates` | Counter | - | 중복 차단 건수 |
| `chat.outbox.pending.size` | Gauge | - | 현재 PENDING 이벤트 수 (`countByProjectionStatus`) |
| `chat.outbox.lag.seconds` | Gauge | - | 가장 오래된 PENDING 이벤트의 `server_received_at`과 now 차이(초) |
| `chat.projection.dead_letter` | Counter | `reason` | DLQ 이관 누계 (예외 클래스 단위) |
| `chat.websocket.sessions.active` | Gauge | - | 현재 활성 WebSocket 세션 수 (인스턴스별) |

**미구현 (설계 단계 거론, 추후 도입 후보):**
- `chat.events.out_of_order` — 현재는 `InvalidSequenceException`을 ERROR 프레임으로만 응답
- `chat.projection.apply.duration` (Timer) — 평균 적용 시간 분포
- `chat.restore.duration.seconds` (Timer) — 복원 API 성능
- `chat.redis.pubsub.published.total` — Pub/Sub 발행 카운터
- `chat.presence.online.count` — 현재 online 사용자 수
- `chat.snapshot.created.total` — 스냅샷 생성 누계

### 2.3 Prometheus 스크래핑
```yaml
# observability/prometheus/prometheus.yml
scrape_configs:
  - job_name: 'chat-eventstore'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    static_configs:
      - targets: ['app1:8080', 'app2:8080']
  - job_name: 'mysql'
    static_configs:
      - targets: ['mysql-exporter:9104']
  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

## 3. Grafana 대시보드

### 3.1 App Dashboard (현재 제공)

`observability/grafana/dashboards/app.json` 단일 대시보드. 위 §2.2 메트릭 6종을 기준으로 패널을 구성한다.

권장 패널 구성:
1. **이벤트 처리율**: `sum by (type, result) (rate(chat_events_received_total[1m]))`
2. **중복 차단**: `rate(chat_events_duplicates_total[1m])`
3. **아웃박스 PENDING 큐**: `chat_outbox_pending_size`
4. **아웃박스 lag**: `chat_outbox_lag_seconds`
5. **DLQ 누계**: `sum by (reason) (rate(chat_projection_dead_letter_total[5m]))`
6. **WebSocket 세션**: `sum(chat_websocket_sessions_active)` (인스턴스 합)
7. **JVM/HTTP 기본 패널**: Spring Boot Actuator 자동 메트릭

> 미구현 메트릭(`out_of_order`, `restore.duration`, `presence.online.count` 등)을 참조하는 패널은 빈 그래프로 표시되므로 추가하지 않는다. 필요 시 `ChatMetrics`에 메트릭을 추가한 뒤 패널을 함께 등록.

### 3.2 MySQL / Redis 대시보드 (미제공)

본 구현은 App 대시보드 1종만 제공한다. MySQL / Redis 메트릭은 mysql-exporter / redis-exporter로 Prometheus에 수집되지만, 별도 Grafana 대시보드 JSON은 제공하지 않는다. 평가/운영 시 공식 대시보드(`grafana.com` ID 7362, 763)를 import하면 된다.

### 3.3 자동 프로비저닝 (실제 파일)
```
observability/grafana/
├── provisioning/
│   ├── datasources/datasource.yml      # Prometheus URL (+ Zipkin 데이터소스)
│   └── dashboards/dashboards.yml       # /var/lib/grafana/dashboards 경로 등록
└── dashboards/
    └── app.json                        # 위 App Dashboard
```

## 4. 로깅

### 4.1 형식: JSON (Logback)
```xml
<!-- logback-spring.xml -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>traceId</includeMdcKeyName>
    <includeMdcKeyName>spanId</includeMdcKeyName>
    <includeMdcKeyName>sessionId</includeMdcKeyName>
    <includeMdcKeyName>eventId</includeMdcKeyName>
    <includeMdcKeyName>userId</includeMdcKeyName>
  </encoder>
</appender>
```

### 4.2 MDC 주입 (실제 구현)
- `common/filter/MdcFilter` (HTTP) — 요청 시작 시 traceId/sessionId 등 주입, 응답 후 clear
- WebSocket 측 별도 wrapper 클래스는 없음 — 핸들러 내부에서 attribute로 sessionId/userId를 직접 사용
- `OutboxPoller.drain()` — 배치 시작 시 `MDC.put("batchId", UUID.randomUUID().toString())`, finally 블록에서 `MDC.remove`

### 4.3 로깅 이벤트 카탈로그
| 레벨 | 이벤트 | 내용 |
|---|---|---|
| INFO | `session.created` | sessionId, createdBy |
| INFO | `event.accepted` | eventId, type, sequence |
| WARN | `event.duplicate` | sessionId, clientEventId, existingEventId |
| WARN | `event.out_of_order` | sessionId, sequence, lastSequence |
| ERROR | `projection.failed` | eventId, error, retryCount |
| ERROR | `projection.dead_letter` | originalEventId, reason |
| INFO | `websocket.connected` | sessionId, userId, remoteAddress |
| INFO | `websocket.disconnected` | sessionId, userId, reason |
| INFO | `snapshot.created` | sessionId, version, lastSequence |

## 5. 분산 추적 (Tracing)

### 5.1 구현
- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-zipkin`
- `ObservationRegistry` 로 span 생성
- HTTP 요청 자동 span, WebSocket/DB/Redis 수동 span

### 5.2 주요 Trace 경로

**이벤트 수집 trace:**
```
HTTP /sessions/{id}/events
├─ EventAppendService.append
│   ├─ db.insert (events)
│   ├─ db.update (sessions.last_sequence)
│   └─ redis.publish (session:{id})
```

**WebSocket 메시지 trace (수동 계측):**
```
ws.message.received
├─ EventAppendService.append
│   └─ (위와 동일)
```

**아웃박스 trace (batch 단위):**
```
outbox.drain (batch_id=...)
├─ db.select_for_update
├─ event.apply (eventId=1)
│   ├─ projection.update
│   └─ (조건부) snapshot.create
└─ event.apply (eventId=2)
```

**복원 API trace:**
```
HTTP /sessions/{id}/timeline
├─ snapshot.find_latest
├─ db.events.replay
└─ state.apply_events
```

### 5.3 TraceId-LogId 상관관계
- MDC에 `traceId`, `spanId` 주입 → JSON 로그에 포함 → Zipkin UI에서 trace 조회 시 로그 검색 가능 (수동 grep이나 간이 UI)

### 5.4 Zipkin 설정
- Docker 이미지: `openzipkin/zipkin`
- 포트 9411, 메모리 스토리지 (재시작 시 초기화, 과제용 허용)

## 6. 부하/테스트 데이터 수집

- k6 스크립트 실행 중 Prometheus 메트릭을 Grafana에 실시간 확인
- 스크린샷을 `docs/images/`에 저장 → 설계 문서에 첨부

## 7. 헬스체크 엔드포인트

- `/actuator/health` — DB, Redis 포함
- `/actuator/health/liveness` — 프로세스 생존
- `/actuator/health/readiness` — 의존성 준비 완료 (Docker compose depends_on 대체)

## 8. 설계 문서 평가 매핑

| 과제 요구 | 대응 구현 |
|---|---|
| "로그" | Logback JSON + MDC (traceId/spanId/sessionId/eventId) |
| "메트릭" | Micrometer → Prometheus → Grafana App 대시보드 1종 (mysql/redis는 공식 대시보드 import 가이드만 제공) |
| "추적" | Micrometer Tracing + OTel → Zipkin |
| "운영 대시보드" (가산점) | Grafana 대시보드 + 스크린샷 |
