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

### 1.4 Redis 보조 중복 감지 (설계만, 미구현)

> 본 구현은 DB UNIQUE 제약만으로 중복을 차단한다. 아래는 핫패스 빠른 차단을 위한 향후 확장 안.

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
| `sequence <= 0` | **거부** — `EventAppendService`가 `InvalidSequenceException` (422) |
| `sequence > lastSequence` | 정상 |
| `sequence == lastSequence`, `clientEventId` 동일 | 중복, `DUPLICATE_IGNORED` 응답 |
| `sequence == lastSequence`, `clientEventId` 다름 | **충돌** — PK 위반으로 `INVALID_SEQUENCE` 422 |
| `sequence < lastSequence`, 해당 sequence가 비어있음 | **뒤늦게 도착한 이벤트**, 저장 허용 |
| `sequence < lastSequence`, 해당 sequence가 이미 존재 | UNIQUE 제약 위반 → 중복(동일 clientEventId)이면 `DUPLICATE_IGNORED`, 아니면 `INVALID_SEQUENCE` |

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
    # 1. at 시점까지의 최대 sequence 산출 (server_received_at 기준 필터)
    maxSeq = findMaxSequence(sessionId, serverReceivedAtLessThanOrEqual=at)

    if maxSeq is null:
        return emptyState()  # 해당 시각까지 이벤트가 없으면 빈 상태가 결정론적 결과

    # 2. maxSeq 이하인 스냅샷 중 가장 최신 (version DESC) 로드
    #    createdAt이 아니라 lastSequence로 선택해 비동기 스냅샷의 시각 왜곡을 차단
    snapshot = findLatestSnapshot(sessionId, lastSequenceLessThanOrEqual=maxSeq)

    if snapshot is null:
        state = emptyState()
        fromSequence = 0
    else:
        state = deserialize(snapshot.stateJson)
        fromSequence = snapshot.lastSequence

    # 3. 스냅샷 이후부터 at 까지의 이벤트 조회 (sequence ASC 단일 키 정렬)
    events = findEvents(
        sessionId,
        sequenceGreaterThan=fromSequence,
        serverReceivedAtLessThanOrEqual=at,
        orderBy="sequence ASC"
    )

    # 4. 이벤트 순차 적용 (StateEventApplier — 순수 함수)
    for event in events:
        state = applyEvent(state, event)

    return state
```

> 중복 이벤트는 INSERT 시점의 `UNIQUE(session_id, client_event_id)` 제약으로 이미 차단되므로
> 복원 단계에서 별도의 `clientEventId` dedupe는 수행하지 않는다.

### 3.3 스냅샷 생성 자동화

본 프로젝트는 다음 트리거로 스냅샷을 **자동 생성**하며, 수동 호출 API는 제공하지 않는다.

| 트리거 | 조건 | 위치 |
|---|---|---|
| **이벤트 카운트 기반** | `MESSAGE` 누적 카운트가 `app.snapshot.event-threshold`(기본 100)의 배수 도달 | `OutboxPoller` → `SnapshotService.createSnapshotIfNeeded` |
| **세션 종료 기반** | `POST /sessions/{id}/end` 호출 시점 | `SessionService.endSession` → `SnapshotService.createFinalSnapshot` |
| **중복 생성 방지** | 최신 스냅샷의 `lastSequence ≥ session.lastSequence` 이면 스킵 | `createFinalSnapshot` 내부 가드 |

**동작 세부:**
- 카운트는 `JOIN/LEAVE` 등 메타 이벤트를 제외한 **MESSAGE 기준** — 참여만 반복되는 세션에 불필요한 스냅샷 부담을 주지 않음
- `OutboxPoller`가 projection.apply() 직후 동일 트랜잭션에서 호출 → 이벤트와 스냅샷 간 순서 역전 없음
- 세션 종료 스냅샷은 **`REQUIRES_NEW` 트랜잭션**으로 격리 → 스냅샷 실패가 이미 커밋된 종료를 롤백시키지 않음
- **보존 정책**: 세션당 최신 `RETENTION=3` 버전만 유지, 그 외는 native DELETE로 자동 정리

**스냅샷 생성 시나리오 예시:**

| 상황 | 결과 |
|---|---|
| MESSAGE 100개 작성 | 자동 트리거 → v1(lastSeq=100) 생성 |
| MESSAGE 100개 작성 후 즉시 종료 | latest.lastSeq(100) ≥ session.lastSeq(100) → **스킵** |
| MESSAGE 105개 작성 후 종료 | latest.lastSeq(100) < 105 → v2(lastSeq=105) 생성 (잔여 봉인) |
| MESSAGE 30개 작성 후 종료 | latest 없음 → v1(lastSeq=30) 생성 |
| 빈 세션 종료 | 적용할 이벤트 없음 → 생성 안 함 |

### 3.4 결정론 보장 (Determinism)

복원이 결정론적이려면 다음 조건을 모두 만족해야 함:

1. **정렬 기준이 고정**: 복원 단계에서는 `sequence ASC` 단일 키. INSERT 시점에 `UNIQUE(session_id, client_event_id)` 와 sequence 채번이 직렬화되므로 동일 sequence 충돌이 원천 차단됨
2. **중복 이벤트 차단 위치 고정**: 복원이 아니라 **INSERT 시점**에서 UNIQUE 제약으로 차단 → 복원은 단순 정렬+재생만 수행
3. **`applyEvent` 함수가 순수 함수**: `StateEventApplier`는 외부 시간/랜덤 의존 없음
4. **스냅샷 선택 기준 고정**: `createdAt` 이 아닌 **`lastSequence` 기준**으로 후보를 선택해 비동기 스냅샷의 시각 왜곡을 차단 (`EventReplayService:64`)
5. **손상 스냅샷 폴백**: 역직렬화 실패 시 빈 상태로 폴백 → 전체 리플레이로 자동 복구 (결정론 유지)

**테스트로 검증:** `EventReplayServiceIntegrationTest` 에서 동일 `at` 두 번 호출 → 결과 동등성 단언.

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
- 스냅샷 조회: `snapshots.idx_session_last_seq (session_id, last_sequence DESC)` — `last_sequence` 기준 역순 (createdAt 미사용)
- 이벤트 리플레이: PK `(session_id, sequence)` — clustered index 순차 I/O

### 4.2 예상 성능

| 세션 이벤트 수 | 스냅샷 간격 | 평균 리플레이 이벤트 | 예상 p99 |
|---|---|---|---|
| 1,000 | 100 | 50 | <50ms |
| 10,000 | 100 | 50 | <50ms |
| 100,000 | 100 | 50 | <100ms |

### 4.3 병목 및 완화
- **병목:** JSON 역직렬화 (snapshot state) — Jackson `ObjectMapper`를 전용 빈으로 재사용 (`SnapshotObjectMapperConfig`)
- **병목:** 스냅샷이 없는 장기 세션의 전체 리플레이
  - 완화 1: 세션 종료 시 잔여 이벤트가 있으면 봉인 스냅샷 자동 생성 (`createFinalSnapshot`)
  - 완화 2: 운영자는 `POST /admin/projections/rebuild` 로 강제 재계산 가능 (스냅샷 자체의 수동 트리거 API는 없음)
- **병목:** 스냅샷 무한 누적
  - 완화: `RETENTION=3` 으로 세션당 최신 3개만 유지 (`SnapshotService:111`)

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

본 과제에서는 **설계 문서에만 언급**, 구현은 제외.
