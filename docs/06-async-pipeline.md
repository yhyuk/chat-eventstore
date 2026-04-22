# 06. 비동기 파이프라인 (Outbox + Projection + Snapshot + DLQ)

## 1. 파이프라인 전체 흐름

```
[WebSocket Handler]
       │  (TX 시작)
       ▼
[EventAppendService]
   - events INSERT (status=PENDING)
   - sessions.last_sequence UPDATE
       │  (TX commit)
       ▼
[Redis Publisher]  (실시간 전달, projection과 독립)
       │
       ▼
[다른 WebSocket 인스턴스 → 연결된 클라이언트]

(병렬로)

[OutboxPoller @Scheduled fixedDelay=500ms]
       │  SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100
       ▼
[ProjectionService]
   - session_projection UPDATE (last_applied_event_id 기반 멱등)
   - events.status = DONE
       │
       ├─ 실패 시 retry_count++, next_retry_at = now() + backoff
       │
       └─ retry_count > MAX (5) → dead_letter_events 이관
       │
       ▼
[SnapshotService @Scheduled (또는 threshold 기반)]
   - 세션당 이벤트 100개 도달 시 스냅샷 생성
```

## 2. 아웃박스 워커 설계

### 2.1 동작
```java
@Scheduled(fixedDelay = 500)
public void drain() {
    List<EventEntity> batch = eventRepo.fetchPendingForUpdate(BATCH_SIZE);
    for (EventEntity e : batch) {
        try {
            projectionService.apply(e);
            e.setProjectionStatus(DONE);
        } catch (Exception ex) {
            handleFailure(e, ex);
        }
    }
}
```

### 2.2 핵심 쿼리 (Native, SKIP LOCKED)
```sql
SELECT id, session_id, sequence, client_event_id, type, payload, ...
FROM events
WHERE projection_status = 'PENDING'
  AND next_retry_at <= NOW(3)
ORDER BY id ASC
LIMIT :limit
FOR UPDATE SKIP LOCKED
```
- **트랜잭션 경계:** `fetch → apply → status 업데이트`를 동일 트랜잭션 유지 → 락이 자동 해제될 때까지 다른 워커가 못 잡음.
- **SKIP LOCKED**: 여러 워커 인스턴스가 경합 없이 서로 다른 row 처리.

### 2.3 멱등성 보장
- `session_projection.last_applied_event_id` 비교:
  ```sql
  UPDATE session_projection
  SET ... , last_applied_event_id = :eventId
  WHERE session_id = :sid AND last_applied_event_id < :eventId
  ```
- 이미 적용된 이벤트는 `WHERE` 조건으로 스킵.

### 2.4 재시도 정책 (지수 백오프)
| retry_count | next_retry_at delay |
|---|---|
| 0 | 즉시 |
| 1 | 2초 |
| 2 | 4초 |
| 3 | 8초 |
| 4 | 16초 |
| 5 → DLQ | — |

**최대 재시도 5회**, 6회째 실패 시 `dead_letter_events` 이관.

### 2.5 동시성
- 워커 여러 인스턴스 실행: Spring Boot 서버 2대 × 기본 1 스레드 = 2 워커
- `SKIP LOCKED` 덕분에 row-level 경합 없음
- 배치 크기 100으로 제한 → 장애 시 재처리 부담 제한

## 3. Dead Letter Queue (DLQ)

### 3.1 이관 시점
- `retry_count >= MAX_RETRY (5)` 도달 시
- 명시적 "재생 불가" 예외 (예: 역직렬화 불가능한 payload)

### 3.2 DLQ 운영 API (가산점)
- `GET /admin/dlq` — 미처리 건 조회
- `POST /admin/dlq/{id}/retry` — 수동 재처리 (events로 복원)
- `DELETE /admin/dlq/{id}` — 영구 폐기
- 관리자 전용 인증은 과제 Non-goals라 생략, 설계 문서에만 언급

### 3.3 메트릭
- `chat.projection.dead_letter.total{reason}` — 카운터
- Grafana 경고 룰: 1시간 내 > 0 → Slack/email 알림 (문서로만 기술)

## 4. Snapshot 생성

### 4.1 트리거
- **자동 (주기형):** 세션당 이벤트 100개마다
  - `session_projection.message_count % 100 == 0` 확인 후 생성
- **자동 (종료 시):** 세션 `status = ENDED` 전환 시 최종 스냅샷
- **수동:** `POST /sessions/{id}/snapshots`

### 4.2 생성 절차
```
1. 현재 session_projection.last_applied_event_id 기준으로 상태 재구성
   (가장 최근 snapshot 로드 후 이후 이벤트 리플레이)
2. snapshot 객체 직렬화 → JSON
3. INSERT INTO snapshots (session_id, version, last_event_id, last_sequence, state_json)
4. 오래된 스냅샷 정리 (세션당 최근 3개 유지)
```

### 4.3 결정론성 보장
- Snapshot 생성도 `restoreAt(sessionId, at=now)` 알고리즘을 재사용
- 동일 이벤트 스트림 → 동일 스냅샷 결과 (유닛 테스트 검증)

## 5. Idempotency Key 정리

| 레벨 | 키 | 구현 |
|---|---|---|
| 이벤트 수집 | `(session_id, client_event_id)` | DB UNIQUE |
| Projection 적용 | `last_applied_event_id` | UPDATE WHERE 조건 |
| Redis publish | 허용 at-least-once | 클라이언트측 dedupe (`clientEventId`) |
| 스냅샷 생성 | `(session_id, version)` | PK 충돌 시 skip |

## 6. 메트릭 (관측)

| 메트릭 | 타입 | 설명 |
|---|---|---|
| `chat.outbox.pending.size` | Gauge | 현재 PENDING 이벤트 수 |
| `chat.outbox.processed.total` | Counter | 성공 처리 누계 |
| `chat.outbox.failed.total{reason}` | Counter | 실패 누계 |
| `chat.outbox.dead_letter.total` | Counter | DLQ 이관 누계 |
| `chat.outbox.lag.seconds` | Gauge | 가장 오래된 PENDING 이벤트의 serverReceivedAt과 now 차이 |
| `chat.projection.apply.duration` | Histogram | 단일 이벤트 적용 시간 |
| `chat.snapshot.created.total` | Counter | 스냅샷 생성 누계 |

## 7. 장애 격리

- 아웃박스 파이프라인 정체 ≠ 실시간 전달 중단 (Redis Pub/Sub 경로 독립)
- Redis 장애 ≠ 이벤트 저장 중단 (DB 저장은 성공, publish만 fallback)
- DB 장애 → 전체 영향, 단일 장애 도메인

## 8. Projection Rebuild (운영용)

### 8.1 필요성
- projection 로직 버그 수정 후 기존 데이터 재계산 필요
- 이벤트 소싱의 핵심 이점 — "이벤트가 진실의 원천, projection은 언제든 재계산 가능"

### 8.2 API
- `POST /admin/projections/rebuild?sessionId={id}`
  - 특정 세션의 `session_projection` row를 지우고 처음부터 이벤트 리플레이로 재구성
- `POST /admin/projections/rebuild-all`
  - 전체 재구축 (운영자 수동 실행 가정, 비동기)

### 8.3 구현 플로우
```
1. session_projection 기존 row 삭제 (또는 초기화)
2. events 테이블에서 session_id 조건으로 전체 이벤트 조회 (sequence 정렬)
3. empty state로 시작, applyEvent를 순차 실행
4. 최종 projection INSERT
5. 필요 시 snapshots 재생성
```

### 8.4 안전장치
- 진행 중 세션은 기본 거부 (ACTIVE 세션 재구축 시 경합 가능 → lock + 점검 모드 필요)
- 단일 세션만 타겟, 전체 재구축은 관리자 수동 트리거
- 처리 진행률 메트릭 노출: `chat.projection.rebuild.progress`

## 9. Rate Limiting / Backpressure (서비스 관점 설계 문서용)

### 9.1 필요성
- 악의적/오류 클라이언트의 이벤트 폭주 방지
- 서비스 전체 안정성 보호

### 9.2 제안 설계 (구현 없이 설계 문서에만)
- **세션당 rate limit**: Redis `INCR session:{id}:rate:{sec}` + `EXPIRE 1`
- **사용자당 rate limit**: `INCR user:{id}:rate:{sec}`
- 한도 초과 시 `ERROR` 프레임 + 일시 차단 (TTL 5초)
- **토큰 버킷 또는 leaky bucket** 알고리즘 후일 도입

### 9.3 구현 범위 (본 과제)
- 본 과제에서는 **문서에만 제안**, 실제 구현 생략 (일정 우선순위)

## 10. 확장 경로 (문서용)

현재 구현이 트래픽 한계에 도달하면 (이벤트/초 1K 이상):
1. **Kafka 전환**: `events` INSERT 후 Kafka producer로 topic publish, 별도 consumer group이 projection 처리
2. **Marzullo 알고리즘 또는 hybrid logical clock**: 다중 서버 동시 쓰기에서 sequence 충돌 방지
3. **읽기 전용 replica**: 복원 쿼리를 read replica로 분리

### 10.1 Hot/Warm/Cold 티어링 (서비스 관점)
- **Hot (최근 7일)**: MySQL `events` 테이블
- **Warm (7일~90일)**: MySQL archive 테이블 또는 S3 Parquet
- **Cold (90일+)**: S3 Glacier / 압축 저장
- 복원 요청이 warm/cold 영역을 참조하면 lazy load + 캐시
- 본 과제에서는 설계 문서 섹션으로만 제시, 구현 생략
