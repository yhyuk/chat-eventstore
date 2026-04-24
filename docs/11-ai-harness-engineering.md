# 11. AI 협업 방식 — 개발 프로세스 회고

본 문서는 Claude Code 위에 다중 에이전트 하네스를 구성하여 진행한 방식을 정리한다. "AI에게 코드를 작성시켰다"가 아니라, **AI를 뛰어난 개발 동료로 두고 역할을 분리해 협업한 결과**를 기술한다.

---

## 11.1 포지션

- **AI는 단순 도구가 아닌 파트너로 취급한다.** 옵션 탐색·초안 작성·결함 지적을 위임하되, **채택·기각·아키텍처 경계 설정은 전부 개발자가 판단**한다.
- **편향 제거를 위해 역할을 쪼갠다.** 같은 모델이 작성과 검증을 함께 하면 자기검증 편향이 발생하므로, 읽기 전용 검토자를 강제로 끼운다.
- **검증되지 않은 제안은 코드에 들어가지 않는다.** 합의 사이클을 통과하지 못한 방향은 구현 단계로 넘어갈 수 없다.

---

## 11.2 개발자가 판단한 것 vs AI에게 위임한 것

| 구분 | 개발자 책임 | AI 위임 |
|---|---|---|
| **기술 스택 선택** | MySQL vs Postgres, JPA vs MyBatis, STOMP/Kafka 배제 | 옵션별 트레이드오프 표 생성 |
| **아키텍처 경계** | 이벤트 스키마(`clientEventId`/`sequence`/`clientTimestamp`), 순서 기준 `ORDER BY sequence, server_received_at, id`, Tombstone 패턴 채택 | ADR 초안 문서화 |
| **과제 범위 판정** | Non-goals 해석(Kafka·헥사고날·완전 인증 등 "과투자" 판정) | 제안 생성만, 채택 여부는 개발자 |
| **검증 결과 수용 판단** | Critic이 제시한 BLOCKER/MAJOR 등급을 실제로 받아들일지 결정 | 결함 후보 제시 |
| **디버깅 / 원인 분석** | WebSocket 경로의 고유한 버그 추적, 재현 UI(`chat-debug-ui.html`) 직접 제작 | 패턴 제안 (채택은 개발자) |
| **코드 리뷰** | 패턴 일관성, 네이밍, 테스트 시나리오 적절성 확인 | 초안 구현 |

**요약:** AI는 "가능한 선택지를 빠르게 펼쳐 보여주는 파트너"이자 "놓치기 쉬운 결함을 지적해주는 동료"로 활용했고, **모든 채택 결정과 방향 설정은 개발자가 수행**했다.

---

## 11.3 AI 제안을 기각한 사례

AI가 "엔터프라이즈급 설계"를 제안했으나 1주일 과제에는 과투자라 판단해 거부한 항목:

| 기각 항목 | AI의 논거 | 개발자 판단 |
|---|---|---|
| **Kafka 기반 이벤트 스트리밍** | 대규모 처리·확장성 | 1주일 과제에 운영 복잡도 과잉. DB 아웃박스 + `SKIP LOCKED`로 동일 목적 달성 가능. 이중 쓰기 문제도 원천 차단 |
| **헥사고날 / 멀티모듈** | 도메인 격리·테스트 용이성 | 단일 모듈 + 도메인 패키지로 충분. 과제 규모 대비 ROI 미달 |
| **STOMP 프로토콜** | Spring 생태계 표준 | 자체 이벤트 스키마 설계 자유도를 포기해야 함. 과제 요구사항(`clientEventId`/`sequence`)과 충돌 |
| **JWT / OAuth2 완전 인증** | 프로덕션 수준 보안 | 과제 Non-goals 영역. 쿼리 파라미터 최소 식별로 대체 |
| **별도 heartbeat scheduler** | Presence 정확도 향상 | Redis TTL만으로 만료 처리 충분. 스케줄러 추가는 복잡도 대비 이득 없음 |

공통 원칙: **과제 범위를 벗어나는 "좋은 설계" 제안은 과감히 기각한다.** 좋은 아키텍처 감각은 "무엇을 안 할지 결정하는 것"이라 판단.

---

## 11.4 개발자가 직접 해결한 사례

### chat-debug-ui.html 자체 제작

WebSocket 프레임 레벨 버그는 일반적인 브라우저 콘솔이나 Postman으로는 재현이 까다로웠다. **디버깅 루프를 직접 통제하기 위해** 단일 HTML 파일로 디버그 클라이언트를 작성했다.

- 2개 분할 뷰 클라이언트: Alice는 app1:7081, Bob은 app2:7082에 각각 WebSocket 연결
- 하단 RAW FRAME LOG 패널로 TX/RX 프레임 원본을 실시간 관측
- 세션 생성/참여, 메시지 송수신, EDIT/DELETE(Tombstone) 트리거, 양측 sequence 동기화 확인
- 서로 다른 앱 인스턴스에 접속한 상태에서 Redis Pub/Sub 전파가 동작함을 시각적으로 검증

결과: 아래 버그들을 **디버그 UI로 재현해 직접 수정**했다.

### 실제로 잡은 버그 예시

- **JOIN 응답에 `lastSequence` 누락으로 인한 PK 충돌** — 클라이언트가 JOIN이 점유한 sequence를 모른 채 첫 메시지를 보내다 `INVALID_SEQUENCE`(PK 충돌)가 발생. `JoinSessionResponse`에 `lastSequence`를 포함하고, 디버그 UI가 이를 받아 `lastSequence + 1`부터 전송하도록 수정 (커밋 `9a163a9`).

**디버깅은 AI에 위임하지 않는 영역으로 두었다.** 원인 추정과 재현 조건 통제는 개발자의 판단이 결정적이므로, AI는 수정안 검토에만 활용했다.

---

## 11.5 역할 분리 구성

| 역할 | 책임 | 권한 |
|---|---|---|
| **Planner** | 요구사항 분해, 옵션 비교, 채택안 초안, ADR 작성 | 읽기 + 계획 작성 |
| **Architect** | 설계 검증, 위험 식별, 아키텍처 정합성 검토 | **읽기 전용** |
| **Critic** | 결함 지적 (BLOCKER/MAJOR/MINOR/Missing 등급) | **읽기 전용** |
| **Executor** | 합의된 Phase 단위의 실제 구현, 테스트 작성·실행 | 쓰기 + Bash |
| **개발자 (본인)** | 전체 오케스트레이션, 채택/기각 최종 판단, 디버깅, 코드 리뷰 | 모든 권한 |

**read-only 역할을 분리한 이유:** 같은 인스턴스가 작성과 검증을 동시에 수행하면 자기검증 편향이 발생한다. 권한 분리로 인지적 독립성을 강제했다.

---

## 11.6 합의 사이클

```
[Planner v1]   요구사항 분해 · 옵션 비교 · 채택안 초안
      ↓
[Architect]    steelman · 위험 식별 · 보강 권고
      ↓
[Planner v2]   권고 반영
      ↓
[Critic]       red-team · BLOCKER/MAJOR/MINOR 지적
      ↓
[Planner v3]   (개발자 판단 개입) 수용 / 기각 결정 → 최종안 확정
      ↓
[Executor]     Phase 병렬 실행
      ↓
[테스트 통과]  → Conventional Commit
```

**개발자 개입 지점:** Planner v3 단계에서 Critic이 제시한 결함 목록을 전부 수용할지 판단한다. 과제 범위나 구현 비용 대비 이득이 부족하다고 판단되는 제안은 이 단계에서 기각한다.

---

## 11.7 합의 단계에서 차단된 실제 결함

코드 작성 전 합의 과정에서 발견·반영된 사례:

| 등급 | 항목 | 반영 방법 |
|---|---|---|
| BLOCKER | 배치 단일 트랜잭션 — 한 이벤트 실패가 전체 롤백 | per-event `TransactionTemplate`으로 분리 |
| BLOCKER | DLQ에 sequence 컬럼 누락 — 원본 조회 불가 | V6 마이그레이션 추가 |
| MAJOR | UPSERT 첫 INSERT 경합 | `INSERT … ON DUPLICATE KEY UPDATE` 단일 쿼리화 |
| MAJOR | 스냅샷 JSON 직렬화 시 Map 순서 비결정 — 재실행마다 다른 스냅샷 생성 위험 | `SnapshotObjectMapperConfig`에서 `ORDER_MAP_ENTRIES_BY_KEYS` 활성화 |
| MAJOR | 스냅샷 실패가 세션 종료 트랜잭션을 롤백 | `REQUIRES_NEW` 격리 |
| MAJOR | `@Immutable` 엔티티에 `@Modifying` UPDATE 시도 | Native DELETE로 우회 |
| MAJOR | Outbox 폴링 쿼리가 FAILED 이벤트까지 재picking할 수 있음 | 쿼리를 `PENDING`만 픽하도록 제한, FAILED는 DLQ 재시도 API로만 재진입 |

**코드 한 줄 작성 전에 합의 단계에서 전부 발견되었다.** 코드에 박힌 뒤 발견했다면 마이그레이션 재작성·트랜잭션 경계 재설계로 더 큰 비용이 발생했을 것이다.

---

## 11.8 도구 스택 (요약)

| 계층 | 도구 | 용도 |
|---|---|---|
| Runtime | Claude Code (Opus / Sonnet) | 메인 CLI, 도구 호출, 파일 I/O |
| Orchestrator | oh-my-claudecode (OMC) | 합의 사이클, agent 역할 정의 |
| Domain Skill | everything-claude-code (ECC) | Spring Boot / JPA / TDD 자동 트리거 |
| Memory | `.omc/handoffs/`, `.omc/project-memory.json` | 단계 간 인계서, 핫패스 학습 |
| Persistence | `.omc/plans/` | 역할별 계획 문서 (계약서 역할) |

구체 명령어·세부 설정은 부록(`.omc/plans/` 및 `.claude/settings.local.json`)에 보관.

---

## 11.9 의도적으로 배제한 것

| 배제 항목 | 이유 |
|---|---|
| 단일 에이전트로 구현 위임 | 자기검증 편향 회피 |
| Architect/Critic에 쓰기 권한 부여 | 인지적 독립성 강제 |
| 합의 없이 코드 착수 | 결함이 박힌 후 발견하는 비용 회피 |
| 디버깅을 AI에 위임 | 원인 추정·재현 조건 통제는 개발자 판단이 결정적 |
| "좋아 보이는 엔터프라이즈 설계" 무비판 수용 | 과제 범위 대비 과투자 판정 |

---

## 11.10 회고 — 무엇이 효과적이었나

1. **합의 비용 < 결함 발견 비용.** 합의 사이클 자체는 시간 투자가 크지만, 코드 진입 후 발견·수정보다 압도적으로 저렴하다. BLOCKER 2건이 코드에 박혔다면 마이그레이션 재작성 + 트랜잭션 재설계로 추가 비용이 들었을 것.
2. **역할 분리의 실효.** Planner가 놓친 "DLQ sequence 컬럼"을 Critic이 BLOCKER로 잡았다. 같은 인스턴스가 작성·검증했다면 누락되었을 가능성이 높다.
3. **AI를 "동료"로 두면 질문의 해상도가 올라간다.** 단순 "이거 구현해줘"가 아니라 "이 옵션 A vs B 중 과제 범위에서 어느 쪽이 나은가, 근거를 표로 달라"로 물으면 의사결정에 필요한 정보가 나온다.
4. **디버깅은 위임하지 않는다.** 재현 환경을 통제하고 원인을 특정하는 일은 개발자 손에 남겨두어야 한다. AI는 수정 후 패턴 리뷰에만 활용했다.
5. **"안 할 것"을 결정하는 것이 아키텍처.** Kafka·헥사고날·STOMP 같은 "좋은 설계 제안"을 과제 범위 기준으로 기각할 수 있었던 것이 가장 중요한 판단이었다.

---

## 11.11 재현 환경

```bash
# Claude Code 설치
curl -fsSL https://claude.ai/install | sh

# OMC 플러그인 설치
/oh-my-claudecode:omc-setup

# ECC 플러그인 설치
/everything-claude-code:configure-ecc
```

상세 합의 로그 및 역할별 계획 문서는 `.omc/plans/` 디렉터리에 보관되어 있다.
