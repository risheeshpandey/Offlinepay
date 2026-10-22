import { check, fail } from 'k6';
import { handleSummary } from './capacity.js';
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
export function setup() { return { token: 'FAKE_ACTUAL_SETUP_SENTINEL' }; }
export { handleSummary } from './capacity.js';
export default function () {
  const input = { root_group: { checks: [] }, state: { testRunDurationMs: 30000 },
    metrics: { business_requests: { values: { count: 42, rate: 1.4 } } },
    setup_data: { token: 'FAKE_SECRET_SENTINEL' }, unexpectedSecret: 'FAKE_EXTRA_SENTINEL' };
  const before = JSON.stringify(input);
  const exported = Object.values(handleSummary(input))[0];
  const report = JSON.parse(exported);
  if (!check(report, {
    'capacity report omits setup and unknown secrets': r => !('setup_data' in r) &&
      !('unexpectedSecret' in r) && !exported.includes('SENTINEL'),
    'capacity report retains measurement and workload': r => r.metrics.business_requests.values.count === 42 &&
      r.offlinepayMetadata.walletPairs >= 1 && r.offlinepayMetadata.maximumCapacityClaim === false,
    'capacity exporter does not mutate input': () => before === JSON.stringify(input),
  })) fail('Unsafe capacity export');
}
