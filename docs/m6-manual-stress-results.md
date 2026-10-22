# Additional Windows stress observations — 22 October 2026, IST

Source: the user's pasted k6 summaries, reconciliation outputs and correlated application logs. These are user-executed Windows runs, not independent authoring-host benchmarks. Raw summary files for these later runs have not been attached. The earlier uploaded automatic-run ZIP is analyzed separately in `m6-automated-load-analysis.md`.

## Direct arrival-rate attempts

| Target | Allocation | Completed business requests | Observed business-window RPS | Dropped iterations | Result |
| --- | --- | ---: | ---: | ---: | --- |
| 1,000/s | maximum40 VUs | 460 | 55.3683 | 7,818 | Aborted on zero-drop threshold, exit99 |
| 50/s | preallocated10, maximum20 VUs | 396 | 49.2967 | 6 | Aborted on zero-drop threshold |

The first run had112 client-recorded accepted transfers, HTTP failure rate0, transfer p95 1479.13ms and read p95 992.48ms. The second had HTTP failure rate0, transfer p95 446.46ms and read p95 70.45ms. Neither completed its scheduled30-second stage. Dropped iterations are unsent schedules, not successful requests; zero HTTP failures does not make either attempt pass. A bounded worker pool and co-located resource pressure affect these outcomes, so they do not establish the backend maximum.

The automatic runner's isolation probe initially received a Gateway login504. Subsequent direct-core login took0.832s and Gateway login0.792s. Manual API wallet ID and latest notification ID both matched the isolated SQL databases. A PowerShell pipeline initially displayed a blank notification row; parentheses around `Invoke-RestMethod` exposed the array correctly. That display issue alone does not prove the remaining runner-preflight failure's cause. No probe was removed or marked successful without comparing the data.

## Original fixed-VU mixed workload

Run: `m6-mixed50-20261022-131659`, `mixed.js`, 50 virtual users, 30-second stage, about70% reads /30% transfers sharing one sender. The CLI reported3275 complete iterations, zero interrupted and exit0.

| Metric | Measured value |
| --- | ---: |
| Business requests | 3,275 |
| Business counter rate, including setup in denominator | 103.37795/s |
| Accepted transfers | 944 |
| Business failures | 8 /3275 =0.24427% |
| HTTP failure rate, including setup requests | 0.24361% |
| Server responses at least500 | 8 |
| Rate-limited responses429 | 0 |
| Transfer p95 /p99 | 1317.79 /1591.72ms |
| Wallet-read p95 | 750.03ms |

This passed the original `<1%` failure budgets and read/transfer p95 limits1s/2s. It was not a zero-error run and was not a fixed1,000-RPS test. The observed business-window denominator was not recorded by this older `mixed.js`, so do not relabel103.38/s as a setup-excluding metric. The new CI wrapper records both denominators explicitly.

All eight correlated core logs were `GET /api/v1/wallets/me status=503` at13:17:12.90–12.94 IST, duration636–678ms. No matching `database_operation_failed` message was in that extracted result. Redis protection timeout is a hypothesis because the configured timeout is500ms and its catch returns503 without an exception log. The actual response error codes and root exceptions were not captured, so the cause is unconfirmed. `RateLimited=0` rules out observed429 responses, not Redis-protection unavailability. The CI wrapper adds allowlisted error-code counters without changing Redis timeouts or disabling protection.

## Final cumulative reconciliation

- Negative wallets, unbalanced operations and duplicate idempotency keys:0.
- Wallet and issued funds:110,000,000paise each (INR1,100,000 virtual funds).
-11 demo topups,10,044 transfers;10,055 total outbox events, all published and consumed.
-20,099 expected and actual notifications; duplicate notices0; open dead letters0.

These totals cover all load runs in those databases. They are not the counts produced by the50-VU run alone. Earlier higher short-stage throughput and this later50-VU observation have different concurrency/resource conditions; neither should be discarded or combined into a universal capacity claim.

Current defensible scope: load-tested50 concurrent virtual users for30 seconds, about103 mixed API requests/s,0.244% business failures, transfer p95 about1.32s, and clean eventual financial/notification reconciliation. A longer independent CI confirmation is pending. Do not claim50 is the maximum user capacity,1,000 RPS achieved, zero HTTP failures in this run, or sustained real-time notification throughput.
