import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:7081';

export const options = {
  scenarios: {
    default: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '60s', target: 50 },
        { duration: '30s', target: 10 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    'http_req_duration{name:events}': ['p(99)<200'],
    'http_req_duration{name:restore}': ['p(99)<500'],
    'http_req_failed': ['rate<0.05'],
  },
};

export default function () {
  // Phase 1-2: Create session
  const createRes = http.post(
    `${BASE}/sessions`,
    JSON.stringify({ createdBy: `u${__VU}` }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'create' },
    }
  );

  check(createRes, {
    'create 2xx': (r) => r.status >= 200 && r.status < 300,
  });

  if (createRes.status >= 300) {
    return;
  }

  const sessionData = createRes.json();
  const sessionId = sessionData.sessionId || sessionData.id;

  // Phase 1-2: Join session
  const joinRes = http.post(
    `${BASE}/sessions/${sessionId}/join`,
    JSON.stringify({ userId: `u${__VU}` }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'join' },
    }
  );

  check(joinRes, {
    'join 2xx': (r) => r.status >= 200 && r.status < 300,
  });

  // Phase 2: Send 10 events via HTTP POST
  for (let seq = 1; seq <= 10; seq++) {
    const event = {
      clientEventId: `${__VU}-${__ITER}-${seq}`,
      userId: `u${__VU}`,
      sequence: seq,
      type: 'MESSAGE',
      payload: { text: `message ${seq} from VU ${__VU}` },
      clientTimestamp: new Date().toISOString(),
    };

    const eventRes = http.post(
      `${BASE}/sessions/${sessionId}/events`,
      JSON.stringify(event),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'events' },
      }
    );

    check(eventRes, {
      'event 2xx or duplicate': (r) => r.status === 200 || r.status === 202,
    });

    sleep(0.1);
  }

  // Phase 3: Restore burst - hit timeline API occasionally
  if (__ITER % 5 === 0) {
    const atTime = new Date().toISOString();
    const restoreRes = http.get(
      `${BASE}/sessions/${sessionId}/timeline?at=${encodeURIComponent(atTime)}`,
      {
        tags: { name: 'restore' },
      }
    );

    check(restoreRes, {
      'restore 2xx': (r) => r.status >= 200 && r.status < 300,
    });
  }
}
