# chat-eventstore

1:1 실시간 채팅 + 이벤트 기반 상태 복원 백엔드 (Spring Boot 3.3, Java 21)

## 개요

WebSocket 실시간 통신과 이벤트 소싱 기반의 특정 시점 상태 복원을 다루는 백엔드 프로젝트.
중복/순서 처리, 수평 확장, DB 설계 및 쿼리 최적화 패턴을 함께 다룬다.

---

## 빠른 시작

### 1) 빌드

```bash
./gradlew bootJar
```

### 2) 전체 스택 기동 (앱 2대 + MySQL + Redis + Prometheus + Grafana + Zipkin + exporter 2종)

```bash
docker compose up -d --build
```

### 3) 헬스체크

```bash
curl http://localhost:7081/actuator/health
curl http://localhost:7082/actuator/health
```

### 4) 재현 스크립트 실행

```bash
chmod +x scripts/reproduce.sh
./scripts/reproduce.sh
```

### 5) OpenAPI 스펙 추출

```bash
chmod +x scripts/export-openapi.sh
./scripts/export-openapi.sh
```

---

## 주요 서비스 URL

| 서비스 | URL | 비고 |
|---|---|---|
| app1 | http://localhost:7081 | Spring Boot 인스턴스 1 |
| app2 | http://localhost:7082 | Spring Boot 인스턴스 2 |
| Grafana | http://localhost:3100 | admin/admin |
| Prometheus | http://localhost:9190 | — |
| Zipkin | http://localhost:9411 | — |
| Actuator health | http://localhost:7081/actuator/health | — |
| Actuator prometheus | http://localhost:7081/actuator/prometheus | — |
| Swagger UI | http://localhost:7081/swagger-ui.html | — |

---

## 아키텍처 요약

### 핵심 기술 선택 (ADR 기반)

| 주제 | 선택 | 근거 |
|---|---|---|
| **실시간 통신** | 순수 WebSocket | `clientEventId/sequence/clientTimestamp` 자체 이벤트 스키마 설계, STOMP 프레임 제약 회피 |
| **주 DB** | MySQL 8.x | InnoDB clustered index `(session_id, sequence)` 로 세션별 순차 I/O 최적화, 익숙한 스택 |
| **ORM** | JPA + QueryDSL | append-only 구조에서 JPA 강점, Native SQL은 SKIP LOCKED 1곳만 사용 |
| **비동기 파이프라인** | DB 아웃박스 + `@Scheduled` + SKIP LOCKED | 이중 쓰기 문제 원천 차단, 운영 단순화 |
| **Redis 역할** | Pub/Sub · Presence TTL · Recent N Sorted Set | 3가지에 한정 (YAGNI) |
| **관측 스택** | Micrometer + Prometheus + Grafana + Zipkin | 메트릭 + 로그 + 추적 3요소 전부 구현 |

자세한 근거는 `docs/01-overview-and-decisions.md` 의 9개 ADR 참조.

---

## 디렉토리 구조

```
chat-eventstore/
├── build.gradle / settings.gradle
├── docker-compose.yml / Dockerfile
│
├── src/main/java/com/example/chat/
│   ├── session/            # 세션 도메인 (CRUD API)
│   ├── event/              # 이벤트 수집 (WebSocket + HTTP fallback)
│   ├── projection/         # 아웃박스 워커, projection 서비스, snapshot
│   ├── realtime/           # WebSocket 핸들러, Redis Pub/Sub
│   ├── presence/           # Redis TTL 기반 온라인 상태
│   ├── restore/            # 특정 시점 상태 복원 API
│   └── common/             # 설정, 예외, 유틸
│
├── src/main/resources/
│   ├── application.yml / application-docker.yml
│   ├── logback-spring.xml
│   └── db/migration/V1~V5__*.sql   # Flyway DDL
│
├── docs/                           # 설계 문서 (한국어)
│   ├── 01-overview-and-decisions.md
│   ├── 02-architecture.md
│   ├── 03-db-schema.md
│   ├── 04-api-spec.md
│   ├── 05-event-sourcing.md
│   ├── 06-async-pipeline.md
│   ├── 07-observability.md
│   ├── 08-failure-scenarios.md
│   ├── 09-testing-and-load.md
│   ├── 10-query-optimization.md
│   ├── 11-ai-harness-engineering.md
│   └── images/                     # Grafana / Zipkin 스크린샷
│
├── openapi/openapi.yaml            # OpenAPI 영어 스펙
├── observability/
│   ├── prometheus/prometheus.yml
│   └── grafana/{provisioning,dashboards}
│
├── scripts/
│   ├── reproduce.sh                # E2E 재현 스크립트
│   ├── export-openapi.sh
│   └── load-test.js                # k6 부하 테스트
│
└── http/                           # .http 재현 파일
```

---

## 주요 API

### REST Endpoints

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| POST | `/sessions` | 세션 생성 |
| POST | `/sessions/{id}/join` | 사용자 참여 |
| POST | `/sessions/{id}/end` | 세션 종료 |
| POST | `/sessions/{id}/events` | HTTP fallback (WebSocket 불가 시) |
| GET | `/sessions/{id}/timeline?at=...` | 특정 시점 상태 복원 |
| GET | `/sessions` | 세션 목록 (status/from/to/participant 필터) |
| POST | `/admin/projections/rebuild?sessionId=X` | Projection 재계산 (운영 API) |
| GET | `/admin/dlq` | Dead Letter Queue 조회 |

### WebSocket

```
ws://host/ws/chat?sessionId=X&userId=Y&lastSequence=N
```

전체 스펙: `openapi/openapi.yaml` 또는 http://localhost:7081/swagger-ui.html

---

## 이벤트 스키마

### WebSocket 클라이언트 → 서버 프레임

```json
{
  "clientEventId": "550e8400-e29b-41d4-a716-446655440000",
  "sequence": 17,
  "type": "MESSAGE | JOIN | LEAVE | EDIT | DELETE | DISCONNECT | RECONNECT",
  "payload": {
    "text": "...",
    "metadata": {}
  },
  "clientTimestamp": "2026-04-21T15:00:00.123Z"
}
```

### 순서 기준 (복원 시)

```sql
ORDER BY sequence ASC, server_received_at ASC, id ASC
```

### 중복 방지

`UNIQUE(session_id, client_event_id)` 제약으로 멱등성 보장.

---

## 관측 가능성

### 메트릭 (Micrometer → Prometheus → Grafana)

- 세션 생성/진행/종료 카운터
- WebSocket 연결/재연결/단절 메트릭
- 이벤트 수신/처리 throughput
- Projection 파이프라인 지연(latency)
- DLQ 진입 이벤트 수

**Grafana 대시보드 3종:**
- Application Metrics (이벤트 처리, WebSocket)
- MySQL Performance (쿼리 성능)
- Redis Usage (Pub/Sub, Presence, 캐시)

<!-- GRAFANA_SCREENSHOT -->
*(스크린샷 수동 캡처: `docs/images/CAPTURE.md` 참고. 파일 저장 경로: `docs/images/grafana-app-dashboard.png`)*

### 로그 (Logback JSON + MDC)

```
{
  "timestamp": "2026-04-21T15:00:00.123Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "sessionId": "session-123",
  "userId": "user-456",
  "requestId": "req-789",
  "batchId": "batch-1000",
  "eventId": "event-id-abc",
  "level": "INFO",
  "message": "Event processed: sequence=17",
  ...
}
```

### 추적 (Micrometer Tracing + OpenTelemetry → Zipkin)

- WebSocket 이벤트 수신 → DB 저장 → Redis 발행 → Projection 반영까지 전체 추적
- 샘플링: 100% (모든 요청 기록)

<!-- ZIPKIN_SCREENSHOT -->
*(스크린샷 수동 캡처: `docs/images/CAPTURE.md` 참고. 파일 저장 경로: `docs/images/zipkin-trace.png`)*

---

## 부하 테스트

k6 스크립트로 다음 시나리오 실행:

1. **Ramp-up**: 0VU → 50VU (30초)
2. **Sustained**: 50VU 유지 (60초)
3. **Restore burst**: restore API 집중 호출 (30초)
4. **Ramp-down**: 50VU → 0VU (10초)

### 설계 임계치 (참고)

- `http_req_duration{name:events} p(99) < 200ms`
- `http_req_duration{name:restore} p(99) < 500ms`
- `http_req_failed rate < 0.05`

### 실행 방법

```bash
docker compose up -d --build
k6 run --summary-export=docs/load-test-results/k6-$(date +%Y%m%d-%H%M%S).json scripts/load-test.js || true
```

---

## 테스트 전략

### 단위 테스트

- `EventReplayService` 결정론성
- 중복 이벤트 필터링
- 순서 역전 정렬

### 통합 테스트 (Testcontainers)

- MySQL 8.x + Redis 7.x 컨테이너 기동
- 세션 라이프사이클
- 중복 이벤트 멱등성
- 순서 역전 정렬 및 복원 결정론성
- WebSocket 재연결 + resume

### 부하 테스트 (k6)

- 10개 세션, 각 2명 사용자
- 10 메시지/초 × 60초
- restore API burst 30초

### 실행

```bash
./gradlew test
```

---

## 설계 문서

모든 설계 문서는 `docs/` 디렉토리에 한국어로 작성되어 있습니다.

| # | 제목 | 경로 | 요약 |
|---|---|---|---|
| 01 | 개요 + ADR 9개 | [docs/01-overview-and-decisions.md](docs/01-overview-and-decisions.md) | 기술 선택 근거 및 트레이드오프 |
| 02 | 아키텍처 + 도메인 | [docs/02-architecture.md](docs/02-architecture.md) | 시스템 아키텍처, 도메인 모델, 데이터 흐름 |
| 03 | DB 스키마 + 인덱스 | [docs/03-db-schema.md](docs/03-db-schema.md) | DDL, ERD, 인덱스 전략 |
| 04 | API 명세 | [docs/04-api-spec.md](docs/04-api-spec.md) | REST + WebSocket 프로토콜 상세 |
| 05 | 이벤트 소싱 전략 | [docs/05-event-sourcing.md](docs/05-event-sourcing.md) | 중복/순서/복원 알고리즘, Tombstone, GDPR 대응 (설계) |
| 06 | 비동기 파이프라인 | [docs/06-async-pipeline.md](docs/06-async-pipeline.md) | 아웃박스 워커, rebuild API, rate limit 설계 |
| 07 | 관측 가능성 | [docs/07-observability.md](docs/07-observability.md) | 메트릭, 로그, 추적 구성 및 대시보드 |
| 08 | 장애 시나리오 | [docs/08-failure-scenarios.md](docs/08-failure-scenarios.md) | 3가지 장애: 감지 → 완화 → 복구 |
| 09 | 테스트 + 부하 | [docs/09-testing-and-load.md](docs/09-testing-and-load.md) | 단위/통합/부하 테스트 전략 |
| 10 | 쿼리 최적화 | [docs/10-query-optimization.md](docs/10-query-optimization.md) | EXPLAIN 분석, 인덱스 활용 |
| 11 | AI 하네스 엔지니어링 | [docs/11-ai-harness-engineering.md](docs/11-ai-harness-engineering.md) | 4역할 AI 팀 합의 사이클, 결함 차단 사례 |

---

## 주요 의사결정 (ADR 1줄 요약)

1. **ADR-001**: 순수 WebSocket으로 이벤트 스키마 자유도 확보, STOMP 제약 회피
2. **ADR-002**: MySQL 8.x의 clustered index로 세션별 순차 I/O 최적화, 익숙한 스택
3. **ADR-003**: JPA + QueryDSL로 append-only 특성 활용, Native는 SKIP LOCKED 1곳만
4. **ADR-004**: DB 아웃박스로 이중 쓰기 문제 원천 차단, 트랜잭셔널 일관성 확보
5. **ADR-005**: Redis 3용도(Pub/Sub, Presence, Recent N)로 한정, YAGNI 원칙 준수
6. **ADR-006**: 메트릭 + 로그 + 추적 풀셋으로 관측 가능성 3요소 전부 구현
7. **ADR-007**: 단일 모듈 + 도메인 패키지로 ROI 최적화
8. **ADR-008**: 쿼리 파라미터 기반 최소 식별 (인증 체계는 범위 외)
9. **ADR-009**: 단위 + 통합(Testcontainers) + k6 부하 테스트 조합

---

## 설계 문서만 존재 (구현 범위 외)

다음 항목들은 설계 문서에만 기재되어 있습니다:

- JWT/OAuth2 인증 체계 (쿼리 파라미터 기반으로 대체)
- Kafka 기반 이벤트 스트리밍 파이프라인 (DB 아웃박스로 대체)
- GDPR / 탈퇴 사용자 데이터 삭제 대응 (crypto-shredding 설계는 docs/05)
- Hot/Warm/Cold 스토리지 티어링 (설계는 docs/06)
- Rate Limiting / Backpressure (설계는 docs/06)
- 자동 페일오버 (장애 시나리오는 docs/08)
- WebRTC 영상 통화

---

## 제약 및 알려진 한계

- **인증**: 쿼리 파라미터 기반 최소 식별 (범위 외)
- **1:N 채팅**: 범위 외 (1:1 채팅 전용)
- **파일 첨부**: 범위 외
- **부하 테스트 환경**: 단일 노트북 기준이므로 p99 threshold는 참고치

---

## 개발 방법론 — AI 페어 프로그래밍

본 프로젝트는 **Claude Code 위에 4역할 가상 엔지니어링 팀**을 구성하여 진행되었다.

| 역할 | 책임 | 권한 |
|---|---|---|
| Planner | 요구사항 분해, 옵션 비교, ADR 초안 | read + plan |
| Architect | 설계 검증, 위험 식별 | read-only |
| Critic | 결함 지적 (BLOCKER/MAJOR/MINOR) | read-only |
| Executor | Phase 단위 실제 구현 | write + Bash |

`Planner v1 → Architect → Planner v2 → Critic → Planner v3 → Executor` 합의 사이클을 강제하여 결함을 코드 진입 전에 차단한다.

상세 도구 스택, 결함 차단 사례는 [docs/11-ai-harness-engineering.md](docs/11-ai-harness-engineering.md) 참조.

---

## 보안 주의

본 저장소는 **로컬 실행 편의**를 위해 비밀번호/설정을 평문 포함하고 있다.

- `docker-compose.yml`: MySQL root/root, Redis 비번 없음
- `application-docker.yml`: DB/Redis 접속 정보

**운영 환경에서는 반드시:**
- AWS Secrets Manager, Azure Key Vault 등 사용
- 환경 변수 주입 (`-e DB_PASSWORD=...`)

로컬 실행 중 문제는 `docker compose logs -f app1` 으로 로그 확인.
