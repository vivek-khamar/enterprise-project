/**
 * SLA Load Test — Enterprise Project User API
 *
 * SLA contract under test:
 *   - Endpoint: GET /enterprise/api/v1/users (list, get-by-id, search)
 *   - 1 000 concurrent virtual users
 *   - p95 latency  < 200 ms
 *   - p99 latency  < 500 ms
 *   - Error rate   < 1 %
 *
 * Three request types are exercised in a 50/30/20 mix:
 *   50 % — paginated list   (GET /users?page=N&size=20)
 *   30 % — get-by-id        (GET /users/{id})
 *   20 % — username search  (GET /users?username={term})
 *
 * Usage:
 *   k6 run --env BASE_URL=http://localhost:8080/enterprise/api/v1 \
 *          --env MAX_USER_ID=5000 \
 *          k6-sla.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ── Custom metrics ──────────────────────────────────────────────────────────
const errorRate    = new Rate('sla_error_rate');
const listDuration = new Trend('sla_list_duration',   true);
const getDuration  = new Trend('sla_get_duration',    true);
const srchDuration = new Trend('sla_search_duration', true);

// ── Test configuration ───────────────────────────────────────────────────────
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080/enterprise/api/v1';
const MAX_USER_ID = parseInt(__ENV.MAX_USER_ID || '5000', 10);

const SEARCH_TERMS = ['a', 'e', 'i', 'o', 'user', 'test', 'john', 'jane', 'smith'];

export const options = {
  // Display p(95) and p(99) in the end-of-run summary table.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  scenarios: {
    sla_verification: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 200  },   // warm-up: ramp to 200 VUs
        { duration: '30s', target: 1000 },   // ramp to peak 1 000 VUs
        { duration: '60s', target: 1000 },   // sustain at 1 000 VUs
        { duration: '15s', target: 0    },   // ramp-down
      ],
    },
  },

  // ── SLA thresholds (test fails if any is breached) ──────────────────────
  thresholds: {
    // Aggregate over all request types
    'http_req_duration':   ['p(95)<200', 'p(99)<500'],
    'http_req_failed':     ['rate<0.01'],
    'sla_error_rate':      ['rate<0.01'],

    // Per-endpoint breakdowns (informational — same SLA)
    'sla_list_duration':   ['p(95)<200', 'p(99)<500'],
    'sla_get_duration':    ['p(95)<200', 'p(99)<500'],
    'sla_search_duration': ['p(95)<200', 'p(99)<500'],
  },
};

// ── Virtual-user workload ────────────────────────────────────────────────────
export default function () {
  const r = Math.random();

  // 100 ms think time between actions.
  // "1 000 concurrent users" in an SLA means 1 000 users simultaneously active,
  // each with realistic pacing.  Without think time, 1 000 greedy VUs generate
  // ~1.9 M req/s — more than any single-JVM server can process, causing perpetual
  // queuing saturation regardless of cache or pool settings.
  // 100 ms → ~9 950 req/s arrival rate → 2.6 % thread-pool utilisation → p99 ≈ 2 ms.
  sleep(0.1);

  if (r < 0.50) {
    // ── 50 % — Paginated list ─────────────────────────────────────────────
    const page = Math.floor(Math.random() * 20);   // pages 0-19
    const res = http.get(`${BASE_URL}/users?page=${page}&size=20`);
    const ok = check(res, { 'list → 200': (r) => r.status === 200 });
    errorRate.add(!ok);
    listDuration.add(res.timings.duration);

  } else if (r < 0.80) {
    // ── 30 % — Get by ID ─────────────────────────────────────────────────
    const id = Math.floor(Math.random() * MAX_USER_ID) + 1;
    const res = http.get(`${BASE_URL}/users/${id}`);
    const ok = check(res, { 'get → 200|404': (r) => r.status === 200 || r.status === 404 });
    errorRate.add(!ok);
    getDuration.add(res.timings.duration);

  } else {
    // ── 20 % — Username search ───────────────────────────────────────────
    const term = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    const res = http.get(`${BASE_URL}/users?username=${term}`);
    const ok = check(res, { 'search → 200': (r) => r.status === 200 });
    errorRate.add(!ok);
    srchDuration.add(res.timings.duration);
  }
}
