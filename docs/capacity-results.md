# Hosted capacity observations

The first tuned run passed a180-second confirmation at200 virtual users. The search ceiling was reached, so this is a tested observation rather than a maximum. A further bounded search is running with higher levels and unchanged pass/fail rules.

Evidence: [GitHub Actions run39766611980](https://github.com/risheeshpandey/Offlinepay/actions/runs/39766611980), source `a1f05d4087603859bff334af434e5226bf6d664f`, [retained report](../benchmarks/evidence/tuned-39766611980/report.json), [confirmation summary](../benchmarks/evidence/tuned-39766611980/confirm-throughput-200.json).

| Confirmation measurement | Observed |
| --- | ---: |
| Virtual users | 200 |
| Requested traffic duration | 180s |
| Business requests completed | 114,539 |
| Mixed business requests/s, excluding setup | 635.47 |
| Accepted INR1 transfers | 34,149 |
| Accepted transfers/s, same business window | 189.46 |
| HTTP/business failure rate | 0% |
| Wallet-read p95 | 582.51ms |
| Transfer p95 /p99 | 647.01 /901.58ms |
| Post-traffic delivery drain | 285.12s |

All traffic, services and infrastructure shared a4-vCPU, approximately16GiB Ubuntu runner (AMD EPYC7763 model string; assigned CPUs4), Java21 and k61.3.0. HTTP used loopback through Gateway. Workload was approximately70% reads and30% new INR1 transfers, one shared sender wallet. Core pool10, notification pool3, Redis500ms timeout, protection enabled at recorded test budgets, outbox batch20/poll100ms. This differs from the8GB Windows laptop.

## Financial and asynchronous delivery checks

For the confirmation, the database transfer delta equalled34,149 accepted transfers, with zero committed-but-unacknowledged transfers. Across preflight, warmup, search and confirmation,57,239 transfers and8 demo topups produced57,247 outbox events. All57,247 events were published and consumed, and114,486 expected in-app notifications were present. Negative balances, unbalanced operations, duplicate keys/notices, per-wallet ledger mismatches, failed outbox events and open dead letters were all zero. Wallet funds equalled issued funds (80,000,000 paise).

There were33,401 unpublished events immediately after confirmation traffic. The queue then cleared during285.12seconds of drain. This verifies eventual delivery after load; it does not demonstrate continuous notification throughput at the API request rate or sub-second notification latency. The confirmation and concurrency objects in report.json refer to the same run and must not be counted twice.

The [original baseline](capacity-baseline.md) failed delivery with2000ms outbox polling. The tuned run used100ms. Assigned CPU model and warmup conditions also differ between runs, so the two runs do not support a precise causal API speedup percentage.

Methodology and reproduction: [capacity test guide](github-capacity-test.md). Failure rules remain HTTP/business errors<1%, read p95<1000ms and transfer p95<2000ms, plus financial and eventual-delivery reconciliation. No universal maximum, multi-instance scaling result or10,000-concurrent-request capacity is claimed.
