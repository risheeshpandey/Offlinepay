import { check } from 'k6';
import { handleSummary } from './ci-mixed.js';
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
export default function () {
  const data = {
    root_group: { name: '' }, state: { testRunDurationMs: 31000 },
    metrics: { business_requests: { values: { count: 1500 } },
      business_started_epoch_ms: { values: { min: 100000 } },
      business_finished_epoch_ms: { values: { max: 130000 } } },
    setup_data: { alice: { token: 'DO_NOT_EXPORT_SETUP_TOKEN' } }, unknown: 'DO_NOT_EXPORT_UNKNOWN',
  };
  const outputs = handleSummary(data);
  const report = JSON.parse(outputs[__ENV.SUMMARY || 'ci-summary.json']);
  check(report, {
    'setup credentials omitted': r => !JSON.stringify(r).includes('DO_NOT_EXPORT'),
    'setup excluded from rate': r => r.offlinepayMetadata.completedBusinessRpsExcludingSetup === 50,
    'fixed VUs recorded without a maximum claim': r => r.offlinepayMetadata.executor === 'constant-vus' && r.offlinepayMetadata.maximumCapacityClaim === false,
    'input data unchanged': () => data.setup_data.alice.token === 'DO_NOT_EXPORT_SETUP_TOKEN',
  });
}
