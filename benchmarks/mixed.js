import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Run only against an isolated demo database: setup creates users and virtual money.
if (__ENV.ALLOW_DEMO_LOAD !== 'yes') throw new Error('Set ALLOW_DEMO_LOAD=yes for an isolated demo deployment');
const base = __ENV.BASE_URL || 'http://localhost:9030';
const run = __ENV.RUN_ID || String(Date.now());
const reads = new Trend('wallet_read_ms', true);
const writes = new Trend('transfer_ms', true);
const accepted = new Counter('transfers_accepted');
const failures = new Rate('business_failures');
const requests = new Counter('business_requests');
const limited = new Counter('rate_limited');
const serverErrors = new Counter('server_errors');
export const options = {
  vus: Number(__ENV.VUS || 1), duration: __ENV.DURATION || '30s',
  setupTimeout: '60s', summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: { http_req_failed: ['rate<0.01'], business_failures: ['rate<0.01'],
    wallet_read_ms: ['p(95)<1000'], transfer_ms: ['p(95)<2000'] },
};
function json(response, status, label) {
  if (!check(response, { [label]: r => r.status === status })) fail(label + ': HTTP ' + response.status);
  return response.json();
}
function user(name, mobileNumber) {
  const payload = { name, mobileNumber, password: 'LoadDemoPassword123!' };
  let csrf = json(http.get(base + '/api/v1/auth/csrf'), 200, 'csrf');
  const params = { headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token } };
  const signup = json(http.post(base + '/api/v1/auth/signup', JSON.stringify(payload), params), 201, 'signup');
  csrf = json(http.get(base + '/api/v1/auth/csrf'), 200, 'csrf');
  params.headers[csrf.headerName] = csrf.token;
  const login = json(http.post(base + '/api/v1/auth/login', JSON.stringify(payload), params), 200, 'login');
  return { walletId: signup.walletId, token: login.accessToken };
}
export function setup() {
  const suffix = String(Math.floor(Math.random() * 100000000)).padStart(8, '0');
  const alice = user('Load Alice', '81' + suffix);
  const bob = user('Load Bob', '82' + suffix);
  const headers = { Authorization: 'Bearer ' + alice.token, 'Content-Type': 'application/json', 'Idempotency-Key': 'load-topup-' + run };
  json(http.post(base + '/api/v1/demo/topups', JSON.stringify({ amount: 100000 }), { headers }), 200, 'topup');
  return { alice, bob };
}
export default function (data) {
  // Exactly 7 wallet reads / 3 fresh transfers per 10 iterations; one hot sender.
  const transfer = __ITER % 10 >= 7;
  const headers = { Authorization: 'Bearer ' + data.alice.token, 'Content-Type': 'application/json',
    'X-Request-ID': 'load-' + run + '-' + __VU + '-' + __ITER };
  let response;
  if (transfer) {
    headers['Idempotency-Key'] = 'load-' + run + '-' + __VU + '-' + __ITER;
    response = http.post(base + '/api/v1/payments/transfer', JSON.stringify({ receiverWalletId: data.bob.walletId, amount: 1 }),
      { headers, tags: { name: 'transfer' } });
    writes.add(response.timings.duration);
    if (response.status === 200) accepted.add(1);
  } else {
    response = http.get(base + '/api/v1/wallets/me', { headers, tags: { name: 'wallet' } });
    reads.add(response.timings.duration);
  }
  const ok = check(response, { 'business request accepted': r => r.status === 200 });
  requests.add(1);
  limited.add(response.status === 429 ? 1 : 0);
  serverErrors.add(response.status >= 500 ? 1 : 0);
  failures.add(!ok);
  if (__ENV.THINK_TIME) sleep(Number(__ENV.THINK_TIME));
  return response; // Optional diagnostic wrapper; existing callers may ignore this.
}
export function handleSummary(data) {
  // k6 includes setup_data (with access tokens) here. Export only report fields.
  const report = {
    root_group: data.root_group,
    state: data.state,
    metrics: data.metrics,
    offlinepayMetadata: { runId: run, vus: options.vus, duration: options.duration,
      workload: '7 reads / 3 new transfers per ten iterations, one hot sender wallet',
      maximumCapacityClaim: false },
  };
  return { [__ENV.SUMMARY || 'load-summary.json']: JSON.stringify(report, null, 2) };
}
