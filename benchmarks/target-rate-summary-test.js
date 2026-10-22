import { check, fail } from 'k6';
import { handleSummary } from './target-rate.js';
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
export function setup() { return { token: 'FAKE_ACTUAL_SETUP_SENTINEL' }; }
export { handleSummary } from './target-rate.js';
export default function () {
  const input = { root_group: { checks: [] }, state: { testRunDurationMs: 5000 },
    metrics: { business_requests: { values: { count: 20 } },
      business_started_epoch_ms: { values: { min: 1000 } }, business_finished_epoch_ms: { values: { max: 3000 } } },
    setup_data: { token: 'FAKE_SECRET_SENTINEL' }, unexpectedSecret: 'FAKE_EXTRA_SENTINEL' };
  const before = JSON.stringify(input);
  const exported = Object.values(handleSummary(input))[0];
  const report = JSON.parse(exported);
  if (!check(report, {
    'target report excludes credentials and unknown fields': r => !('setup_data' in r) && !('unexpectedSecret' in r) && !exported.includes('SENTINEL'),
    'observed rate excludes setup and remains distinct from target': r => r.offlinepayMetadata.completedBusinessRpsExcludingSetup === 10 && r.offlinepayMetadata.targetRps === 1000 && r.offlinepayMetadata.businessWindowSeconds === 2,
    'target exporter preserves input and makes no max-capacity claim': r => JSON.stringify(input) === before && r.offlinepayMetadata.maximumCapacityClaim === false,
  })) fail('Target-rate report regression');
}
