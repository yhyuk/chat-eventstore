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

### 2.2 커스텀 메트릭

| 메트릭 | 타입 | 태그 | 목적 |
|---|---|---|---|
| `chat.events.received.total` | Counter | `type`, `result` | 수집 이벤트 수 (DUPLICATE_IGNORED 포함) |
| `chat.events.duplicates.total` | Counter | - | 중복 차단 건수 (SLA 관측) |
| `chat.events.out_of_order.total` | Counter | - | sequence 역전 감지 건수 |
| `chat.outbox.pending.size` | Gauge | - | 현재 PENDING 큐 크기 |
| `chat.outbox.lag.seconds` | Gauge | - | 가장 오래된 PENDING 이벤트 지연 |
| `chat.projection.apply.duration` | Timer | `type` | 이벤트 적용 시간 분포 |
| `chat.projection.dead_letter.total` | Counter | `reason` | DLQ 이관 누계 |
| `chat.websocket.sessions.active` | Gauge | - | 현재 열린 WebSocket 세션 수 (인스턴스별) |
| `chat.restore.duration.seconds` | Timer | `source`(snapshot/full) | 복원 API 성능 |
| `chat.redis.pubsub.published.total` | Counter | - | Pub/Sub 발행 건수 |
| `chat.presence.online.count` | Gauge | - | 현재 online 사용자 수 |

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

### 3.1 App Dashboard (핵심)
패널 구성:
1. **이벤트 처리율**: `rate(chat_events_received_total[1m])` (type별 색상)
2. **중복 차단율**: `rate(chat_events_duplicates_total[1m]) / rate(chat_events_received_total[1m])`
3. **순서 역전율**: `rate(chat_events_out_of_order_total[1m])`
4. **아웃박스 lag**: `chat_outbox_lag_seconds` (단일 라인)
5. **아웃박스 처리량**: `rate(chat_outbox_processed_total[1m])`
6. **DLQ 카운트**: `chat_outbox_dead_letter_total`
7. **WebSocket 세션**: `sum(chat_websocket_sessions_active)` (인스턴스 합)
8. **복원 API p50/p99**: `histogram_quantile(0.99, ...)`
9. **Presence online count**: `chat_presence_online_count`

### 3.2 MySQL Dashboard
- 공식 대시보드 ID 7362 import
- 추가 패널: slow query (>100ms 비율)

### 3.3 Redis Dashboard
- 공식 대시보드 ID 763 import
- 추가 패널: pub/sub subscribers, channel별 메시지 rate

### 3.4 자동 프로비저닝
```
observability/grafana/provisioning/
├── datasources/datasource.yml      # Prometheus URL
└── dashboards/
    ├── dashboard.yml
    ├── app.json
    ├── mysql.json
    └── redis.json
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

### 4.2 MDC 주입
- `MdcInterceptor` (HTTP) — 요청 시작 시 traceId/spanId/userId 주입, 응답 후 clear
- `WebSocketMdcWrapper` — WebSocket 메시지 처리 시 동일
- `OutboxWorkerMdc` — 배치 시작 시 batch_id, 이벤트별 eventId 주입

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

## 8. 요구 항목 매핑

| 요구 항목 | 대응 구현 |
|---|---|
| "로그" | Logback JSON + MDC (traceId/spanId/sessionId/eventId) |
| "메트릭" | Micrometer → Prometheus → Grafana 대시보드 3종 |
| "추적" | Micrometer Tracing + OTel → Zipkin |
| "운영 대시보드" | Grafana 대시보드 + 스크린샷 |
