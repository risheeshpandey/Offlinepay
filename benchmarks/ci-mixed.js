// Same wallet/transfer loop as mixed.js; fixed VUs, not a fixed arrival-rate claim.
import business, { setup } from './mixed.js';
import { Counter, Trend } from 'k6/metrics';
export { setup };

const vus = Number(__ENV.VUS || 10);
const duration = __ENV.DURATION || '30s';
if (!Number.isInteger(vus) || vus < 1 || vus > 1000 || !/^\d+s$/.test(duration)) {
  throw new Error('CI workload requires 1..1000 VUs and a duration in seconds');
}
export const options = {
  vus, duration, setupTimeout: '180s', gracefulStop: '30s', maxRedirects: 0,
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.01'], business_failures: ['rate<0.01'],
    wallet_read_ms: ['p(95)<1000'], transfer_ms: ['p(95)<2000'],
  },
};
const starts = new Trend('business_started_epoch_ms');
const finishes = new Trend('business_finished_epoch_ms');
const response503 = new Counter('response_503');
const response504 = new Counter('response_504');
const other5xx = new Counter('response_other_5xx');
const transport = new Counter('transport_failures');
const limiterUnavailable = new Counter('error_rate_limit_unavailable');
const databaseUnavailable = new Counter('error_database_unavailable');
const insufficientFunds = new Counter('error_insufficient_funds');
const otherFailure = new Counter('error_other');

export default function (data) {
  starts.add(Date.now());
  const response = business(data);
  finishes.add(Date.now());
  if (response.status === 200) return;
  if (response.status === 503) response503.add(1);
  else if (response.status === 504) response504.add(1);
  else if (response.status >= 500) other5xx.add(1);
  else if (response.status === 0) transport.add(1);
  // Never export a response body, token, cookie, credential or arbitrary error string.
  let code = 'OTHER';
  try {
    const supplied = response.json('code');
    if (supplied === 'RATE_LIMIT_UNAVAILABLE') { code = supplied; limiterUnavailable.add(1); }
    else if (supplied === 'DATABASE_UNAVAILABLE') { code = supplied; databaseUnavailable.add(1); }
    else if (supplied === 'INSUFFICIENT_FUNDS') { code = supplied; insufficientFunds.add(1); }
    else otherFailure.add(1);
  } catch (_) { otherFailure.add(1); }
  if (__ITER < 5) console.warn(JSON.stringify({ event: 'business_failure', status: response.status, code, vu: __VU, iteration: __ITER }));
}

export function handleSummary(data) {
  const count = data.metrics.business_requests?.values.count || 0;
  const first = data.metrics.business_started_epoch_ms?.values.min;
  const last = data.metrics.business_finished_epoch_ms?.values.max;
  const seconds = Number.isFinite(first) && Number.isFinite(last) && last > first ? (last - first) / 1000 : null;
  const metadata = {
    runId: __ENV.RUN_ID, vus, duration, executor: 'constant-vus', walletPairs: 1,
    workload: 'Approximately 70% wallet reads / 30% fresh INR1 transfers, one shared sender',
    completedBusinessRpsExcludingSetup: seconds ? count / seconds : null,
    businessWindowSeconds: seconds, maximumCapacityClaim: false,
  };
  const report = { root_group: data.root_group, state: data.state, metrics: data.metrics, offlinepayMetadata: metadata };
  return {
    [__ENV.SUMMARY || 'ci-summary.json']: JSON.stringify(report, null, 2),
    stdout: JSON.stringify({ vus, completed: count, observedBusinessRps: metadata.completedBusinessRpsExcludingSetup }) + '\n',
  };
}
