# 05. 이벤트 소싱 전략 (중복/순서/복원)

## 1. 중복 이벤트 방지 전략

### 1.1 기본 원칙
**클라이언트가 생성한 `clientEventId`를 멱등 키로 사용한다.**

### 1.2 구현 방식
- `events` 테이블의 `UNIQUE(session_id, client_event_id)` 제약
- INSERT 시 `DataIntegrityViolationException` 발생 → 애플리케이션에서 무시 또는 기존 이벤트 조회 후 ACK 반환
- `clientEventId`는 UUID v4 권장 (클라이언트 라이브러리에서 재전송 시 동일 값 유지)

### 1.3 트레이드오프

| 전략 | 장점 | 단점 | 채택 |
|---|---|---|---|
| **UNIQUE 제약** | DB 레벨 보장, 단순 | INSERT 실패 비용 (예외 throw) | **채택** |
| 사전 SELECT 후 INSERT | 예외 없음 | race condition, 2회 쿼리 | 미채택 |
| Redis 키 기반 dedupe | 빠름 | TTL 이후 중복 발생, Redis 장애 시 무력화 | **보조용** (단기 중복 빠른 차단) |
| 메시지 해시 | payload 중복까지 방지 | payload 동일하지만 의도상 다른 이벤트 오차단 | 미채택 |

### 1.4 Redis 보조 중복 감지 (선택적)
- 최근 1분 내 `clientEventId` 를 Redis `SET client_evt:{id} 1 EX 60` 으로 기록
- 핫패스에서 빠른 차단, TTL 만료 이후는 DB 제약이 최종 방어선
- Redis 장애 시 fallback = DB 제약 (즉, 장애 도메인 격리)

---

## 2. 순서 뒤바뀜 처리 전략

### 2.1 순서 기준 정의

**최종 정렬 키:**
```
ORDER BY sequence ASC, server_received_at ASC, id ASC
```

**근거:**
- `sequence`: 클라이언트가 할당한 세션 내 단조 증가 번호 — 주 정렬 키
- `server_received_at`: 동일 sequence 충돌 시 tiebreaker (이론상 충돌 없지만 방어적)
- `id`: 최종 fallback (INSERT 순서)

### 2.2 클라이언트 `sequence` 규칙
- 클라이언트는 세션별로 단조 증가 sequence를 부여
- 재전송 시 **동일 sequence 유지**
- 새 이벤트는 **직전 sequence + 1** 이 이상적이나, 네트워크 역전으로 큰 번호가 먼저 도착할 수 있음
- 서버는 **sequence 역전을 허용** — 복원 시 `ORDER BY sequence`로 정렬되므로 저장 순서와 무관

### 2.3 허용/거부 규칙

| 상황 | 처리 |
|---|---|
| `sequence > lastSequence` | 정상 |
| `sequence == lastSequence`, `clientEventId` 동일 | 중복, 무시 |
| `sequence == lastSequence`, `clientEventId` 다름 | **충돌** — `ERROR` 프레임 반환 (422) |
| `sequence < lastSequence`, 해당 sequence가 비어있음 | **뒤늦게 도착한 이벤트**, 저장 허용 |
| `sequence < lastSequence`, 해당 sequence가 이미 존재 | 중복 또는 충돌로 처리 |

### 2.4 `lastSequence` 관리
- `sessions.last_sequence` 컬럼으로 세션당 최대 sequence 추적
- 이벤트 append 시 `UPDATE sessions SET last_sequence = GREATEST(last_sequence, :seq)`

---

## 3. 이벤트 기반 상태 복원 전략

### 3.1 복원 모델 선택

**결정:** **Snapshot + Replay 하이브리드**

**대안 비교:**

| 전략 | 장점 | 단점 |
|---|---|---|
| 전체 리플레이 | 단순, 스냅샷 관리 불필요 | 세션당 이벤트 수 증가 시 O(N) 비용 |
| **Snapshot + Replay** | 최근 스냅샷부터 리플레이 → 성능 우수 | 스냅샷 정합성 관리 필요 |
| 상태 테이블만 | 조회 최속 | 시점 복원 불가, 이벤트 소싱 의미 상실 |

### 3.2 복원 알고리즘

```
function restoreAt(sessionId, at):
    # 1. 가장 가까운 이전 스냅샷 로드
    snapshot = findLatestSnapshot(sessionId, createdAtLessThanOrEqual=at)

    if snapshot is null:
        state = emptyState()
        fromSequence = 0
    else:
        state = deserialize(snapshot.stateJson)
        fromSequence = snapshot.lastSequence

    # 2. 스냅샷 이후부터 at 까지의 이벤트 조회
    events = findEvents(
        sessionId,
        sequenceGreaterThan=fromSequence,
        serverReceivedAtLessThanOrEqual=at,
        orderBy="sequence, server_received_at, id"
    )

    # 3. 중복 제거 (clientEventId 기준)
    events = deduplicateByClientEventId(events)

    # 4. 이벤트 순차 적용
    for event in events:
        state = applyEvent(state, event)

    return state
```

### 3.3 스냅샷 생성 주기

- **기본 규칙:** 세션당 이벤트 100개마다 스냅샷 자동 생성
- **수동 트리거:** `POST /sessions/{id}/snapshots` (관리용)
- **세션 종료 시:** `POST /sessions/{id}/end` 호출 시 최종 스냅샷 생성
- **생성 주체:** 아웃박스 워커가 projection 업데이트와 함께 실행

### 3.4 결정론 보장 (Determinism)

복원이 결정론적이려면 다음 조건을 모두 만족해야 함:

1. **정렬 기준이 고정**: `(sequence, server_received_at, id)` — 세 필드 모두 immutable
2. **중복 이벤트 제거 규칙 고정**: `clientEventId` 기준, 먼저 저장된 것 유지
3. **`applyEvent` 함수가 순수 함수**: 외부 상태 의존 금지 (시간, 랜덤 불가)
4. **스냅샷 재현 가능성**: 스냅샷은 특정 `lastSequence` 지점의 결정론적 결과여야 함 → 스냅샷 생성 로직도 동일 알고리즘 사용

**테스트로 검증:** 동일 이벤트 스트림을 2번 리플레이 → 결과 상태 동등성 확인 (통합 테스트에서 실행).

### 3.5 이벤트별 `applyEvent` 로직

| type | 상태 변경 |
|---|---|
| `JOIN` | participants 집합에 추가 |
| `LEAVE` | participants 집합에서 제거 |
| `DISCONNECT` | presence 상태 `OFFLINE` 로, participant 유지 |
| `RECONNECT` | presence 상태 `ONLINE` 로 |
| `MESSAGE` | messages 리스트에 추가 (status=SENT) |
| `EDIT` | `targetEventId` 메시지의 text 갱신, status=EDITED |
| `DELETE` | `targetEventId` 메시지의 status=DELETED |

### 3.6 복원 대상 상태 (`state` 구조)

```json
{
  "sessionId": 42,
  "participants": [
    { "userId": "alice", "joinedAt": "...", "presence": "ONLINE" }
  ],
  "messages": [
    { "eventId": 1, "sequence": 1, "userId": "alice", "text": "hi", "status": "SENT", "serverReceivedAt": "..." }
  ],
  "lastSequence": 17,
  "lastAppliedEventId": 123
}
```

---

## 4. 복원 성능 설계

### 4.1 인덱스
- 스냅샷 조회: `snapshots.idx_session_last_seq` (created_at 기준 역순)
- 이벤트 리플레이: PK `(session_id, sequence)` — clustered index 순차 I/O

### 4.2 예상 성능

| 세션 이벤트 수 | 스냅샷 간격 | 평균 리플레이 이벤트 | 예상 p99 |
|---|---|---|---|
| 1,000 | 100 | 50 | <50ms |
| 10,000 | 100 | 50 | <50ms |
| 100,000 | 100 | 50 | <100ms |

### 4.3 병목 및 완화
- **병목:** JSON 역직렬화 (snapshot state) — Jackson ObjectMapper 재사용, `@JsonTypeInfo` 최소화
- **병목:** 스냅샷이 없는 오래된 세션의 전체 리플레이
  - 완화: 세션 종료 시 강제 스냅샷, 조회 요청 시 lazy 스냅샷 생성 옵션

---

## 5. Tombstone 패턴 (EDIT / DELETE)

서비스 관점 차별화: **원본 이벤트를 UPDATE/DELETE 하지 않고, 새 이벤트(`EDIT`, `DELETE`)를 append 해서 tombstone을 표현.**

### 5.1 동작
- `EDIT` 이벤트: `payload = { targetEventId, newText }`, 새로운 sequence
- `DELETE` 이벤트: `payload = { targetEventId, reason }`, 새로운 sequence
- 리플레이 시 `applyEvent`가 기존 메시지 상태를 변경 (`status = EDITED/DELETED`)

### 5.2 이벤트 소싱 정합성
- 원본 `MESSAGE` 이벤트는 **불변** 유지 — 이벤트 소싱 본질 보존
- 복원 시 `EDIT`/`DELETE` 이벤트도 sequence 순서로 반영되어 **시점별 상태 정확 재현**
- "2026-04-21 14:30 시점의 대화" 조회 시 EDIT 이전이면 원문, 이후면 수정본

### 5.3 GDPR / 탈퇴 사용자 대응 (설계 문서용)

**긴장 관계:** 이벤트 소싱의 "불변 append-only" vs GDPR의 "right to be forgotten"

**해결 전략:**
1. **Crypto-shredding**: 사용자별 암호화 키로 payload 암호화 → 탈퇴 시 키 폐기 → 복호화 불가 = 사실상 삭제
2. **Tombstone + payload nullify**: `FORGET_USER` 이벤트를 기록하고, 해당 사용자의 기존 이벤트 payload를 `null`로 업데이트 (원본 메시지만 제거, 메타데이터는 유지)
3. **Crypto-shredding + projection 재구축**: projection 테이블은 재구축으로 반영

본 프로젝트에서는 **설계 문서에만 언급**, 구현은 제외.

---

## 6. 구현 범위

- [x] Snapshot + Replay 하이브리드 **구현**
- [x] Projection 비동기화 **구현** (DB 아웃박스)
- [x] 재연결 resume/replay **구현** (Redis Sorted Set + MySQL fallback)
- [x] 자동 스냅샷 트리거 **구현** (100 이벤트마다 + 세션 종료 시)
- [x] Tombstone 패턴 (EDIT/DELETE) **구현**
- [x] Projection Rebuild API **구현** (`POST /admin/projections/rebuild`)
- [ ] GDPR 대응 — 설계 문서만
