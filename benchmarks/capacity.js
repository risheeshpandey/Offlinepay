// Automatic staged runner uses the same business loop as the manual baseline.
import business, { setup as createPair } from './mixed.js';

const pairs = Number(__ENV.WALLET_PAIRS || 1);
if (!Number.isInteger(pairs) || pairs < 1 || pairs > 20) throw new Error('WALLET_PAIRS must be 1..20');
export const options = {
  vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '30s',
  setupTimeout: '180s', gracefulStop: '10s', maxRedirects: 0,
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }],
    business_failures: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }],
    wallet_read_ms: [{ threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '10s' }],
    transfer_ms: [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '10s' }],
  },
};
export function setup() {
  const fixtures = [];
  for (let n = 0; n < pairs; n++) fixtures.push(createPair());
  return { pairs: fixtures };
}
export default function (data) { business(data.pairs[(__VU - 1) % data.pairs.length]); }
export function handleSummary(data) {
  // Do not serialize k6 setup_data: it contains fixture access tokens.
  const report = {
    root_group: data.root_group, state: data.state, metrics: data.metrics,
    offlinepayMetadata: {
      runId: __ENV.RUN_ID, vus: options.vus, duration: options.duration, walletPairs: pairs,
      workload: 'Approximately 70% reads / 30% fresh INR1 transfers; VUs share the configured wallet pairs',
      maximumCapacityClaim: false,
    },
  };
  return { [__ENV.SUMMARY || 'capacity-summary.json']: JSON.stringify(report, null, 2) };
}
