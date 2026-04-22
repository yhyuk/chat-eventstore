# chat-eventstore

1:1 실시간 채팅 + 이벤트 기반 상태 복원 백엔드.

## 빠른 실행

```bash
# 1. 빌드
./gradlew bootJar

# 2. 기동 (앱 2대 + MySQL + Redis + 관측 스택)
docker compose up -d --build

# 3. 기동 확인
curl http://localhost:7081/actuator/health
curl http://localhost:7082/actuator/health
```

### 포트 매핑

| 서비스 | 포트 | 비고 |
|---|---|---|
| app1 | 7081 | Spring Boot 인스턴스 1 |
| app2 | 7082 | Spring Boot 인스턴스 2 |
| MySQL | 6306 | user/pw `root/root`, DB `chat` |
| Redis | 6389 | — |
| Prometheus | 9190 | — |
| Grafana | 3100 | anonymous viewer 허용 |
| Zipkin | 9211 | — |
| mysql-exporter | 9204 | Prometheus scrape 대상 |
| redis-exporter | 9221 | Prometheus scrape 대상 |

표준 기본 포트 대신 프로젝트 전용 4자리 포트로 통일하여 로컬 환경과의 충돌을 방지했다. 호스트 포트와 컨테이너 내부 리스닝 포트를 같은 값으로 맞춰 디버깅 시 혼동을 줄였다.

### 접속 엔드포인트

| 용도 | URL |
|---|---|
| 앱 1 | http://localhost:7081 |
| 앱 2 | http://localhost:7082 |
| Swagger UI | http://localhost:7081/swagger-ui.html |
| Prometheus | http://localhost:9190 |
| Grafana | http://localhost:3100 |
| Zipkin | http://localhost:9211 |

## 주요 의사결정 요약

상세 근거는 `docs/01-overview-and-decisions.md` 참조.

| 주제 | 선택 | 핵심 근거 |
|---|---|---|
| 실시간 전송 | 순수 WebSocket | `clientEventId/sequence/clientTimestamp` 스키마 자유도 확보, STOMP 프레임 제약 회피 |
| DB | MySQL 8.x | InnoDB clustered index `(session_id, sequence)` 로 세션별 순차 I/O |
| ORM | JPA + QueryDSL | append-only 구조에서 JPA 강점, Native는 SKIP LOCKED 한 곳만 |
| 비동기 파이프라인 | DB 아웃박스 + `@Scheduled` + SKIP LOCKED | 이중 쓰기 문제 차단, 운영 단순화 |
| Redis 역할 | Pub/Sub · Presence TTL · Recent N Sorted Set | 3가지에 한정 (YAGNI) |
| 관측 | Micrometer + Prometheus + Grafana + JSON 로그 + Zipkin | 메트릭/로그/추적 3요소 전부 커버 |

## 프로젝트 구조

```
chat-eventstore/
├── build.gradle / settings.gradle
├── docker-compose.yml / Dockerfile
├── src/main/java/com/example/chat/        # session, event, projection, realtime, presence, restore
├── src/main/resources/
│   ├── application.yml / application-docker.yml
│   ├── logback-spring.xml
│   └── db/migration/V1~V5__*.sql          # Flyway
├── docs/                                  # 설계 문서 (한국어)
├── openapi/openapi.yaml                   # OpenAPI 스펙
├── observability/
│   ├── prometheus/prometheus.yml
│   └── grafana/{provisioning,dashboards}
├── scripts/                               # reproduce.sh, load-test.js (k6)
└── http/                                  # .http 재현 파일
```

## 설계 문서

| # | 제목 | 경로 |
|---|---|---|
| 01 | 개요 + ADR 통합 | [docs/01-overview-and-decisions.md](docs/01-overview-and-decisions.md) |
| 02 | 아키텍처 + 도메인 | [docs/02-architecture.md](docs/02-architecture.md) |
| 03 | DB 스키마 + 인덱스 | [docs/03-db-schema.md](docs/03-db-schema.md) |
| 04 | API 명세 | [docs/04-api-spec.md](docs/04-api-spec.md) |
| 05 | 이벤트 소싱 전략 | [docs/05-event-sourcing.md](docs/05-event-sourcing.md) |
| 06 | 비동기 파이프라인 | [docs/06-async-pipeline.md](docs/06-async-pipeline.md) |
| 07 | 관측 가능성 | [docs/07-observability.md](docs/07-observability.md) |
| 08 | 장애 시나리오 | [docs/08-failure-scenarios.md](docs/08-failure-scenarios.md) |
| 09 | 테스트 및 부하 전략 | [docs/09-testing-and-load.md](docs/09-testing-and-load.md) |
| 10 | 쿼리 최적화 | [docs/10-query-optimization.md](docs/10-query-optimization.md) |

## 보안 주의

본 저장소는 로컬 실행 편의를 위해 비밀번호/설정을 평문 포함하고 있다. 운영 환경에서는 Secrets Manager 또는 환경 변수 주입을 사용해야 한다.
