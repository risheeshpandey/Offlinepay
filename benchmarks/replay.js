import http from 'k6/http';
import { check, fail } from 'k6';
export { setup } from './mixed.js';

// Functional concurrent retry check; expected conflicts are not HTTP failure metrics.
export const options = { vus: 1, iterations: 1, setupTimeout: '60s', thresholds: { checks: ['rate==1'] } };
http.setResponseCallback(http.expectedStatuses(200, 201, 409));
export default function (data) {
  const base = __ENV.BASE_URL || 'http://localhost:9030';
  const headers = { Authorization: 'Bearer ' + data.alice.token, 'Content-Type': 'application/json',
    'Idempotency-Key': 'load-replay-' + (__ENV.RUN_ID || Date.now()) };
  const body = JSON.stringify({ receiverWalletId: data.bob.walletId, amount: 1 });
  const results = http.batch([
    ['POST', base+'/api/v1/payments/transfer', body, { headers }],
    ['POST', base+'/api/v1/payments/transfer', body, { headers }],
  ]);
  if (!check(results, { 'concurrent retries return one transaction': r => r.every(x => x.status === 200)
      && r[0].json('transactionId') === r[1].json('transactionId') })) fail('Concurrent replay failed');
  const changed = http.post(base+'/api/v1/payments/transfer',
    JSON.stringify({ receiverWalletId: data.bob.walletId, amount: 2 }), { headers });
  check(changed, { 'changed payload rejected': r => r.status === 409 });
}
