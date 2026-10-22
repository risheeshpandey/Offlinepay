// One iteration makes exactly one business HTTP request. Fixture/setup calls are separate.
import business from './capacity.js';
import { Trend } from 'k6/metrics';
export { setup } from './capacity.js';

const rate = Number(__ENV.TARGET_RPS || 1000);
const maxVUs = Number(__ENV.MAX_VUS || 40);
const preAllocatedVUs = Math.min(Number(__ENV.PREALLOCATED_VUS || 10), maxVUs);
const duration = __ENV.DURATION || '30s';
if (!Number.isInteger(rate) || rate < 1 || rate > 10000 || !Number.isInteger(maxVUs) || maxVUs < 1 || maxVUs > 100
    || !Number.isInteger(preAllocatedVUs) || preAllocatedVUs < 1) throw new Error('Invalid target rate / bounded VU allocation');
const starts = new Trend('business_started_epoch_ms');
const finishes = new Trend('business_finished_epoch_ms');
export const options = {
  setupTimeout: '180s', maxRedirects: 0,
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: { target_rate: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
    preAllocatedVUs, maxVUs, gracefulStop: '10s' } },
  thresholds: {
    dropped_iterations: [{ threshold: 'count==0', abortOnFail: true, delayAbortEval: '10s' }],
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }],
    business_failures: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }],
    wallet_read_ms: [{ threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '10s' }],
    transfer_ms: [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '10s' }],
  },
};
export default function (data) {
  starts.add(Date.now());
  business(data);
  finishes.add(Date.now());
}
export function handleSummary(data) {
  const count = data.metrics.business_requests?.values.count || 0;
  const first = data.metrics.business_started_epoch_ms?.values.min;
  const last = data.metrics.business_finished_epoch_ms?.values.max;
  const windowSeconds = Number.isFinite(first) && Number.isFinite(last) && last > first ? (last - first) / 1000 : null;
  const report = {
    root_group: data.root_group, state: data.state, metrics: data.metrics,
    offlinepayMetadata: {
      runId: __ENV.RUN_ID, executor: 'constant-arrival-rate', targetRps: rate, duration,
      preAllocatedVUs, maxVUs, walletPairs: Number(__ENV.WALLET_PAIRS || 1),
      workload: 'Approximately 70% wallet reads / 30% fresh INR1 transfers',
      completedBusinessRequests: count, businessWindowSeconds: windowSeconds,
      completedBusinessRpsExcludingSetup: windowSeconds ? count / windowSeconds : null,
      maximumCapacityClaim: false,
    },
  };
  return { [__ENV.SUMMARY || 'target-rate-summary.json']: JSON.stringify(report, null, 2) };
}
