// k6 load test scaffold. Runbook in docs/09-testing-and-load.md.
// Full scenario will be wired up on D7; keeping a minimal, runnable shell here
// so the entry point is obvious.
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 2,
      duration: '10s',
    },
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:7081';

export default function () {
  const res = http.get(`${BASE}/actuator/health`);
  check(res, { 'health is UP': (r) => r.status === 200 });
  sleep(1);
}
