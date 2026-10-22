# Hosted capacity baseline: outbox bottleneck

Run: [39761172032](https://github.com/risheeshpandey/Offlinepay/actions/runs/39761172032), source `58ed6175bb2cca83eda1fbc8e567710db740b4df`, 22 October2026. [Retained machine-readable report](../benchmarks/evidence/baseline-39761172032/report.json).

**Overall result: CONFIRMATION_FAILED.** API and financial checks passed; eventual notification delivery did not finish within the600-second drain allowance. These results do not establish maximum capacity or a passing end-to-end throughput claim.

All services, infrastructure and k6 shared an Ubuntu runner with4 assigned CPUs, approximately16GiB RAM and Java21. Fixed VUs issued approximately70% wallet reads and30% new INR1 transfers against one shared sender. Core pool10, notification pool3, Redis protection enabled, original outbox poll2000ms with batch20. Rules: HTTP/business errors<1%, read p95<1000ms, transfer p95<2000ms, full financial and delivery reconciliation.

| Stage | VUs | Seconds | Business requests/s | Transfers accepted | Transfer p95 ms | Read p95 ms | Request failure rate | Delivery drain | Overall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Search10 | 10 | 30 | 403.49 | 3,625 | 99.60 | 26.75 | 0% | 349.8s; cleared | Pass |
| Search25 | 25 | 30 | 695.99 | 6,243 | 108.52 | 42.49 | 0% | 600.85s;143 events unpublished | Fail |
| Confirmation10 | 10 | 180 | 740.07 | 39,962 | 60.86 | 8.94 | 0% | 602.23s;32,506 events unpublished | Fail |

Business rates exclude setup and include the completion tail. Throughput varies with warmup and runner conditions; the faster confirmation is not evidence of a code optimization. Virtual users are not an unconditional simultaneous-request capacity.

No negative wallet, unbalanced operation, duplicate key/notice, per-wallet journal mismatch or open dead letter was observed. Confirmation database transfer delta equalled39,962 accepted transfers; cumulative wallet funds equalled issued funds. Notification delivery remained incomplete, which fails the overall result despite financial correctness.

## Diagnosis and next measurement

[`OutboxRelay.poll()`](../src/main/java/com/risheesh/offlinepay/messaging/OutboxRelay.java) uses a fixed delay of2000ms after each batch; [`OutboxTransactions.ready()`](../src/main/java/com/risheesh/offlinepay/messaging/OutboxTransactions.java) selects at most20 records. Even before publishing/database work, that configuration limits a single worker to fewer than10 events/s. The observed queue drain was consistent with approximately9–10 events/s. API transfer production exceeded delivery throughput.

A subsequent CI deployment explicitly selects `--offlinepay.messaging.poll-ms=100`, retaining batch20 and the same reconciliation, latency/error rules and drain budget. The application default remains2000ms; results must name which deployment setting was measured. This configuration experiment has not passed merely because it was added.

The baseline runner started confirmation with a small residual queue from the failed prior stage. The full cumulative counts are retained above. The updated runner requires a clean delivery snapshot before confirmation; a remaining backlog stops the run instead of being attributed to new confirmation traffic.
