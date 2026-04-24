# 04. API 명세 (REST + WebSocket)

본 문서는 실제 코드(`src/main/java/com/example/chat/**`)에 구현된 엔드포인트와 프레임만 기술한다.
설계상 거론만 되고 구현되지 않은 기능(Heartbeat, Rate Limit 등)은 별도 절에서 "설계만/미구현"으로 명시한다.

## 1. REST API 요약

| Method | Path | 설명 | 컨트롤러 |
|---|---|---|---|
| POST | `/sessions` | 세션 생성 | `SessionController` |
| GET | `/sessions` | 세션 목록 조회 (필터) | `SessionController` |
| POST | `/sessions/{id}/join` | 참여자 join | `SessionController` |
| POST | `/sessions/{id}/end` | 세션 종료 | `SessionController` |
| POST | `/sessions/{id}/events` | 이벤트 HTTP 수집 (디버깅용 fallback) | `SessionEventController` |
| GET | `/sessions/{id}/timeline?at=...` | 특정 시점 상태 복원 | `SessionTimelineController` |
| POST | `/admin/projections/rebuild?sessionId=...` | 특정 세션 projection 재계산 | `AdminProjectionController` |
| GET | `/admin/dlq` | DLQ 목록 조회 | `DlqAdminController` |
| POST | `/admin/dlq/{id}/retry` | DLQ 항목 수동 재처리 | `DlqAdminController` |
| DELETE | `/admin/dlq/{id}` | DLQ 항목 삭제 | `DlqAdminController` |
| GET | `/actuator/health` | 헬스체크 | Spring Boot Actuator |
| GET | `/actuator/prometheus` | 메트릭 노출 | Spring Boot Actuator |

> 운영 편의용 별도 엔드포인트(`POST /sessions/{id}/snapshots` 같은 스냅샷 수동 생성 API, `GET /sessions/{id}/events` 디버깅 조회 API)는 **제공하지 않는다.**
> - 스냅샷 생성은 비동기 파이프라인에서 자동화되어 있다(`docs/05-event-sourcing.md` §3.3 참조).
> - 운영자가 강제로 projection을 다시 만들고 싶을 때는 `POST /admin/projections/rebuild`를 사용한다.

## 2. 주요 엔드포인트 상세

### 2.1 POST /sessions

**Request Body:** 빈 본문(`{}`). 서버에서 ID/상태/시간을 자동 채번한다.

**Response 201:**
```json
{
  "sessionId": 42,
  "status": "ACTIVE",
  "createdAt": "2026-04-21T15:00:00.123"
}
```

### 2.2 POST /sessions/{id}/join

**Request Body:**
```json
{ "userId": "alice" }
```

**Response 200:**
```json
{
  "sessionId": 42,
  "userId": "alice",
  "joinedAt": "2026-04-21T15:01:00.123",
  "lastSequence": 1
}
```

**동작:**
- `participants` 행 INSERT (트랜잭션). 기존 참여자가 있으면 멱등 응답.
- **JOIN 이벤트를 server-side에서 sequence=`session.lastSequence + 1`로 자동 append** 후 Redis Pub/Sub 브로드캐스트.
- 응답의 `lastSequence`는 JOIN까지 반영된 값이다. 클라이언트는 이 값을 보관하고, 이후 첫 사용자 메시지부터 `lastSequence + 1`로 sequence를 시작해야 한다(자세한 규칙은 §6 참조).

### 2.3 POST /sessions/{id}/events

WebSocket 연결이 어려운 환경에서의 fallback 엔드포인트.

**Request Body:**
```json
{
  "clientEventId": "uuid-v4",
  "userId": "alice",
  "sequence": 17,
  "type": "MESSAGE",
  "payload": { "text": "hello" },
  "clientTimestamp": "2026-04-21T15:02:00.123"
}
```

`userId`, `clientEventId`, `sequence`, `type`은 모두 필수.

**Response 202 (정상):**
```json
{
  "status": "ACCEPTED",
  "eventId": 12345,
  "sequence": 17
}
```

**Response 200 (중복 — `UNIQUE(session_id, client_event_id)` 위반):**
```json
{
  "status": "DUPLICATE_IGNORED",
  "existingEventId": 12340,
  "existingSequence": 17
}
```

### 2.4 GET /sessions/{id}/timeline?at=2026-04-21T15:05:00

**Response 200 (`TimelineResponse`):**
```json
{
  "sessionId": 42,
  "at": "2026-04-21T15:05:00",
  "participants": [
    { "userId": "alice", "joinedAt": "...", "presence": "ONLINE" }
  ],
  "messages": [
    { "eventId": 1, "sequence": 1, "userId": "alice", "text": "hi", "status": "SENT", "serverReceivedAt": "..." }
  ],
  "restoredFromSnapshot": true,
  "snapshotVersion": 3,
  "replayedEventCount": 17
}
```

**필드:**
- `restoredFromSnapshot` (boolean) — 복원 시 스냅샷이 사용됐는지 여부
- `snapshotVersion` (Integer | null) — 사용된 스냅샷 버전. 미사용이면 null
- `replayedEventCount` (int) — 스냅샷 이후 추가로 적용한 이벤트 수

**빈 결과:** 해당 시각까지 이벤트가 없으면 `participants/messages = []`, `restoredFromSnapshot=false`, `snapshotVersion=null`, `replayedEventCount=0` 으로 응답한다.

### 2.5 GET /sessions?status=ACTIVE&from=...&to=...&participant=alice&page=0&size=20

QueryDSL 기반 동적 필터. 페이지네이션된 `SessionListResponse` 반환.

### 2.6 POST /admin/projections/rebuild?sessionId=42

세션의 `session_projection` 행을 events 전체 리플레이로 재계산. 중간에 발생한 적용 누락이나 손상된 projection 복구용.

**Response 200:**
```json
{ "sessionId": 42, "appliedEvents": 234, "lastAppliedEventId": 5678 }
```

### 2.7 GET /admin/dlq

DLQ 진입한 이벤트 전체 목록(`movedAt DESC`).

### 2.8 POST /admin/dlq/{id}/retry

단일 트랜잭션에서 (1) DLQ row 조회 (2) 원본 events row를 PENDING/retry_count=0/next_retry_at=NOW로 리셋 (3) DLQ row 삭제. 다음 폴링 사이클에서 OutboxPoller가 다시 처리.

**Response 202 Accepted.** 원본 또는 DLQ row 없으면 404.

### 2.9 DELETE /admin/dlq/{id}

DLQ row만 삭제. 원본 events 상태는 변경하지 않는다. **Response 204.**

## 3. WebSocket 프로토콜

### 3.1 Connection URL

```
ws://host/ws/chat?sessionId=42&userId=alice&lastSequence=17
```

- `sessionId`, `userId` 필수
- `lastSequence` 선택 (생략 시 0). 재연결 resume 지점

### 3.2 Handshake

- `ChatHandshakeInterceptor`에서 쿼리 파라미터 검증 → `WebSocketSession.attributes`에 `sessionId/userId/lastSequence` 저장
- 세션이 `ENDED` 상태면 거부
- 참여자가 아니면 거부

### 3.3 Client → Server 프레임 (`ClientEventFrame`)

```json
{
  "clientEventId": "uuid-v4",
  "sequence": 18,
  "type": "MESSAGE",
  "payload": { "text": "hi" },
  "userId": "alice",
  "clientTimestamp": "2026-04-21T15:02:00.123Z"
}
```

`sessionId`는 프레임 본문이 아니라 핸드셰이크 attribute에서 가져온다.
`userId`는 핸드셰이크에서 이미 알고 있으므로 보내지 않아도 되고, 프레임에 있어도 무시된다.

### 3.4 Server → Client 프레임

모든 서버 → 클라이언트 프레임은 다음 envelope 구조로 직렬화된다:

```json
{ "frameType": "EVENT|ACK|ERROR|RESUME_BATCH|PRESENCE", "body": { ... } }
```

내부 `body`의 구체 구조는 다음과 같다.

**EVENT (브로드캐스트):**
```json
{
  "frameType": "EVENT",
  "body": {
    "event": {
      "id": 12345,
      "sessionId": 42,
      "sequence": 18,
      "clientEventId": "uuid-v4",
      "userId": "bob",
      "type": "MESSAGE",
      "payload": { "text": "hi" },
      "serverReceivedAt": "2026-04-21T15:02:00.200Z",
      "clientTimestamp": "2026-04-21T15:02:00.123Z"
    }
  }
}
```

**ACK — 정상 수락:**
```json
{
  "frameType": "ACK",
  "body": {
    "clientEventId": "uuid-v4",
    "status": "ACCEPTED",
    "existingEventId": null,
    "existingSequence": null
  }
}
```

**ACK — 중복:**
```json
{
  "frameType": "ACK",
  "body": {
    "clientEventId": "uuid-v4",
    "status": "DUPLICATE_IGNORED",
    "existingEventId": 12340,
    "existingSequence": 18
  }
}
```

**ERROR:**
```json
{
  "frameType": "ERROR",
  "body": {
    "code": "INVALID_SEQUENCE",
    "message": "sequence must be > lastSequence"
  }
}
```

**RESUME_BATCH (재연결 시 한 번 전송):**
```json
{
  "frameType": "RESUME_BATCH",
  "body": {
    "events": [ /* EventPayload 배열 (위 EVENT의 event 형식과 동일) */ ],
    "fromSequence": 18,
    "toSequence": 25
  }
}
```

**PRESENCE (`PresenceFrame` — 정의는 되어 있으나 현재 핸들러는 자동 송신하지 않음. 향후 사용 예약):**
```json
{
  "frameType": "PRESENCE",
  "body": { "userId": "bob", "status": "ONLINE" }
}
```

### 3.5 Heartbeat (설계만, 미구현)

PING/PONG 기반 양방향 heartbeat은 본 구현에 포함되지 않는다. presence는 Redis TTL(`PresenceService`) 기반으로 관리되며, 명시적 disconnect 처리는 `WebSocketSession.afterConnectionClosed`에서 수행된다.

## 4. 에러 코드 체계

| HTTP | code | 설명 | 사용 위치 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 형식 오류 / 알 수 없는 EventType | REST validation, WebSocket parse 실패 |
| 403 | `NOT_PARTICIPANT` | 세션 참여 권한 없음 | Handshake / REST |
| 404 | `SESSION_NOT_FOUND` | 세션 부재 | REST |
| 409 | `SESSION_ENDED` | 종료된 세션에 이벤트 시도 | REST |
| 422 | `INVALID_SEQUENCE` | sequence 규칙 위반 | REST `EventAppendService`, WebSocket handler |
| 200 | (응답 status `DUPLICATE_IGNORED`) | 중복이지만 정상 처리 | REST POST `/events`, WebSocket ACK |
| 500 | `INTERNAL_ERROR` | 그 외 서버 오류 | 글로벌 예외 핸들러 |

## 5. OpenAPI 스펙

레포 내 `openapi/openapi.yaml`(영문, 표준 관례)을 단일 진실 원본으로 사용한다.
springdoc 같은 자동 생성기는 본 구현에는 포함되어 있지 않다.

## 6. 재연결 / Resume 프로토콜

### 6.1 클라이언트 책임

1. 연결 직후 서버가 보내는 ACK/EVENT의 `sequence`를 보관 (`lastReceivedSequence`)
2. disconnect 감지 시 보관해둔 `lastReceivedSequence`를 `?lastSequence=N`로 다시 연결
3. 새 메시지의 sequence는 마지막으로 알려진 `lastSequence + 1`부터 시작 — 자세한 규칙은 §6.3 참조

### 6.2 서버 동작 (`ResumeService`)

- Redis Sorted Set `session:{sessionId}:recent`(`ZRANGEBYSCORE > N`)에서 후속 이벤트 조회
- **캐시 hit이 0건일 때만** MySQL 폴백(`EventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc`)
- 결과를 `RESUME_BATCH` 프레임 1회로 전송. 별도의 `RESUME_COMPLETE` 프레임은 보내지 않는다 — 비어있으면 아무 것도 보내지 않는다.

> 부분 hit(캐시에 일부만 있고 나머지는 DB에 있는 상태)은 현재 발생하지 않는다. `RecentCacheService.append`가 모든 신규 이벤트를 캐시에 추가하므로 캐시는 "전체 또는 0건" 둘 중 하나로 유지된다.

### 6.3 sequence 채번 규칙 (중요)

`/sessions/{id}/join`이 **JOIN 이벤트를 sequence=1로 자동 append**한다.
따라서 클라이언트가 첫 사용자 메시지를 보낼 때는 다음 규칙을 지켜야 한다.

| 시나리오 | 첫 메시지 sequence |
|---|---|
| join 직후 첫 메시지 | `joinResponse.lastSequence + 1` (보통 2) |
| 재연결 직후 첫 메시지 | `lastReceivedSequence + 1` (서버 ACK/EVENT로 본 마지막 값 + 1) |
| 알 수 없으면 | `GET /sessions/{id}/timeline?at=now` 의 가장 큰 `sequence + 1` |

서버는 `session.last_sequence` 기반으로 충돌을 검증하므로 클라이언트가 임의로 `1`부터 시작하면 PK 충돌 → `INVALID_SEQUENCE`가 발생한다.

### 6.4 정합성 보장

- 복원/Resume 모두 `sequence ASC` 단일 정렬 키 사용. PK `(session_id, sequence)`가 UNIQUE이므로 tiebreaker 불필요.
- 클라이언트 측 dedupe는 `clientEventId` 기준으로 권장(서버는 INSERT 시점에 `UNIQUE(session_id, client_event_id)`로 차단).

## 7. Rate Limiting (설계만, 미구현)

서비스 본 구현에는 rate limit 미적용. 운영 시 Redis INCR + EXPIRE 기반 토큰 버킷을 권장. 상세 설계는 `docs/06-async-pipeline.md` 부록 참조.
