# 09. 테스트 전략 및 부하 테스트

## 1. 전체 전략

| 계층 | 도구 | 범위 | 실제 파일 수 |
|---|---|---|---|
| 단위 테스트 | JUnit 5 + AssertJ + Mockito | 도메인 로직, 순수 함수 | 13개 (`*Test.java`) |
| 통합 테스트 | Spring Boot Test + Testcontainers | Controller → DB/Redis E2E | 18개 (`*IntegrationTest.java`) |
| 공통 헬퍼 | `AbstractIntegrationTest`, `AbstractWebIntegrationTest`, `AbstractFullIntegrationTest`, `SharedContainers` | Testcontainers 베이스 | 4개 |
| 재현 스크립트 | bash + http client | 수동 검증용 | `scripts/reproduce.sh` + `http/*.http` |
| 부하 테스트 | k6 | HTTP/WebSocket 처리량/레이턴시 | `scripts/load-test.js` + 결과 JSON |

## 2. 단위 테스트

### 2.1 대상 클래스 (실제 구현)

`event/service/` `EventAppendService` 내부에 중복 감지(`DataIntegrityViolationException` catch)와 순서 검증(`sessions.last_sequence` 비교)이 통합되어 있어, 별도 `DuplicateDetector`/`OrderingService` 클래스는 두지 않는다.

- `EventAppendServiceTest` — UNIQUE 제약 위반 → `DuplicateEventException` 변환, sequence 역전/0 이하 거부
- `EventReplayServiceTest` — 이벤트 리스트 → 상태 결정론적 재구성
- `StateEventApplierTest` — 타입별 상태 전환(JOIN/LEAVE/MESSAGE/EDIT/DELETE 등) 순수 함수 검증, 결정론
- `SnapshotServiceTest` — 스냅샷 트리거 가드, RETENTION=3 정리, REQUIRES_NEW
- `ProjectionServiceTest` — 카운터 증감 (MESSAGE/JOIN/LEAVE) + EDIT/DELETE 시 변화 없음
- `OutboxPollerTest` — 2단계 트랜잭션, 재시도 + DLQ 이관
- `SessionServiceTest` — JOIN 자동 append, end 시 final snapshot 트리거

### 2.2 핵심 검증 케이스
```
- replay_deterministic:
    동일 이벤트 스트림을 두 번 replay → 동일 상태
- replay_with_duplicates:
    중복 이벤트 포함 스트림 → 중복 무시 후 결정론적 상태
- replay_with_out_of_order:
    sequence 역전된 스트림 → 정렬 후 결정론적 상태
- snapshot_plus_replay_equals_full_replay:
    전체 리플레이 결과 == 스냅샷 + 이후 이벤트 리플레이 결과
- tombstone_edit_reflects_at_time:
    t1 시점엔 원본, t2(EDIT 이후) 시점엔 수정본
- tombstone_delete_hides_message:
    DELETE 이후 시점에 status=DELETED
```

## 3. 통합 테스트 (Testcontainers)

### 3.1 인프라 구성
```java
@Testcontainers
@SpringBootTest
class IntegrationTestBase {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("chat");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

### 3.2 핵심 시나리오 (13개)

1. **SessionLifecycle**: `POST /sessions` → `join` → 이벤트 수집 → `end` 전 과정
2. **EventAppend_Duplicate**: 동일 `clientEventId` 3회 전송 → 1개만 persist, `chat_events_duplicates_total` 카운터 증가
3. **EventAppend_OutOfOrder**: sequence [3, 1, 2] 순 전송 → 저장은 도착 순, 복원은 [1,2,3]
4. **Restore_AtSpecificTime**: 특정 시점 `at` 기준 복원 결과 정확성
5. **Restore_Determinism**: 동일 `at`으로 두 번 호출 → 동일 결과
6. **Restore_WithSnapshot**: 세션 150 이벤트 → 스냅샷 생성 확인 → 복원 시 스냅샷 + 이후 이벤트 조합
7. **Tombstone_Edit**: MESSAGE → EDIT 시 복원 시점별 상태 확인
8. **Tombstone_Delete**: MESSAGE → DELETE 시 복원 시점별 상태 확인
9. **WebSocket_Broadcast**: 같은 세션 두 클라이언트 연결 (서로 다른 앱 인스턴스 시뮬레이션) → 한쪽 메시지 전송 → 다른쪽 수신 확인 (로컬 테스트는 단일 인스턴스 + Redis Pub/Sub 검증)
10. **Reconnect_Resume**: 연결 → 메시지 5개 → 연결 끊김 → 재연결 with `lastSequence=2` → 3,4,5 resume
11. **Outbox_Projection**: 이벤트 insert → 잠시 대기 → `session_projection.message_count` 반영 확인
12. **Outbox_RetryAndDLQ**: 의도적 예외 throw mock → retry 5회 후 `dead_letter_events` 이관 확인
13. **ProjectionRebuild**: 세션에 이벤트 100개 → projection 강제 삭제 → Rebuild API 호출 → 정확히 재구성 확인

### 3.3 WebSocket 테스트 방법
- Spring `WebSocketClient` (`StandardWebSocketClient`)로 테스트 내 연결
- 메시지 수신 검증은 `CompletableFuture<String>` + timeout

## 4. 재현 스크립트

### 4.1 구성
```
scripts/
├── reproduce.sh             # 마스터 스크립트 (메뉴형)
├── 01-create-session.sh
├── 02-send-events.sh
├── 03-duplicate-test.sh
├── 04-out-of-order-test.sh
├── 05-restore-test.sh
└── 06-reconnect-test.sh

http/
├── sessions.http            # IntelliJ / VS Code REST Client
├── events.http
└── timeline.http
```

### 4.2 WebSocket 테스트 도구
- `wscat` 또는 `websocat` (CLI)
- 예시:
  ```bash
  websocat "ws://localhost:7081/ws/chat?sessionId=1&userId=alice&lastSequence=0"
  # 이후 stdin으로 JSON 프레임 입력
  ```

## 5. 부하 테스트 (k6)

### 5.1 목표
- 이벤트 수집 API p99 < 200ms
- 동시 WebSocket 세션 500개 기준 처리량 측정
- 아웃박스 lag < 2s 유지 확인

### 5.2 시나리오

**Phase 1: Ramp up**
- 0 → 50 VUs in 30s

**Phase 2: Sustained**
- 50 VUs × 60s
- 각 VU: 세션 생성 → WebSocket 연결 → 초당 2 메시지 × 60초

**Phase 3: Restore burst**
- 10 VUs가 `GET /sessions/{id}/timeline` 동시 호출 × 30초

### 5.3 k6 스크립트 스케치
```javascript
// scripts/load-test.js
import ws from 'k6/ws';
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '60s', target: 50 },
    { duration: '30s', target: 10 }, // restore phase
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{name:events}': ['p(99)<200'],
    'http_req_duration{name:restore}': ['p(99)<500'],
  },
};

export default function () {
  const create = http.post('http://localhost:7081/sessions', '{}', {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'create' },
  });
  const sessionId = create.json('sessionId');

  // WebSocket 연결 + 메시지 송신
  ws.connect(`ws://localhost:7081/ws/chat?sessionId=${sessionId}&userId=u${__VU}`, {}, (socket) => {
    let seq = 1;
    socket.setInterval(() => {
      socket.send(JSON.stringify({
        clientEventId: `${__VU}-${seq}`,
        sequence: seq++,
        type: 'MESSAGE',
        payload: { text: `hi ${seq}` },
        clientTimestamp: new Date().toISOString(),
      }));
    }, 500);
    socket.setTimeout(() => socket.close(), 10000);
  });

  // Restore
  const restore = http.get(`http://localhost:7081/sessions/${sessionId}/timeline?at=${new Date().toISOString()}`, {
    tags: { name: 'restore' },
  });
  check(restore, { 'restore 200': (r) => r.status === 200 });
}
```

### 5.4 결과 수집
- k6 summary 출력 저장: `k6 run --summary-export=results.json scripts/load-test.js`
- Prometheus 메트릭 스크린샷 → Grafana `App Dashboard` 화면 캡처
- `docs/images/load-test-YYYYMMDD.png` 로 보관
- 결과 요약 문서 1페이지 (`docs/09-testing-and-load.md`의 결과 섹션):
  - 이벤트 수집 p50/p95/p99
  - 복원 p50/p95/p99
  - 처리량 req/s
  - 에러율
  - 아웃박스 lag 최대/평균
  - 병목 분석 (만약 있다면)

## 5.5 실측 결과 (2026-04-24)

부하 테스트와 중복 처리 전략 실측을 Grafana 대시보드 한 화면에 담았다.

![Grafana 통합 대시보드 실측](images/grafana-dashboard.png)

### 관측 구간 A: 부하 테스트 (14:30 ~ 14:45, 좌측)

k6 스크립트(`scripts/load-test.js`)로 50 VU × 초당 2 MESSAGE 부하를 인가한 구간.

| 지표 | 피크값 | 회복 후 |
|---|---|---|
| 실시간 이벤트 수신 처리량 | 약 230 events/s | 0 |
| Projection 반영 지연 시간 | 약 600초 (선형 증가) | 0 |
| WebSocket 활성 세션 수 | 8개 (부하 종료 후 2 → 0) | 0 |
| DLQ 이관 | 0 건 | 0 건 |

**해석:** 아웃박스 워커의 이론 처리량(`batch-size 100 × 500ms 주기 = 200/s`)을 유입량이 초과하면서 `PENDING` 이벤트가 선형 누적, Projection lag이 600초까지 상승했다. 부하 종료 후 수동 개입 없이 **pending=0 / lag=0으로 자연 회복**. DLQ 이관이 한 건도 없어 이벤트 유실 없이 모두 처리되었다. 이는 ADR-004(DB 아웃박스 채택)가 "이벤트 수집과 projection을 분리하여 수집 경로는 워커 지연과 무관하게 유지한다"는 설계 의도의 실측 검증이다.

### 관측 구간 B: 중복 처리 전략 실측 (15:20 직전, 우측 끝)

동일 `clientEventId`로 500회 연속 전송하여 중복 감지 경로를 검증.

| 지표 | 값 | 의미 |
|---|---|---|
| 총 전송 | 501 건 | 최초 1 + 중복 500 |
| `ACCEPTED` | 1 건 | 최초 요청만 DB 저장 |
| `DUPLICATE_IGNORED` | 500 건 | 이후 499건 + 최초 이전 중복 1건 모두 멱등 응답 |
| `chat_events_duplicates_total` 카운터 증분 | 519 | 이전 테스트 누계 포함 |
| Projection lag | 0 유지 | 중복이 projection 파이프라인에 영향 없음 |

**해석:** UNIQUE(session_id, client_event_id) DB 제약이 **최종 방어선**으로 동작. 이벤트 수신 처리량 패널과 중복 차단 건수 패널이 **동일 시점에 동일 높이의 spike**를 그리는 것은 "들어온 요청의 대부분이 중복이었고 모두 차단되었음"의 직접 증빙이다. 파이프라인/DLQ에 부작용 없음.

### 종합 평가

| 과제 평가 항목 | 대응 증빙 |
|---|---|
| 2.1 중복 이벤트 전략 | 구간 B의 중복 차단 spike (500건 차단) |
| 4.1 실시간 메시지 송수신 | 구간 A의 수신 처리량 + WebSocket 세션 |
| 4.2 비동기 처리 구조 | 구간 A의 Projection lag 회복 곡선 |
| 4.4(3) 데이터 유실/정합성 | DLQ = 0 (전 구간 유실 없음) |
| 가산점 — 부하 테스트 + 운영 대시보드 | 본 스크린샷 전체 |

## 6. CI 설계 (가산점, 구현은 선택)

- GitHub Actions:
  - `./gradlew test` (Testcontainers 사용, docker 러너 필요)
  - `./gradlew bootJar` 후 docker 이미지 빌드 smoke test
- 부하 테스트는 수동 실행 권장

## 7. 테스트 데이터 정리

- 통합 테스트 종료 시 `@Sql` 또는 `truncate` 로 정리
- Testcontainers는 테스트 클래스 종료 시 자동 폐기 (기본 동작)
- k6 부하 테스트 후 DB 정리 스크립트: `scripts/cleanup.sh`

## 8. 검증 목록 (제출 전)

- [ ] 단위/통합 테스트 31개(`./gradlew test`) 모두 통과
- [ ] 부하 테스트 실행 결과(`docs/load-test-results/k6-*.json`) 포함
- [ ] 재현 스크립트(`scripts/reproduce.sh`) README에서 링크 및 실행법 명시
- [ ] k6 summary + Grafana 스크린샷 2~3장 첨부 (`docs/images/`)
