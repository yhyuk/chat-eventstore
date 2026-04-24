# 11. AI 하네스 엔지니어링 — 개발 방법론

> **상태:** WORK IN PROGRESS (진행률 약 80%, D5 구현 중)
> **집계 기준일:** 2026-04-23 (D2-1 ~ D5 합의 완료까지)
> **최종 갱신은 D7 완료 후 수행 예정**

본 문서는 본 과제를 1인 개발자가 Claude Code(Anthropic) 위에 다중 에이전트 하네스를 구성하여 진행한 방식을 정리한다. 단순한 "AI에게 코드를 짜달라고 했다"가 아니라, **역할 기반 가상 팀 + 합의 사이클 + 병렬 실행**이라는 운영 모델을 의도적으로 설계해 사용한 결과를 수치화한다.

---

## 11.1 한 줄 요약

> 4역할(Planner / Architect / Critic / Executor) 가상 엔지니어링 팀을 ralplan 합의 사이클로 운용하여, 5일치 백엔드 과제를 결정 추적성·결함 사전 차단·테스트 회귀 0의 품질 게이트 안에서 구현 중.

---

## 11.2 활용 도구 스택

| 계층 | 도구 | 용도 |
|---|---|---|
| Runtime | **Claude Code (Opus 4.7 / Sonnet 4.6)** | 메인 CLI, 도구 호출, 파일 I/O |
| Workflow Orchestrator | **oh-my-claudecode (OMC)** | `ralplan`, `autopilot`, `team`, agent 정의 |
| Domain Skill Pack | **everything-claude-code (ECC)** | Spring Boot / JPA / TDD / Verification 자동 트리거 |
| Memory | `.omc/project-memory.json`, `.omc/state/`, `.omc/handoffs/` | 핫패스 학습, 미션 상태, 단계 간 인계서 |
| Permissioning | `.claude/settings.local.json` | 35개 화이트리스트 — 권한 프롬프트 차단 |
| Persistence | `.omc/plans/d{N}-*.md` | Day별 계획 문서 (계약서 역할) |

---

## 11.3 4역할 가상 팀 구성

| 역할 | 모델 | 책임 | 권한 |
|---|---|---|---|
| **Planner** | Opus | 요구사항 분해, 옵션 비교 표, 채택안 결정, ADR 초안 | 파일 읽기 + plan 작성 |
| **Architect** | Opus | 설계 검증, steelman, ADR 보강, 위험 식별 | **read-only** |
| **Critic** | Opus | BLOCKER/MAJOR/MINOR/Missing 등급 결함 지적 | **read-only** |
| **Executor** | Sonnet | Phase 단위 실제 코드 작성, 테스트 작성/실행 | 파일 쓰기 + Bash |

> read-only 역할(Architect/Critic)을 의도적으로 분리한 이유: 같은 모델이 작성하면서 동시에 검증하면 자기검증 편향이 발생. 권한 분리로 인지적 독립성을 강제한다.

---

## 11.4 작동 패턴 — ralplan 합의 사이클

매 Day마다 동일한 3단계 합의 사이클이 강제되었다.

```
[Planner v1]
   └─ 요구사항 분해 / 옵션 (a)~(g) 비교 / 채택안 v1
        ↓ steelman
[Architect 검토]
   └─ Rec#1~Rec#7 권고 (트랜잭션 경계, 인덱스, 테스트 누락 등)
        ↓ revision
[Planner v2]
        ↓ red-team
[Critic 검토]
   └─ BLOCKER 0~2 / MAJOR 1~5 / MINOR 2~6 / Missing 1~3
        ↓ revision
[Planner v3] — APPROVED 또는 ACCEPT-WITH-RESERVATIONS
        ↓
[Executor] — Phase A~I 병렬 분산 실행
        ↓
[테스트 ./gradlew test 통과] → [Conventional Commit]
```

### 실제 사례: D4 합의에서 차단된 결함

| 등급 | 항목 | 영향 |
|---|---|---|
| BLOCKER-1 | 배치 단일 트랜잭션 → 한 이벤트 실패가 전체 롤백 | per-event TransactionTemplate으로 변경 |
| BLOCKER-2 | DLQ에 sequence 누락 → 원본 조회 불가 | V6 마이그레이션으로 컬럼 추가 |
| MAJOR-1 | UPSERT의 첫 INSERT 경합 | `INSERT … ON DUPLICATE KEY UPDATE` 단일 쿼리화 |
| MAJOR-2 | TreeMap/LinkedHashMap 미강제 → projection 결정론 깨짐 | `ORDER_MAP_ENTRIES_BY_KEYS` 활성화 |
| MAJOR-3 | 스냅샷 실패가 세션 종료 롤백 | `REQUIRES_NEW` 격리 |
| Critic MAJOR-1 | `@Immutable` 엔티티에 `@Modifying` UPDATE 시도 | Native DELETE로 우회 |
| Critic MAJOR-2 | SKIP LOCKED가 FAILED 이벤트도 picking | WHERE에서 FAILED 제외 |

**위 7건은 코드 한 줄 작성 전에 합의 단계에서 발견·반영되었다.**

---

## 11.5 정량 지표 (2026-04-23 기준)

### 11.5.1 에이전트 사용량 (총 34 인스턴스 / 4 미션)

| 역할 | 인스턴스 수 | 비중 |
|---|---:|---:|
| planner | 11 | 32% |
| architect | 11 | 32% |
| critic | 8 | 24% |
| executor | 4 | 12% |
| **합계** | **34** | **100%** |

### 11.5.2 미션(=Day)별 분포

| 미션 | 일자 | 워커 | 산출 (commits) | 주제 |
|---|---|---:|---:|---|
| #1 D2-1 | 04-22 | 9 | 3 | JPA 엔티티 + Repository + Testcontainers |
| #2 D2-2 | 04-22 | 6 | 3 | Session REST + 글로벌 예외 처리 |
| #3 D3+D4 | 04-22 | **17** | 13 | WebSocket + Redis Pub/Sub + 비동기 파이프라인 + DLQ |
| #4 D5 | 04-23 | 2 | _진행 중_ | Restore API + Projection Rebuild + 세션 목록 |

> D3 한 미션에서만 17 워커가 동원된 이유: 9개 Phase를 의존성 분석으로 6개 병렬 그룹(A+B → C → D → E+F → G+H → I)으로 분할 후 동시 실행.

### 11.5.3 코드 산출물

| 지표 | 값 |
|---|---:|
| 누적 커밋 | 27개 (Conventional Commits 100%) |
| 변경 파일 | 94개 |
| 코드 라인 | +6,719 / −37 |
| 테스트 케이스 | 100 PASS (단위 + 통합 + Testcontainers) |
| Flyway 마이그레이션 | V1~V6 |
| ADR 합의 사이클 | 5회 (D2-1, D2-2, D3, D4, D5) |
| Pre-mortem 시나리오 사전 식별 | 3건 |

---

## 11.6 활용 스킬 / 슬래시 커맨드

### OMC (oh-my-claudecode) — 주 워크플로우
| 스킬 | 호출 | 용도 |
|---|---:|---|
| `/oh-my-claudecode:ralplan` | 5회 | 합의 기반 계획 수립 (Planner→Architect→Critic) |
| `/oh-my-claudecode:autopilot` | 다회 | Phase 자동 분산 실행 |
| `oh-my-claudecode:explore` (haiku) | 다회 | 코드베이스 탐색 |
| `oh-my-claudecode:architect` (opus) | 11회 | 설계 read-only 검증 |
| `oh-my-claudecode:critic` (opus) | 8회 | 다중 관점 결함 지적 |
| `oh-my-claudecode:executor` (sonnet) | 4회 | Phase 단위 구현 |

### ECC (everything-claude-code) — Spring Boot 전문 자동 트리거
| 스킬 | 트리거 시점 |
|---|---|
| `springboot-patterns` | Spring Boot 코드 작성 시 |
| `jpa-patterns` | JPA 엔티티/Repository 설계 시 |
| `springboot-tdd` | 테스트 작성 시 |
| `java-build-resolver` | Gradle 빌드 실패 시 |
| `springboot-verification` | 커밋 전 검증 루프 |
| `database-migrations` | Flyway 마이그레이션 작성 시 |

### Claude Code 네이티브
- **TaskCreate / TaskUpdate** — Phase별 진행 상태 추적
- **Bash run_in_background** — `./gradlew test` 비동기 실행
- **ScheduleWakeup** — 백그라운드 테스트 결과 폴링
- **자동 메모리** — `.omc/project-memory.json` 핫패스 49개 자동 학습

---

## 11.7 운영 규칙 (Rules)

| 출처 | 규칙 |
|---|---|
| `~/.claude/CLAUDE.md` | 한국어 응답 강제, 이모지 금지, OMC 우선 |
| `chat-eventstore/CLAUDE.md` | Conventional Commits, ADR 우선 참조, Native SQL은 SKIP LOCKED 1곳만, 헥사고날·STOMP·Kafka 도입 금지 |
| `.claude/settings.local.json` | 35개 명령 화이트리스트 — 권한 프롬프트 0 |
| `.omc/handoffs/` | 단계 간 인계서 (Decided / Rejected / Risks / Files / Remaining 5필드) |

---

## 11.8 의도적으로 배제한 것

| 배제 항목 | 이유 |
|---|---|
| 단일 에이전트 구현 | 자기검증 편향 회피 |
| Architect/Critic의 쓰기 권한 | 인지적 독립성 강제 |
| 합의 없이 코드 착수 | 결함이 코드에 박힌 후 발견하는 비용 회피 |
| 미션 간 컨텍스트 공유 의존 | handoff 문서로 명시 인계 → LLM 컨텍스트 윈도우 한계 보완 |
| 모든 Phase 직렬 실행 | 의존성 그래프 분석 후 병렬 그룹 추출 |

---

## 11.9 회고 — 무엇이 효과적이었나 (잠정)

> D5 완료 후 D6/D7 단계에서 갱신 예정.

1. **합의 비용 < 결함 발견 비용**: D4에서 합의 단계가 BLOCKER 2건을 사전 차단. 코드 진입 후 발견했다면 V6 마이그레이션 추가 + 트랜잭션 경계 재설계로 0.5~1일 손실 예상.
2. **역할 분리의 실효**: Planner가 놓친 "DLQ sequence 컬럼"을 Critic이 BLOCKER로 잡음. 같은 인스턴스가 작성+검증했다면 누락 가능성 높음.
3. **handoff 문서**: 새 세션에서 즉시 컨텍스트 복원 가능. 5필드 포맷(Decided/Rejected/Risks/Files/Remaining)이 회수율 핵심.
4. **병렬화의 한계**: D3에서 Phase E+F 병렬 실행 시 SessionRegistry 순환 의존 위험. Architect가 사전 지적하여 RedisSubscriptionRegistrar로 분리.

---

## 11.10 남은 작업 (D6 ~ D7 + 본 문서 갱신 계획)

- [ ] D5 구현 완료 (현재 진행 중)
- [ ] D6 — 관측 가능성 보강 + 부하 테스트 시나리오
- [ ] D7 — k6 부하 테스트 + Grafana 스크린샷 + 최종 문서화
- [ ] **본 문서 11.5/11.6/11.9 절 최종 수치로 갱신** (제출 직전)
- [ ] 최종 산출물에 `agent-replay-*.jsonl` 발췌 첨부 여부 결정

---

## 11.11 재현 방법

본 워크플로우는 다음 환경에서 재현 가능하다:

```bash
# 1. Claude Code 설치
curl -fsSL https://claude.ai/install | sh

# 2. OMC 플러그인 설치 (별도 README 참조)
/oh-my-claudecode:omc-setup

# 3. ECC 플러그인 설치
/everything-claude-code:configure-ecc

# 4. 본 프로젝트의 .omc/plans/ 와 .claude/settings.local.json 참조
```

상세 합의 로그는 `.omc/plans/d{2-5}-*.md` (1.5만~6.5만자), 미션 상태는 `.omc/state/mission-state.json`, 단계 간 인계는 `.omc/handoffs/team-plan.md` 에 보관되어 있다.
