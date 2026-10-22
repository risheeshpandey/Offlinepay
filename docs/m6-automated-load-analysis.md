# Actual Windows automatic-run analysis

Source: user-uploaded `capacity-results.zip`, run `auto-20261022-052531-8ca2c4`,22 October2026 IST. The archive was read directly; no original token or setup_data was present in its summaries. Four capped Java21 services and k6 share an8GB Windows11 Pro laptop with i5-8300H /8 logical processors. The runner revision was `native-launch-fix-1`.

## Successful stage

10 VUs,30 active seconds:5,974 business requests,1,784 accepted transfers,189.3954194809738 mixed business requests/s using the k6 total-runtime denominator,56.558658914304864 accepted transfers/s. Wallet p95/p99:15.226245 /36.314382ms. Transfer p95/p99:245.171615 /335.995153ms. HTTP/business failure rates0; all thresholds pass, exit0. SQL deltas match accepted transfers and one new topup. This is a successful short local run, not sustained maximum capacity.

At the end of traffic1,526 events were unpublished. The runner's subsequent reconciliation wait was189.49 seconds. During traffic, snapshots recorded as many as8 PostgreSQL lock waiters; this is evidence of contention in the one-hot-sender workload, not proof of the sole bottleneck. Highest sampled CPU during this stage was93%; highest sampled used RAM92.59%. During its drain, used RAM samples reached93.05%. These are periodic samples, not guaranteed instantaneous peaks.

After this stage:8,250 cumulative transfers,7 topups,8,257 published/consumed events,16,507 expected/actual notices;70,000,000paise wallet funds equal issuance. Negative wallets, unbalanced operations, wallet/journal mismatches, duplicate keys/notices and open DLT are all0.

## Interrupted20-VU stage

After waiting for headroom, the run began at about809.6MiB free /89.97% used RAM. Five consecutive traffic samples stayed at or above the90% guard; sampled RAM reached92.5% and CPU96%. The runner stopped only its k6 child with status `HOST_MEMORY_LIMIT`, exit103. Longer confirmation was `NOT_RUN`.

The partial metrics show2,129 completed business requests,611 recorded accepted transfers,145.60535079215126 mixed requests/s, transfer p95 532.74435ms. Despite partial error rates0, this interrupted stage is not a successful capacity measurement.

SQL transfer delta was626, while the client recorded611 successes:15 committed operations lack a recorded success response in this canceled run. Cancellation can interrupt responses or client bookkeeping after a commit. This discrepancy is not proof of duplicate debit. Reconciliation found no duplicate actor/idempotency keys, no journal mismatch and no money loss. A real client should retain its original idempotency key and reconcile/retry that same operation rather than create a new payment on an ambiguous response.

After a71.48-second delivery wait:8,876 cumulative transfers,8 topups,8,884 published/consumed events,17,760 expected/actual notices;80,000,000paise = INR800,000 wallet funds equal issuance. All financial/duplicate/DLT problem counts remain0.

## What is established

- The native-launch correction worked on the actual Windows host; isolation preflight passed.
- The previously pending manual30-VU backlog had fully drained before this automatic run: initial6,472 events and12,938 notices matched.
- A10-VU short stage achieved about189.4 mixed requests/s and245ms transfer p95 with zero observed request errors, followed by complete financial and eventual notification reconciliation.
- Host memory stopped further exploration; neither1,000 RPS nor10,000 concurrent requests nor maximum sustainable capacity is established.
- Notification backlog grew during the successful stage and drained afterward. The API rate is not sustained notification throughput. Exact backend-vs-generator capacity requires further controlled measurements.

The next request is a guarded direct1,000-RPS target attempt; see `target-rate-test.md`. Changing only the requested rate does not add backend or laptop capacity. The cumulative PDF still represents the earlier reconciled1/5/10-VU checkpoint until new testing is finished.
