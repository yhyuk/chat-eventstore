# chat-eventstore — 프로젝트 개요 및 기술 선택 의사결정 (ADR)

## 1. 프로젝트 배경

**1:1 실시간 채팅 + 이벤트 기반 상태 복원** 서비스 설계 및 구현.

핵심 주제:
- 실시간 통신 구현 (WebSocket)
- 이벤트 소싱 기반 특정 시점 상태 복원(Determinism)
- 중복 이벤트 / 순서 뒤바뀜 처리 전략
- 수평 확장 / 관측 가능성 / 장애 대응 설계
- DB 설계, 쿼리 최적화, REST API 설계

---

## 2. 확정 기술 스택 요약

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어 | **Java 21 (LTS)** | Virtual Threads 활용 |
| 프레임워크 | **Spring Boot 3.3.x** | |
| 빌드 툴 | **Gradle (Groovy DSL)** | |
| 실시간 통신 | **순수 WebSocket (`WebSocketHandler`)** | STOMP 미사용 |
| 주 DB | **MySQL 8.x** | source of truth |
| 보조 저장소 | **Redis** | Pub/Sub, Presence, 최근 N개 캐시 |
| ORM | **Spring Data JPA + QueryDSL** | Native SQL은 SKIP LOCKED 1곳만 |
| 마이그레이션 | **Flyway** | DDL 버전 관리 |
| 비동기 파이프라인 | **DB 아웃박스 + `@Scheduled` 워커** | SKIP LOCKED 기반 |
| 컨테이너화 | **Docker Compose** | 앱 2대 + 인프라 |
| 메트릭 | **Micrometer + Prometheus** | |
| 시각화 | **Grafana** | 대시보드 3종 |
| 로깅 | **Logback JSON + MDC** | traceId/spanId 주입 |
| 추적 | **Micrometer Tracing + OpenTelemetry + Zipkin** | 분산 추적 |
| 테스트 | **JUnit 5 + Testcontainers + k6** | 단위/통합/부하 |
| 인증 | **쿼리 파라미터 기반 최소 식별** | Non-goals 일치 |
| 아키텍처 | **단일 모듈 + 도메인별 패키지** | |
| 문서 언어 | **한국어 (OpenAPI 영어)** | |

---

## 3. ADR — 주요 기술 선택의 근거와 트레이드오프

### ADR-001. 실시간 통신 프로토콜

**결정:** 순수 WebSocket (`WebSocketHandler`), STOMP 미사용

**대안 비교:**
| 옵션 | 장점 | 단점 |
|---|---|---|
| STOMP | Spring 기본 지원 강력, 1:1 채팅 표준 | 프레임 규약이 중복/순서 커스텀 스키마 설계에 제약 |
| **순수 WebSocket** | 이벤트 스키마 자유 설계 (`clientEventId`, `sequence`) | Pub/Sub 직접 구현 필요 |
| SSE + REST | 구현 단순 | 양방향 실시간 아님, 채팅 전형 패턴 아님 |

**근거:** 핵심 주제가 **중복/순서/복원**이며, 이를 자체 이벤트 스키마(`clientEventId`, `sequence`, `clientTimestamp`)로 명시적으로 설계할 수 있어야 함. STOMP의 기본 프레임 위에 얹는 것보다 직접 제어 가능한 순수 WebSocket이 설계 자유도를 확보.

**결과:**
- 메시지 프레임 포맷을 요구사항에 맞춤 설계
- Pub/Sub은 Redis로 보강 (ADR-005 참고)

---

### ADR-002. 주 데이터 저장소

**결정:** MySQL 8.x

**대안 비교:**

| 항목 | PostgreSQL | **MySQL 8.x** | MariaDB |
|---|---|---|---|
| JSON 처리 | JSONB+GIN 우수 | JSON + Functional Index | MySQL과 유사 |
| 비동기 워커 (SKIP LOCKED) | 우수 | 지원 (8.0+) | 지원 |
| 이벤트 리플레이 (PK clustered) | 보통 | 우수 (InnoDB clustered index) | 우수 |
| 운영 친숙도 | 보통 | **높음** | 낮음 |
| 이벤트 소싱 레퍼런스 | 풍부 | 보통 | 적음 |
| Temporal Tables | 없음 | 없음 | 있음 (본 과제에는 부적합) |

**근거:**
1. **익숙한 스택으로 깊이 있는 설계 증명** — AI 활용 구현이지만 평가 인터뷰/리뷰에서 본인 언어로 설명할 수 있는 것이 중요.
2. MySQL 8.0에도 `SELECT ... FOR UPDATE SKIP LOCKED`, JSON + Functional Index, CTE/Window 함수가 모두 있어 본 프로젝트 구현에 부족함 없음.
3. `(session_id, sequence)` 기반 PK로 InnoDB clustered index를 활용하면 세션별 이벤트 순차 조회가 순차 I/O가 되어 복원 핫패스 성능 우수.
4. PostgreSQL JSONB의 이점은 이벤트 payload 스키마가 매우 동적인 경우인데, 본 과제의 이벤트 타입은 5개 내외로 제한적 → JSON 타입으로 충분.

**Temporal Tables 배제 사유 (MariaDB):**
- 본 프로젝트의 본질은 Event Sourcing **정공법** 검증(이벤트 리플레이).
- Temporal Tables는 CRUD + 히스토리 모델로 "패러다임 우회" 인상을 줌.
- 단, 설계 문서의 "대안 검토" 섹션에 언급해 기술 선택지 폭을 보임.

---

### ADR-003. RDBMS 접근 방식 (ORM)

**결정:** Spring Data JPA + QueryDSL (+ Native 1곳)

**대안 비교:**

| 항목 | **JPA + QueryDSL** | MyBatis |
|---|---|---|
| 이벤트 append | 우수 (`save`) | 보통 (XML 필요) |
| 리플레이 조회 | 우수 (메서드 이름 규약) | 보통 |
| 동적 조건 필터 | 우수 (QueryDSL 타입 안전) | 보통 (`<if>`) |
| SKIP LOCKED | 가능 (`@Query nativeQuery`) | 자연스러움 |
| 복잡 조인/집계 | 보통 | 우수 |
| 유지보수 IDE 지원 | 강함 | 약함 |
| 이벤트 소싱 궁합 | 우수 (append 중심) | 보통 |

**근거:**
1. 이벤트 소싱은 **append-only + 단일 테이블 순차 조회**가 지배적 → 복잡 조인 거의 없음 → JPA가 강점 영역.
2. 일주일 기간에서 MyBatis의 XML 보일러플레이트(Mapper + XML + DTO)는 개발 시간 누적 부담.
3. 본인 경험 있음, AI 활용 시 JPA 레퍼런스 압도적으로 많음.

**결과:**
- JPA 주력 (~90%)
- QueryDSL로 세션 목록 동적 필터 (~8%)
- Native SQL은 `SKIP LOCKED` 한 군데만 (~1~2%) — 설계 문서에 명시

---

### ADR-004. 비동기 Projection / Snapshot 파이프라인

**결정:** DB 아웃박스 + `@Scheduled` 워커 (SKIP LOCKED)

**대안 비교:**

| 항목 | **DB 아웃박스** | Redis Streams | Kafka |
|---|---|---|---|
| 구현 복잡도 | 낮음 | 중간 | 높음 |
| 이중 쓰기 일관성 | 자동 (트랜잭션) | 별도 전략 필요 | 아웃박스 + 릴레이 필요 |
| 인프라 추가 | 없음 | Redis (이미 도입) | Kafka+Zookeeper |
| 장애 격리 | 우수 (DB 단일 도메인) | 보통 | 보통 |
| 설계 문서 설득력 | 높음 (트랜잭셔널 아웃박스 정석) | 중간 | 높지만 과대 |
| 실시간성 (체감) | 동일 (WebSocket 직접 전달) | 동일 | 동일 |
| 일주일 ROI | 최고 | 중간 | 낮음 |

**근거:**
1. 이벤트 insert와 projection 큐 enqueue가 **같은 트랜잭션** → 이중 쓰기 문제 원천 차단.
2. 비동기 파이프라인의 재시도/DLQ/Idempotency/중복 실행 방지를 **DB 테이블 2개(`events`, `dead_letter_events`)로 가시화** — ERD + SQL로 설명 용이.
3. MySQL 8.0의 `FOR UPDATE SKIP LOCKED`로 여러 워커가 경합 없이 병렬 처리 가능.
4. Redis Streams는 실무 경험 있으나 **source of truth가 DB인 본 프로젝트 맥락에선 이중 쓰기 리스크 증가** → 채택 시 설계 문서가 오히려 장황해짐.
5. Kafka는 본인 미경험 + 일주일 ROI 낮음.

**결과:**
- `events.projection_status` 컬럼으로 미처리/처리중/완료/실패 관리
- `retry_count`, `next_retry_at` 으로 지수 백오프
- 임계 초과 시 `dead_letter_events` 이관
- 실시간 UX는 WebSocket 직접 브로드캐스트가 담당 (projection은 상태 저장/복원용)

**확장 경로 (문서에 기술):**
> 이벤트/초 1K 초과 또는 다중 consumer group 필요 시 Redis Streams 또는 Kafka로 마이그레이션. 이행 경로는 장애 시나리오 문서 참고.

---

### ADR-005. Redis 도입 여부 및 사용 범위

**결정:** MySQL + Redis 조합, Redis 사용처 3곳으로 한정

**대안 비교:**

| 항목 | MySQL 단독 | **MySQL + Redis** |
|---|---|---|
| 인프라 단순성 | 우수 | 중간 |
| 수평 확장 시연 | 설계 문서로만 | 실제 구현 가능 |
| Presence | DB UPDATE 부담 | TTL 자연스러움 |
| 최근 N개 캐시 | 매번 DB 조회 | Sorted Set O(log N) |
| 관측 가능성 | Redis 메트릭 없음 | redis-exporter 포함 대시보드 풍부 |
| 일주일 ROI | 중간 | 높음 |

**Redis 사용처 (3곳으로 제한 — YAGNI):**
1. **Pub/Sub**: 서버 A에 연결된 Alice가 보낸 메시지를 서버 B에 연결된 Bob에게 전달 (`session:{id}` 채널)
2. **Presence**: `SET user:{id}:online EX 30` + heartbeat → TTL 만료로 자동 offline 처리
3. **최근 N개 메시지 캐시**: Sorted Set `session:{id}:recent`, score는 `sequence`, 재연결 시 빠른 전달

**근거:**
1. **"서버 수평 확장 시 세션 분산 및 상태 저장 전략"** 이 핵심 설계 주제 — 문서만 쓰기보다 구현으로 답하는 편이 설득력 압도적.
2. 본인 실무 경험 있음, 러닝 커브 없음.
3. Redis 장애 대응 시나리오가 장애 대응 문서의 **좋은 소재** 제공.
4. Prometheus/Grafana 대시보드가 풍성해져 "운영 대시보드" 요구를 직접 충족.

---

### ADR-006. 관측 가능성 스택 범위

**결정:** 메트릭 + 로그 + 추적 풀셋 (Prometheus + Grafana + Zipkin)

**대안 비교:**
| 옵션 | 구성 | 소요 | 커버리지 |
|---|---|---|---|
| A: 메트릭 중심 | Prometheus + Grafana + JSON 로그 | ~1일 | 중간 |
| **B: 풀셋** | A + OpenTelemetry + Zipkin | ~2일 | 높음 |
| C: LGTM 스택 | B + Loki + Tempo | ~3일 | 최고 (과투자) |

**근거:**
1. **"관측 가능성 설계(로그, 메트릭, 추적)"** 3요소 전부 구현.
2. Option A는 실무 경험 있음 → Option B의 **추적 부분이 학습 포인트**.
3. WebSocket 이벤트 수신 → projection 반영까지 trace로 추적 가능 → 이벤트 소싱 디버깅 실효성 입증.
4. Zipkin은 Tempo 대비 Docker 구성 경량 → 제한된 기간 내에 안정적.

**결과:**
- Micrometer + Prometheus → 커스텀 메트릭 7종 이상
- Logback JSON + MDC (traceId/spanId/sessionId/eventId)
- Micrometer Tracing + OpenTelemetry → Zipkin
- Grafana 대시보드 3종 (App / MySQL / Redis)

---

### ADR-007. 프로젝트 모듈 구조

**결정:** 단일 Gradle 모듈 + 도메인별 패키지 분리

**대안 비교:**
| 옵션 | 장점 | 단점 |
|---|---|---|
| **단일 모듈 + 레이어드** | 최속 개발, 코드 탐색 쉬움 | 경계 강제 없음 |
| 헥사고날 | 포트/어댑터 명확 | 클래스 수 증가, 오버엔지니어링 |
| 멀티 모듈 | 경계 강제 | 일주일 과투자 |

**근거:** 제한된 개발 기간 내에 코드 리뷰 친화성을 확보하는 데 최적. 헥사고날/멀티모듈은 설계 문서의 "확장 경로"로 언급.

**구조:**
```
src/main/java/com/example/chat/
├── session/        # 세션 도메인
├── event/          # 이벤트 수집/저장
├── projection/     # 아웃박스 워커, projection 서비스
├── realtime/       # WebSocket 핸들러, Redis Pub/Sub
├── restore/        # 상태 복원 API
├── common/         # 공통 유틸, 예외, 설정
└── observability/  # 메트릭, 로깅, 추적 설정
```

---

### ADR-008. 인증/식별 방식

**결정:** 쿼리 파라미터 기반 최소 식별 (`?sessionId=X&userId=Y`)

**근거:** Non-goals에 "인증/인가 체계의 완결된 구현"이 포함됨 → 의도 준수. 설계 문서에 JWT/OAuth2 확장 경로 명시.

**결과:** `HandshakeInterceptor`에서 파라미터 추출 → `WebSocketSession.attributes` 저장.

---

### ADR-009. 테스트 전략

**결정:** 단위 + 통합(Testcontainers) + k6 부하 테스트

**근거:** 통합 테스트와 부하 테스트 결과를 산출물에 포함. 장애 주입은 설계 문서로 대체.

**범위:**
- **단위**: `EventReplayService` 결정론성, 중복/순서 필터링
- **통합 (Testcontainers, MySQL + Redis)**: 세션 라이프사이클, 중복 이벤트, 순서 역전, 복원 결정론성
- **재현 스크립트**: `scripts/reproduce.sh` + `http/*.http`
- **부하 (k6)**: 10세션 × 2유저 × 10msg/s × 60초 → Grafana에 결과 반영

---

## 4. 문서 작성 규칙

- 설계 문서, ADR, 장애 시나리오, README → **한국어**
- OpenAPI 스펙 → **영어** (표준 관례)
- 코드 주석 → **영어** (소스 파일 표준)
- 커밋 메시지 → Conventional Commits (영어 prefix)

---

## 5. 관련 문서

- `02-architecture-and-domain.md` — 아키텍처 다이어그램, 도메인 모델
- `03-db-schema-and-index.md` — ERD, DDL, 인덱스 설계
- `04-api-specification.md` — REST API + WebSocket 프로토콜
- `05-event-sourcing-strategy.md` — 중복/순서/복원 전략
- `06-async-pipeline.md` — 아웃박스 워커, DLQ, Idempotency
- `07-observability.md` — 메트릭/로그/추적, 대시보드 구성
- `08-docker-compose.md` — 로컬 환경 구성
- `09-failure-scenarios.md` — 장애 대응 시나리오
- `10-testing-and-load.md` — 테스트 전략, k6 스크립트
- `11-weekly-roadmap.md` — 일주일 일정 및 마일스톤
