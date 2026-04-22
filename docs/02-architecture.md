# 02. 아키텍처 및 도메인 모델

## 1. 논리 아키텍처

```
┌───────────────────────────┐     ┌───────────────────────────┐
│   WebSocket Client A       │     │   WebSocket Client B       │
└─────────────┬─────────────┘     └─────────────┬─────────────┘
              │ ws://.../ws/chat                 │
              ▼                                  ▼
     ┌────────────────┐                 ┌────────────────┐
     │ Spring Boot #1 │ ◀──Pub/Sub───▶ │ Spring Boot #2 │
     │  (WebSocket    │                 │  (WebSocket    │
     │   Handler)     │                 │   Handler)     │
     └────┬─────┬─────┘                 └────┬─────┬─────┘
          │     │                            │     │
   REST   │     │ Outbox Worker              │     │
   API    │     ▼ @Scheduled                 │     │
          │     ┌──────────────────┐          │     │
          │     │ Projection Svc   │          │     │
          │     │ Snapshot Svc     │          │     │
          │     └────────┬─────────┘          │     │
          │              │                    │     │
          ▼              ▼                    ▼     ▼
     ┌──────────────────────────┐       ┌─────────────────┐
     │        MySQL 8.x          │       │      Redis      │
     │  events / sessions /      │       │ Pub/Sub         │
     │  participants / snapshot /│       │ Presence (TTL)  │
     │  projection / DLQ         │       │ Recent N cache  │
     └──────────────────────────┘       └─────────────────┘
              │                                  │
              ▼                                  ▼
     ┌──────────────────┐               ┌──────────────────┐
     │ mysql-exporter   │               │ redis-exporter   │
     └────────┬─────────┘               └────────┬─────────┘
              └────────────┬────────────────────┘
                           ▼
                ┌─────────────────────┐
                │    Prometheus        │
                └──────────┬──────────┘
                           ▼
                ┌─────────────────────┐
                │    Grafana           │
                └─────────────────────┘

Micrometer Tracing → OpenTelemetry → Zipkin
```

## 2. 주요 트래픽 흐름

### (A) 이벤트 수집 플로우
1. Client가 WebSocket으로 메시지 frame 전송 (`{ clientEventId, sequence, type, payload }`)
2. Spring Boot가 `events` 테이블에 INSERT
   - `UNIQUE(session_id, client_event_id)` 위반 시 중복으로 판단, 무시
   - `projection_status = 'PENDING'` 설정
3. **같은 트랜잭션 내** 에서 Redis `PUBLISH session:{id}`로 즉시 broadcast
4. 다른 Spring Boot 인스턴스에 연결된 참여자들에게 메시지 전달
5. 이후 아웃박스 워커가 projection/snapshot 업데이트

### (B) 재연결 / Resume 플로우
1. Client가 재연결, `?lastSequence=N` 파라미터 전달
2. 서버가 Redis `session:{id}:recent` Sorted Set 확인
3. 캐시 hit: Sorted Set에서 `score > N` 항목 즉시 전달
4. 캐시 miss: MySQL `events`에서 `sequence > N` 조회 후 전달

### (C) 상태 복원 플로우
1. `GET /sessions/{id}/timeline?at=2026-04-21T15:00:00Z`
2. 가장 가까운 이전 snapshot 로드 (없으면 빈 상태)
3. snapshot 이후부터 `at` 까지의 이벤트 리플레이
4. 결과 상태 반환 (참여자 목록, 메시지 목록, 메시지별 상태)

## 3. 도메인 모델

### 3.1 Aggregate

**Session (집약 루트)**
- sessionId (PK)
- status: `ACTIVE`, `SUSPENDED`, `ENDED`
- createdAt, endedAt
- lastSequence (최대 시퀀스, 낙관적 락)

**Participant**
- id (PK)
- sessionId (FK)
- userId
- joinedAt, leftAt
- presenceStatus: `ONLINE`, `OFFLINE` (Redis 반영)

**Event (append-only)**
- id (PK, auto increment)
- sessionId
- clientEventId (멱등키)
- sequence (세션 내 순서)
- type: `MESSAGE`, `JOIN`, `LEAVE`, `DISCONNECT`, `RECONNECT`, `EDIT`, `DELETE`
- payload (JSON)
- clientTimestamp
- serverReceivedAt
- projectionStatus: `PENDING`, `PROCESSING`, `DONE`, `FAILED`
- retryCount
- nextRetryAt

**Snapshot**
- sessionId + version (PK)
- stateJson (직렬화된 세션 상태)
- lastEventId (해당 snapshot까지 반영된 이벤트)
- createdAt

**SessionProjection (읽기 모델)**
- sessionId (PK)
- participantCount
- messageCount
- lastMessageAt
- lastAppliedEventId

**DeadLetterEvent**
- id (PK)
- originalEventId
- sessionId
- error, stackTrace
- failedAt
- retryCount

### 3.2 이벤트 타입 카탈로그

| type | payload 예시 | 비고 |
|---|---|---|
| `MESSAGE` | `{ "text": "hi" }` | 기본 메시지 |
| `JOIN` | `{ }` | 참여자 입장 |
| `LEAVE` | `{ }` | 참여자 퇴장 |
| `DISCONNECT` | `{ "reason": "timeout" }` | 일시 단절 |
| `RECONNECT` | `{ "lastSeq": 123 }` | 재연결 |
| `EDIT` | `{ "targetEventId": 10, "text": "fixed" }` | 메시지 수정 |
| `DELETE` | `{ "targetEventId": 10 }` | 메시지 삭제 |

### 3.3 이벤트 스키마 (WebSocket 프레임)

```json
{
  "clientEventId": "uuid-v4",
  "sessionId": 42,
  "userId": "alice",
  "sequence": 17,
  "type": "MESSAGE",
  "payload": { "text": "hello" },
  "clientTimestamp": "2026-04-21T15:00:00.123Z"
}
```

**필드 설명:**
- `clientEventId`: 클라이언트 생성 UUID, 중복 방지 멱등키
- `sequence`: 세션 내 단조 증가, 클라이언트가 생성 (재전송 시 동일 유지)
- `clientTimestamp`: 클라이언트 시각, 순서 tiebreaker
- `serverReceivedAt` (서버 주입): 최종 정렬 기준

## 4. 프로젝트 최상위 구조

```
chat-eventstore/
├── README.md                      # 실행 방법, 주요 의사결정 요약
├── build.gradle
├── settings.gradle
├── docker-compose.yml
├── Dockerfile
├── src/
│   ├── main/
│   │   ├── java/com/example/chat/ # (아래 패키지 구조 참고)
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── logback-spring.xml
│   │       └── db/migration/       # Flyway
│   └── test/
│       └── java/com/example/chat/
├── docs/                          # 모든 설계 문서 (README 제외)
│   ├── 01-overview-and-decisions.md   # ADR 통합본
│   ├── 02-architecture.md
│   ├── 03-db-schema.md
│   ├── 04-api-spec.md             # OpenAPI 링크 + 한국어 해설
│   ├── 05-event-sourcing.md       # 중복/순서/복원 전략
│   ├── 06-async-pipeline.md       # 아웃박스 + DLQ
│   ├── 07-observability.md
│   ├── 08-failure-scenarios.md
│   ├── 09-testing-and-load.md
│   ├── 10-query-optimization.md   # 주요 쿼리 2~3개 + 인덱스 근거
│   ├── adr/                       # 개별 ADR (세부)
│   │   ├── adr-001-websocket.md
│   │   ├── adr-002-mysql.md
│   │   └── ...
│   ├── diagrams/                  # mermaid, 아키텍처 다이어그램
│   └── images/                    # 대시보드 스크린샷 등
├── openapi/
│   └── openapi.yaml               # 영어 표준 스펙
├── scripts/
│   ├── reproduce.sh
│   └── load-test.js               # k6
├── http/                          # IntelliJ http client / VSCode REST Client
│   ├── create-session.http
│   ├── send-event.http
│   └── restore-timeline.http
└── observability/
    ├── prometheus/
    │   └── prometheus.yml
    ├── grafana/
    │   ├── provisioning/
    │   └── dashboards/
    └── zipkin/                    # (선택) 설정 있으면
```

**운영 원칙:**
- `README.md`는 **프로젝트 최상위에 고정** — 실행법/주요 의사결정 요약만.
- 모든 설계 문서 / ADR / 장애 시나리오 / 테스트 전략 등은 **`docs/` 하위에서 관리**.
- 다이어그램 소스(mermaid)와 이미지는 `docs/diagrams/`, `docs/images/` 로 분리.
- OpenAPI는 **코드/문서와 분리된 `openapi/` 디렉토리**에서 버전 관리.

## 5. 패키지 구조 상세

```
com.example.chat
├── ChatEventStoreApplication.java
├── common/
│   ├── config/            # WebSocket, Redis, JPA, Observability config
│   ├── exception/
│   └── util/
├── session/
│   ├── controller/        # POST /sessions, GET /sessions, POST /sessions/{id}/end
│   ├── service/
│   ├── repository/
│   └── domain/            # Session, Participant entity
├── event/
│   ├── controller/        # POST /sessions/{id}/events (HTTP fallback + debug)
│   ├── service/           # EventAppendService, DuplicateDetector, OrderingService
│   ├── repository/
│   └── domain/            # Event entity
├── projection/
│   ├── worker/            # OutboxPoller @Scheduled
│   ├── service/           # ProjectionService, SnapshotService
│   ├── repository/
│   └── domain/            # SessionProjection, Snapshot, DeadLetterEvent
├── realtime/
│   ├── handler/           # ChatWebSocketHandler
│   ├── interceptor/       # HandshakeInterceptor
│   ├── registry/          # SessionRegistry (in-memory Map)
│   └── pubsub/            # RedisMessagePublisher, RedisMessageSubscriber
├── presence/
│   ├── service/           # PresenceService (Redis SET/TTL)
│   └── scheduler/         # Heartbeat 관리
├── restore/
│   ├── controller/        # GET /sessions/{id}/timeline
│   └── service/           # EventReplayService, SnapshotReplayService
└── observability/
    ├── metrics/           # Micrometer custom metrics
    ├── tracing/
    └── logging/           # MDC interceptor
```

## 6. 수평 확장 전략

- 앱 서버는 **무상태** 가정 (로컬 Map은 연결된 클라이언트 WebSocket만 관리, 진실의 원천 아님)
- 메시지 전달은 **Redis Pub/Sub**으로 모든 인스턴스에 broadcast
- 각 인스턴스는 **자신이 보유한 WebSocket 세션**에만 실제 전송
- Presence는 Redis TTL로 중앙 관리
- 아웃박스 워커는 모든 인스턴스에서 실행되며 `SKIP LOCKED`로 경합 없음
- 세션 sticky 라우팅 **불필요** → 로드 밸런서 단순화

## 7. 트랜잭션 경계

| 작업 | 트랜잭션 경계 |
|---|---|
| 이벤트 insert + Redis publish | DB 트랜잭션 commit 후 publish (최소 1회 전달 보장) |
| projection 업데이트 | 단일 트랜잭션 내 `events.status` 변경 + `session_projection` 업데이트 |
| snapshot 생성 | 별도 트랜잭션, idempotent |
| 중복 감지 | `UNIQUE` 제약 + `DataIntegrityViolationException` catch |

## 8. 가정 사항 명시

- 이벤트 페이로드 크기는 < 4KB (MySQL `JSON` 타입 권장 범위)
- 동시 활성 세션 수: < 1,000 (초기 설계 기준)
- 이벤트/초: < 100 (설계 기준치, 실제 부하 테스트로 검증)
- 세션당 최대 이벤트 수: < 100,000 (스냅샷 주기 설계 참고치)
