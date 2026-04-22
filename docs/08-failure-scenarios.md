# 09. 장애 대응 시나리오 (감지 → 완화 → 복구)

장애 대응 관점에서 3가지 시나리오를 기술.

## 1. 서버 다운 (Spring Boot 인스턴스 장애)

### 감지
- **Prometheus up-check**: `up{job="chat-eventstore"} == 0` 1분 지속
- **로드 밸런서 헬스체크 실패**: `/actuator/health/liveness` 연속 실패
- **Grafana alert**: WebSocket 활성 세션 수 급감 → Slack/email (문서 제안)

### 영향 범위
- 해당 인스턴스에 연결된 WebSocket 클라이언트 즉시 단절
- 다른 인스턴스는 정상 (무상태 설계)
- 처리 중이던 아웃박스 배치는 트랜잭션 rollback → 다음 워커가 SKIP LOCKED 재획득

### 완화
- **로드 밸런서가 즉시 트래픽 제거**: LB 헬스체크 failure threshold 3회 × 5초 = 15초 내 격리
- **클라이언트 재연결 로직**: 지수 백오프 (1s → 2s → 4s → 8s)로 다른 인스턴스에 연결
  - `lastSequence` 파라미터로 놓친 이벤트 resume
- **세션 상태는 DB/Redis에 보존** → 클라이언트가 다른 인스턴스로 재연결 시 상태 복원 가능

### 복구
1. 인스턴스 재시작 (Kubernetes / Docker compose restart)
2. Flyway가 스키마 검증, 마이그레이션 필요 시 실행
3. 아웃박스 워커 재개 → `FOR UPDATE SKIP LOCKED`로 미처리 이벤트 이어서 처리
4. 복구 완료 후 LB 헬스체크 통과 → 트래픽 재유입
5. Prometheus `up` 메트릭 정상화 확인

### 데이터 정합성 보장 요소
- 이벤트는 커밋된 트랜잭션만 반영 → 인스턴스 다운으로 "부분 처리된" 이벤트는 존재하지 않음
- 아웃박스 `projection_status`가 `PROCESSING`으로 남아있는 경우 없음 (락이 풀리면 `PENDING`으로 유지)

### 관측 포인트
- `chat_websocket_sessions_active` 인스턴스별 그래프 (분리 표시)
- `up{instance=...}` 알림

---

## 2. DB 장애 (MySQL 성능 저하 / 커넥션 고갈 / 락 경합)

### 감지
- **HikariCP 메트릭**: `hikaricp_connections_active / max` > 0.9 지속
- **slow query**: `mysql_global_status_slow_queries` 급증
- **락 대기**: `mysql_global_status_innodb_row_lock_waits` 급증
- **애플리케이션 에러율**: 500 에러 비율 급증
- **헬스체크**: `/actuator/health/db` DOWN

### 영향 범위
- 이벤트 INSERT 지연/실패 → WebSocket 핸들러가 타임아웃
- 아웃박스 워커 동작 중단 → projection lag 증가
- 복원 API 응답 지연

### 완화
- **HikariCP 설정**: `connection-timeout=2000ms`, `maximum-pool-size=20` → 빠른 실패 유도 (cascading 방지)
- **Circuit Breaker** (Resilience4j 설계 문서 제안):
  - DB 쓰기 5xx 연속 10회 감지 시 open
  - open 상태에서 이벤트 INSERT 일시 거부 → 클라이언트에 `503 DEGRADED` 반환
  - half-open 상태에서 점진 복구
- **읽기 경로 임시 degradation**:
  - 복원 API가 DB 조회 실패 시 Redis 캐시만 반환 (최근 N개)
- **Redis 쓰기 계속 동작**: WebSocket 브로드캐스트 경로 유지 → 실시간 UX 최대한 보존
- **이벤트 저장 실패 시 클라이언트 재시도 유도**: `ACK`에 `status: RETRY_LATER` 플래그

### 복구
1. DBA 개입 또는 자동 복구
   - 커넥션 고갈: 장시간 커넥션 holder 식별 (`information_schema.processlist`) → kill
   - 락 경합: 슬로우 트랜잭션 확인, 인덱스 보강
2. `/actuator/health/db` UP 전환
3. Circuit breaker half-open → closed 전환
4. 아웃박스 워커가 `PENDING` + `FAILED` 이벤트 재처리 (지수 백오프 자동 동작)
5. 리플레이로 유실 검증: 각 세션의 `last_sequence`와 `session_projection.last_applied_event_id` 일치 확인

### 락 경합 특화 완화
- `events` 테이블 INSERT는 PK = `(session_id, sequence)` 기반 clustered → 같은 세션 동시 쓰기만 충돌
- 아웃박스 `SELECT FOR UPDATE SKIP LOCKED`는 행 단위 락 → 전체 테이블 락 없음
- 장애 시 `SHOW ENGINE INNODB STATUS`로 락 graph 확인

### 관측 포인트
- `hikaricp_connections_active`
- `mysql_global_status_innodb_row_lock_waits`
- `http_server_requests_seconds{status="5xx"}` 비율

---

## 3. 데이터 유실 / 정합성 이슈 (중복 저장 / 부분 실패)

### 감지
- **중복 저장 감지**:
  - 로그에서 `event.duplicate` warning 급증
  - `chat_events_duplicates_total` 메트릭 비율 > 평상시 3배
- **정합성 체크 배치** (설계 제안):
  - 세션별 `MAX(events.sequence) == sessions.last_sequence` 검증
  - `session_projection.message_count == COUNT(events WHERE type=MESSAGE)` 검증
  - 불일치 발견 시 알림
- **사용자 신고**: 수정/삭제 이벤트 반영 안 됨

### 영향 범위
- 중복: UNIQUE 제약으로 이미 차단 (가장 흔한 경로) — 새 중복이 발생한다면 제약 설정 자체 오류
- projection 불일치: 복원 API 결과가 실제와 다름
- 이벤트 유실: 복원 시 참여자/메시지 누락

### 완화
- **UNIQUE 제약 우선**: DB 레벨에서 중복 저장 원천 차단 (이미 구현)
- **멱등 재시도**: 아웃박스 워커는 `last_applied_event_id` 비교로 중복 apply 방지
- **부분 실패 시 트랜잭션 rollback**: 이벤트 insert와 sessions.last_sequence UPDATE는 같은 트랜잭션
- **Redis ↔ DB 불일치**:
  - Redis는 fallback 목적이므로 불일치 시 DB를 진실의 원천으로 재동기화
  - `scripts/sync-redis.sh` 로 특정 세션의 최근 N개 캐시를 DB 기준으로 재생성

### 복구
1. **불일치 범위 식별**:
   - 영향받은 sessionId 목록 수집 (정합성 체크 배치 결과)
2. **Projection Rebuild API 호출**: `POST /admin/projections/rebuild?sessionId=X`
   - events 테이블(진실의 원천)에서 projection 재계산
3. **스냅샷 무효화**: 필요 시 해당 세션의 snapshots 삭제 → lazy 재생성
4. **Redis 캐시 무효화**: `DEL session:{id}:recent` → 다음 접근 시 DB에서 재적재
5. **근본 원인 분석 및 수정**: 로그/trace로 원인 이벤트 ID 식별, 코드 수정 후 배포

### 데이터 유실 방지 원칙
- 이벤트는 **DB 커밋 후 Redis publish** — publish 실패해도 DB에는 남음
- 클라이언트가 ACK 받기 전까지 로컬 outbox 유지 → 서버 장애 시 재전송
- **at-least-once delivery** 보장, 서버측 멱등성으로 중복 허용

### 운영 도구
- `POST /admin/projections/rebuild` (구현 범위 포함)
- `GET /admin/dlq` (DLQ 조회)
- `POST /admin/dlq/{id}/retry` (수동 재처리)

### 관측 포인트
- `chat_events_duplicates_total` 비율
- `chat_outbox_dead_letter_total` 건수
- projection lag: `chat_outbox_lag_seconds`

---

## 4. 종합 대응 플레이북 (요약)

| 증상 | 즉시 조치 | 근본 복구 |
|---|---|---|
| 인스턴스 down | LB에서 제거, 재시작 | 로그/덤프 분석, 리소스 증설 |
| DB 커넥션 고갈 | long-running 쿼리 kill, pool 확대 | 인덱스/쿼리 튜닝, 샤딩 검토 |
| DB 락 경합 | 장기 트랜잭션 kill | 트랜잭션 경계 축소, 인덱스 보강 |
| 중복 저장 급증 | 중복 로그 분석 | 제약 설정 점검, 클라이언트 재전송 로직 검토 |
| Projection 불일치 | Rebuild API 호출 | projection 로직 수정 + 재배포 |
| Redis down | fallback 경로 확인 (DB 직접 조회) | Redis 재시작/교체, 캐시 워밍업 |

## 5. 평상시 대비

- **합성 모니터링**: `scripts/synthetic-check.sh` 5분마다 실행 (세션 생성 → 이벤트 1개 → 복원 → 삭제)
- **무중단 배포**: blue-green 또는 rolling (앱 무상태 + 아웃박스가 멱등이라 안전)
- **리허설**: 장애 주입 테스트 (Toxiproxy로 DB 지연 시뮬레이션) — 구현은 설계 문서에만

## 6. 서비스 관점 보완 (구현 미포함)

- **자동 페일오버**: MySQL Primary-Replica, Redis Sentinel
- **백업/복구**: 하루 1회 MySQL `mysqldump` S3 업로드, Point-In-Time Recovery 문서화
- **재해 복구(DR)**: 다른 리전 read replica, RPO 15분 / RTO 30분 목표 제안
