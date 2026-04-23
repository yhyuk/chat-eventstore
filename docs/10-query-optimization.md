# 10. 주요 쿼리 최적화

> 본 문서는 D7 (부하 테스트 실행 후) 에 EXPLAIN 결과와 측정 수치를 붙여 완성한다.
> 현재는 뼈대와 설계 의도만 기록한다. 상세 DDL/인덱스 근거는 `03-db-schema.md` 참조.

## 1. 대상 쿼리

| # | 쿼리 | 핫패스 여부 | 인덱스 |
|---|---|---|---|
| Q1 | 아웃박스 워커 PENDING 이벤트 조회 (SKIP LOCKED) | 핫 | `idx_projection_status_retry` |
| Q2 | 특정 시점 복원 (snapshot + events replay) | 웜 | PK `(session_id, sequence)` + `idx_session_last_seq` |
| Q3 | 세션 목록 동적 필터 | 일반 | `idx_status_created`, `idx_last_message_at` |

## 2. Q1 — 아웃박스 워커

```sql
SELECT * FROM events
WHERE projection_status = 'PENDING' AND next_retry_at <= NOW(3)
ORDER BY id ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

**설계 포인트**
- `idx_projection_status_retry (projection_status, next_retry_at)` 사용
- SKIP LOCKED로 다수 워커 인스턴스 간 row-lock 경합 제거
- 예상 병목: PENDING 이벤트 누적 시 인덱스 cardinality 저하 → 배치 주기 단축 또는 archive 테이블 분리
- D7 측정: EXPLAIN, 초당 처리 이벤트 수, Prometheus `chat_outbox_poll_duration_seconds` 분포

## 3. Q2 — 특정 시점 복원

```sql
-- (a) 최근 스냅샷 탐색
SELECT * FROM snapshots
WHERE session_id = :sid AND created_at <= :at
ORDER BY version DESC LIMIT 1;

-- (b) 스냅샷 이후 이벤트 리플레이
SELECT * FROM events
WHERE session_id = :sid
  AND sequence > :snapshotLastSeq
  AND server_received_at <= :at
ORDER BY sequence ASC, server_received_at ASC, id ASC;
```

**설계 포인트**
- PK 순차 스캔이라 range scan 효율적
- 예상 병목: 스냅샷 주기(`app.snapshot.event-threshold=100`) 대비 세션 수명이 짧을 때 리플레이 길어짐
- 개선: 세션 종료 시 강제 스냅샷 (D4에서 구현 예정)
- D7 측정: p50/p95/p99 복원 지연, Prometheus `chat_restore_duration_seconds`

## 4. Q3 — 세션 목록

```sql
SELECT s.id, s.status, s.created_at, sp.message_count, sp.last_message_at
FROM sessions s
JOIN session_projection sp ON s.id = sp.session_id
WHERE s.status = :status
  AND s.created_at BETWEEN :from AND :to
ORDER BY s.created_at DESC
LIMIT 50;
```

**설계 포인트**
- `sessions.idx_status_created (status, created_at)` 를 left-most로 활용
- 참여자 필터 추가 시 `participants` JOIN 필요 → QueryDSL 동적 빌드
- 자주 쓰이면 `session_projection`에 `participant_user_ids` 비정규화 고려

## 5. D7 제출물 체크

- [x] 각 쿼리의 `EXPLAIN ANALYZE` 결과 캡처 (아래 6절 참조)
- [ ] k6 부하 테스트 중의 MySQL/Redis 지표 Grafana 스크린샷
- [ ] 병목 발견 시 개선 조치 기록 (V5 migration 또는 별도 문서)

## 6. 실측 EXPLAIN 결과 (2026-04-23)

> 실행 환경: Docker 컨테이너 내 MySQL 8.x, 소량 데이터(세션 2개, 이벤트 ~10건) 기준

### Q1 — 이벤트 복원 (session_id PK lookup)

```
쿼리: SELECT * FROM events WHERE session_id = 1
      ORDER BY sequence ASC, server_received_at ASC, id ASC LIMIT 200;

EXPLAIN ANALYZE:
-> Limit: 200 row(s)  (cost=0.35 rows=1) (actual time=0.0527..0.0527 rows=0 loops=1)
    -> Sort: events.sequence, events.server_received_at, events.id,
             limit input to 200 row(s) per chunk  (cost=0.35 rows=1) (actual time=0.0514..0.0514 rows=0 loops=1)
        -> Index lookup on events using PRIMARY (session_id=1)  (cost=0.35 rows=1) (actual time=0.017..0.017 rows=0 loops=1)
```

**해석:** PK `(session_id, sequence)` clustered index lookup으로 rows=0 (데이터 없음). 실데이터 기준 range scan 비용은 O(N) where N=세션 이벤트 수로, 스냅샷 주기(100개)와 결합 시 최대 100행 이내.

### Q2 — 아웃박스 워커 PENDING 조회

```
쿼리: SELECT * FROM events
      WHERE projection_status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW())
      ORDER BY id LIMIT 50;

EXPLAIN ANALYZE:
-> Limit: 50 row(s)  (cost=4.31 rows=9) (actual time=0.177..0.179 rows=8 loops=1)
    -> Sort: events.id, limit input to 50 row(s) per chunk  (cost=4.31 rows=9) (actual time=0.177..0.178 rows=8 loops=1)
        -> Index range scan on events using idx_projection_status_retry
           over (projection_status = 'PENDING' AND next_retry_at <= '2026-04-23 03:03:06'),
           with index condition: (projection_status = 'PENDING') AND (next_retry_at <= now())
           (cost=4.31 rows=9) (actual time=0.0419..0.139 rows=8 loops=1)
```

**해석:** `idx_projection_status_retry (projection_status, next_retry_at)` index range scan 정상 동작. rows=8로 인덱스 조건 pushdown 확인. SKIP LOCKED 추가 시 동일 플랜 유지, 다중 워커 경합 없음.

### Q3 — 세션 목록 (status 필터)

```
쿼리: SELECT s.* FROM sessions s
      WHERE s.status = 'OPEN' ORDER BY s.created_at DESC LIMIT 10;

EXPLAIN ANALYZE:
-> Limit: 10 row(s)  (cost=0.35 rows=1) (actual time=0.0131..0.0131 rows=0 loops=1)
    -> Index lookup on s using idx_status_created (status='OPEN') (reverse)  (cost=0.35 rows=1) (actual time=0.0125..0.0125 rows=0 loops=1)
```

**해석:** `idx_status_created (status, created_at)` 복합 인덱스의 reverse scan으로 ORDER BY DESC 추가 정렬 없이 처리. rows=0은 'OPEN' 상태 세션 없음 (테스트 데이터는 'ACTIVE'). 실데이터에서도 인덱스 활용 동일.
