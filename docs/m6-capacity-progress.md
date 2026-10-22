# Continued capacity exploration before automation

User-reported Windows/PostgreSQL results after the reconciled1/5/10-VU baseline. Each stage is30 active seconds, same-host k6v1.3.0, one hot sender, approximately70% reads /30% new INR1 transfers. Both exits were0, thresholds passed, HTTP/business failure rates0, rate-limited0 and server errors0. These are observations, not maximum-capacity claims.

| Measurement |20 VUs |30 VUs |
|---|---:|---:|
| Total k6 runtime including setup,ms |31,550.6089 |31,463.6872 |
| Business requests |5,790 |5,976 |
| Accepted transfers |1,718 |1,766 |
| Business requests/s |183.51468329348154 |189.93323833959295 |
| Accepted transfers/s |54.452197909879324 |56.128195935026966 |
| Wallet read p95 /p99,ms |103.14415499999998 /145.149748 |188.28338 /238.6019609999999 |
| Transfer p95 /p99,ms |340.82206 /415.597129 |473.00334999999995 /622.0107499999988 |

After20 VUs: all4,705 outbox events published/consumed,9,405 expected/actual notices,4,700 cumulative transfers,5 topups and50,000,000paise wallet/issued total. Negative wallets, unbalanced operations, duplicate keys/notices and open DLT all0. A first snapshot had647 pending; a later check confirmed complete drain. Free physical memory after drain912,556KiB of8,266,868KiB visible.

After30 VUs: financial checks passed with6,466 cumulative transfers,6 topups,60,000,000paise wallet/issued total and zero negative wallets, unbalanced operations or duplicate keys. At the captured core snapshot740 were PENDING,1 PROCESSING,5,731 PUBLISHED; the subsequent notification snapshot showed5,805 consumed /11,604 notices, duplicates0 and open DLT0. Final drain confirmation for this stage was not provided before automation was requested. Expected final counts:6,472 events and12,938 notices. Free memory was746,688KiB. The automatic runner must drain/check that backlog before starting another stage.

Throughput rose about3.5% from20 to30 VUs while read/transfer latency increased. This suggests flattening for this local workload; lock-wait/CPU/GC profiling has not established the bottleneck. Samples above are not during-test peaks. The user requested automatic staged testing before code walkthrough; see `capacity-automation.md`. The earlier PDF remains a reconciled1/5/10-VU checkpoint until new automatic measurements and final checks are available.
