# Hosted capacity results

**Highest confirmed passing concurrency: 300 virtual users.** The same 180-second confirmation observed **754.29 mixed business requests/sec**, **0% HTTP/business failures** and **744.73ms transfer p95**. The next tested level, 500 VUs, failed the wallet-read latency rule. Untested counts between 300 and 500 do not establish an exact maximum.

[Actual GitHub Actions run 39769439816](https://github.com/risheeshpandey/Offlinepay/actions/runs/39769439816) used source `103cb588dbc55c8ada5f571dde0a7cb0daf1ef42`. [Retained report](../benchmarks/evidence/extended-39769439816/report.json), [native 300-VU summary](../benchmarks/evidence/extended-39769439816/confirm-concurrency-300.json), [native 500-VU failure](../benchmarks/evidence/extended-39769439816/search-500.json) and [stage CSV](../benchmarks/evidence/extended-39769439816/stages.csv) remain in Git after workflow artifacts expire.

## Test environment and rules

Four assigned vCPUs, 15,989MiB RAM, Ubuntu 24.04 and Java 21.0.12.1; CPU model string Intel Xeon Platinum 8370C. Application JVMs, infrastructure and k6 shared the runner. HTTP traffic used loopback through Gateway, Eureka discovery was enabled, and Kafka delivered to a separate notification service/database.

The workload was approximately 70% wallet reads and 30% new INR 1 online transfers, sharing one sender wallet. Each stage created a fresh account pair and virtual funding. This does not benchmark offline signature verification, mobile transport or distinct customer wallets.

Core/notification pools 10/3, heap caps 512/256MiB (Gateway 256, Eureka 192), outbox batch 20/poll 100ms, Redis timeout 500ms, enabled test budgets 10,000 auth/min and 100,000 API/min, trace sampling 0 and structured request logs. The normal application outbox default remains 2000ms; the CI deployment explicitly overrides it. This is a different deployment from the 8GB Windows laptop.

Rules stayed unchanged: HTTP/business failures each <1%, wallet-read p95<1000ms, transfer p95<2000ms, financial reconciliation and eventual delivery. The search stops at its first failing level, then selected candidates receive separate 180-second confirmations. 500 failed, so 1000 VUs was not executed.

## All observed stages

| Stage | VUs | Seconds | Mixed req/s | Accepted transfers | Read p95 ms | Transfer p95 ms | HTTP failures | Delivery drain s | Overall |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| warmup | 5 | 20 | 117.44 | 698 | 62.91 | 127.57 | 0.0000% | 5.48 | Pass |
| search-10 | 10 | 30 | 245.21 | 2,201 | 38.35 | 162.19 | 0.0000% | 10.83 | Pass |
| search-25 | 25 | 30 | 553.18 | 4,976 | 58.66 | 147.08 | 0.0000% | 37.54 | Pass |
| search-50 | 50 | 30 | 719.29 | 6,453 | 107.74 | 165.77 | 0.0000% | 48.78 | Pass |
| search-100 | 100 | 30 | 729.36 | 6,502 | 241.68 | 301.88 | 0.0000% | 54.23 | Pass |
| search-200 | 200 | 30 | 755.85 | 6,642 | 477.96 | 537.78 | 0.0000% | 54.66 | Pass |
| search-300 | 300 | 30 | 742.37 | 6,430 | 700.97 | 850.04 | 0.0000% | 55.11 | Pass |
| search-500 | 500 | 30 | 778.99 | 6,607 | 1101.25 | 1423.04 | 0.0000% | 55.20 | Fail |
| confirm-throughput-200 | 200 | 180 | 725.27 | 38,981 | 510.58 | 562.51 | 0.0000% | 307.88 | Pass |
| confirm-concurrency-300 | 300 | 180 | 754.29 | 40,536 | 659.60 | 744.73 | 0.0000% | 320.25 | Pass |

Rates exclude setup and use the observed business interval, including the completion tail. The short search selected 200 VUs for throughput and 300 for concurrency; in the longer confirmations, 300 VUs happened to have the higher observed rate. That variability does not prove a general speedup from adding users.

## Confirmed 300-VU numbers

136,156 business calls and 40,536 accepted transfers completed during an observed 180.509-second interval: 754.29 mixed requests/sec and 224.56 accepted transfers/sec. HTTP failures, business failures, 5xx responses, 429 rejections and transport errors were all zero. Read p95 was 659.60ms; transfer p95/p99 were 744.73/953.05ms. The database transfer delta matched 40,536 accepted responses exactly, with zero committed-but-unacknowledged transfers.

Immediately after traffic there were 39,762 unpublished events. Delivery then completed during 320.25 seconds of drain. The separate 200-VU confirmation required 307.88 seconds of drain. This verifies eventual completion after load; it does not establish continuous notification throughput at the API request rate or sub-second notification latency.

## Financial and delivery reconciliation

Across preflight, warmup, every search stage (including the latency-failing 500-VU stage) and both confirmations:

| Invariant | Observed |
| --- | ---: |
| Transfers / demo topups | 120,027 /11 |
| Operations / outbox events / consumed events | 120,038 /120,038 /120,038 |
| Expected / actual in-app notifications | 240,065 /240,065 |
| Negative wallets / unbalanced operations | 0 /0 |
| Duplicate keys / duplicate notices | 0 /0 |
| Per-wallet journal mismatches | 0 |
| Failed outbox events / open dead letters | 0 /0 |
| Wallet funds / issued funds, paise | 110,000,000 /110,000,000 |
| Unpublished events after final drain | 0 |

Counts are cumulative across this suite; do not attribute 120,038 operations to only the 300-VU confirmation. The 500-VU stage had zero request errors and correct financial/delivery reconciliation, but its 1101.25ms read p95 exceeded the 1000ms rule. Transfer p95 at 500 VUs was 1423.04ms. This is a latency boundary under this workload, not a crash or a universal limit.

## Earlier runs and limits

The [original 2000ms-poll baseline](capacity-baseline.md) failed notification drain despite successful API/financial checks. The [initial tuned run 39766611980](https://github.com/risheeshpandey/Offlinepay/actions/runs/39766611980), [retained report](../benchmarks/evidence/tuned-39766611980/report.json), confirmed 200 VUs at 635.47 mixed req/s and 647.01ms transfer p95 with zero failures; delivery drained in 285.12 seconds. It used 4 assigned vCPUs (AMD EPYC 7763 model) and approximately 16GiB RAM. Hardware model and warmup conditions vary across hosted runs, so these observations do not support a precise causal API improvement percentage.

The measured passing/failing bracket is 300/500 VUs; intermediate counts, longer soak tests, different wallet distributions, network/TLS latency, multiple core instances, HPA behavior and production availability remain unmeasured. No unconditional maximum or 10,000-concurrent-request claim is made. See [reproduction methodology](github-capacity-test.md), [Java CI evidence](ci-validation.md), and [resume notes](resume-notes.md).
