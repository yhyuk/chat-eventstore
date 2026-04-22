# 04. API 명세 (REST + WebSocket)

## 1. REST API 요약

| Method | Path | 설명 |
|---|---|---|
| POST | `/sessions` | 세션 생성 |
| GET | `/sessions` | 세션 목록 조회 (필터) |
| POST | `/sessions/{id}/join` | 참여자 join |
| POST | `/sessions/{id}/end` | 세션 종료 |
| POST | `/sessions/{id}/events` | 이벤트 HTTP 수집 (WebSocket 대체/디버깅) |
| GET | `/sessions/{id}/events` | 이벤트 조회 (디버깅) |
| GET | `/sessions/{id}/timeline?at=...` | 특정 시점 상태 복원 |
| POST | `/sessions/{id}/snapshots` | 스냅샷 수동 생성 (선택) |
| POST | `/admin/projections/rebuild` | 특정 세션의 projection 재계산 (운영용) |
| GET | `/admin/dlq` | DLQ 목록 조회 |
| POST | `/admin/dlq/{id}/retry` | DLQ 항목 수동 재처리 |
| GET | `/actuator/health` | 헬스체크 |
| GET | `/actuator/prometheus` | 메트릭 |

## 2. 주요 엔드포인트 상세

### 2.1 POST /sessions
**Request Body:**
```json
{ "createdBy": "alice", "metadata": { "title": "1:1 상담" } }
```
**Response 201:**
```json
{ "sessionId": 42, "status": "ACTIVE", "createdAt": "2026-04-21T15:00:00Z" }
```

### 2.2 POST /sessions/{id}/join
**Request Body:**
```json
{ "userId": "alice" }
```
**Response 200:**
```json
{ "sessionId": 42, "userId": "alice", "joinedAt": "2026-04-21T15:01:00Z" }
```
**동작:** `JOIN` 이벤트 append + participants 테이블 INSERT (트랜잭션).

### 2.3 POST /sessions/{id}/events
**Request Body:**
```json
{
  "clientEventId": "uuid-v4",
  "userId": "alice",
  "sequence": 17,
  "type": "MESSAGE",
  "payload": { "text": "hello" },
  "clientTimestamp": "2026-04-21T15:02:00.123Z"
}
```
**Response 202:** `{ "status": "ACCEPTED", "eventId": 12345 }`
**중복:** `UNIQUE(session_id, client_event_id)` 위반 → 200 OK + `{ "status": "DUPLICATE_IGNORED" }`

### 2.4 GET /sessions/{id}/timeline?at=2026-04-21T15:05:00Z
**Response 200:**
```json
{
  "sessionId": 42,
  "at": "2026-04-21T15:05:00Z",
  "participants": [
    { "userId": "alice", "joinedAt": "..." },
    { "userId": "bob", "joinedAt": "..." }
  ],
  "messages": [
    { "eventId": 1, "userId": "alice", "text": "hi", "status": "SENT", "sequence": 1 },
    { "eventId": 2, "userId": "bob", "text": "hello", "status": "EDITED", "sequence": 2 }
  ],
  "snapshotUsed": { "version": 3, "lastSequence": 100 },
  "replayedEventCount": 17
}
```

### 2.5 GET /sessions?status=ACTIVE&from=...&to=...&participant=alice
**Response 200:** 페이지네이션된 세션 목록.
QueryDSL 기반 동적 쿼리.

## 3. WebSocket 프로토콜

### 3.1 Connection URL
```
ws://host/ws/chat?sessionId=42&userId=alice&lastSequence=17
```
- `lastSequence` 파라미터: 재연결 resume 지점 (생략 시 0)

### 3.2 Handshake
- `HandshakeInterceptor`에서 파라미터 검증 → `WebSocketSession.attributes` 저장
- 세션이 `ENDED` 상태면 403 거부
- 참여 권한 없으면 (user가 해당 session의 participant가 아니면) 403

### 3.3 Client → Server 프레임
```json
{
  "clientEventId": "uuid-v4",
  "sequence": 18,
  "type": "MESSAGE",
  "payload": { "text": "hi" },
  "clientTimestamp": "2026-04-21T15:02:00.123Z"
}
```

### 3.4 Server → Client 프레임

**이벤트 브로드캐스트:**
```json
{
  "frameType": "EVENT",
  "event": {
    "eventId": 12345,
    "sessionId": 42,
    "userId": "bob",
    "sequence": 18,
    "type": "MESSAGE",
    "payload": { "text": "hi" },
    "serverReceivedAt": "2026-04-21T15:02:00.200Z"
  }
}
```

**Ack (중복 포함):**
```json
{
  "frameType": "ACK",
  "clientEventId": "uuid-v4",
  "status": "ACCEPTED"
}
```
```json
{
  "frameType": "ACK",
  "clientEventId": "uuid-v4",
  "status": "DUPLICATE_IGNORED",
  "existingEventId": 12345
}
```

**Resume (재연결 시 놓친 이벤트 전송):**
```json
{
  "frameType": "RESUME_BATCH",
  "events": [ /* ... */ ],
  "fromSequence": 18,
  "toSequence": 25
}
```

**Presence 갱신:**
```json
{
  "frameType": "PRESENCE",
  "userId": "bob",
  "status": "ONLINE"
}
```

**에러:**
```json
{
  "frameType": "ERROR",
  "code": "INVALID_SEQUENCE",
  "message": "sequence must be > lastSequence"
}
```

### 3.5 Heartbeat
- 클라이언트 → 서버: 15초마다 `{ "frameType": "PING" }`
- 서버 → 클라이언트: `{ "frameType": "PONG" }`
- 30초 무응답 시 서버가 `DISCONNECT` 이벤트 기록, Redis presence TTL 만료

## 4. 에러 코드 체계

| HTTP | code | 설명 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 형식 오류 |
| 403 | `NOT_PARTICIPANT` | 세션 참여 권한 없음 |
| 404 | `SESSION_NOT_FOUND` | |
| 409 | `SESSION_ENDED` | 종료된 세션에 이벤트 시도 |
| 422 | `INVALID_SEQUENCE` | sequence 규칙 위반 |
| 200/202 | `DUPLICATE_IGNORED` | 중복이나 정상 처리 |
| 500 | `INTERNAL_ERROR` | |
| 503 | `DEGRADED` | Redis down 등 부분 장애 |

## 5. OpenAPI 생성 전략

- `springdoc-openapi-starter-webmvc-ui` 도입
- `/v3/api-docs` + `/swagger-ui/index.html` 제공
- OpenAPI 필드 설명은 영어(표준), 한국어 별도 설계 문서에서 보완

## 6. 재연결 프로토콜 상세

1. 클라이언트 disconnect 감지 (네트워크 오류 등)
2. 클라이언트는 `lastReceivedSequence`를 로컬 보관
3. 재연결 시 `ws://.../ws/chat?...&lastSequence=N` 으로 연결
4. 서버:
   - Redis `session:{id}:recent` Sorted Set 조회 → `score > N` 항목 전달
   - Sorted Set 사이즈 초과 시 MySQL fallback
   - 전달 완료 후 `RESUME_COMPLETE` 프레임 전송
5. 클라이언트는 이후 새 이벤트 실시간 수신

**정합성 보장:**
- 복원은 `(sequence, server_received_at, id)` 순서로 정렬 → 결정론적
- 중복 이벤트는 `clientEventId` 기반 클라이언트측 dedupe

## 7. Rate Limiting (선택)

- 서버당 초당 이벤트 수 제한: Redis INCR + EXPIRE
- 한도 초과 시 `ERROR` 프레임 + TTL 만료 후 자동 해제
