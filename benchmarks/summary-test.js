import { check, fail } from 'k6';
import { handleSummary as exportReport } from './mixed.js';

// Pure report regression: no HTTP calls, users, payments or database changes.
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
export function setup() { return { token: 'FAKE_K6_SETUP_TOKEN' }; }
export function handleSummary(data) { return exportReport(data); }
export default function () {
  const data = {
    setup_data: { alice: { token: 'FAKE_TEST_ACCESS_TOKEN' }, refreshToken: 'FAKE_TEST_REFRESH_TOKEN' },
    unexpectedSecret: 'FAKE_EXTRA_SECRET',
    root_group: { checks: [] }, state: { testRunDurationMs: 30000 },
    metrics: { business_requests: { values: { count: 10, rate: 0.333 } } },
  };
  const before = JSON.stringify(data);
  const output = Object.values(exportReport(data))[0];
  const report = JSON.parse(output);
  const ok = check(report, {
    'export omits setup credentials and unrecognized root fields': () =>
      !('setup_data' in report) && !('unexpectedSecret' in report)
      && !output.includes('FAKE_TEST_ACCESS_TOKEN') && !output.includes('FAKE_TEST_REFRESH_TOKEN')
      && !output.includes('FAKE_EXTRA_SECRET'),
    'report retains metrics, timing, checks and run metadata': r =>
      r.metrics.business_requests.values.count === 10 && r.state.testRunDurationMs === 30000
      && Array.isArray(r.root_group.checks) && r.offlinepayMetadata.maximumCapacityClaim === false,
    'export does not mutate k6 input': () => JSON.stringify(data) === before,
  });
  if (!ok) fail('Report export regression failed');
}
