# M6 measured PostgreSQL load results

Evidence: user-provided k6 v1.3.0 JSON, PowerShell exit status and read-only SQL outputs from the Windows laptop, 22 October 2026 IST. These runs were executed by the user with guided commands, not on the authoring host. Selected credential-free measurements are in `m6-load-results.json`.

## Configuration and scope

- Java 21.0.11; Windows amd64; approximately 8 GB RAM. Maven 3.9.11. k6 and all services share the same laptop.
- Gateway 9030, core 9031, notifications 9032, Eureka 8762; core/notifications use separate PostgreSQL load databases. Dedicated Kafka load topics and Redis DB 1 preserve the ordinary demo.
- PostgreSQL 17.6, Kafka 3.9.1, Redis 7.4.11 in Docker. Java heap caps: core 384 MiB, other three services 256 MiB each. Heap caps do not equal total process memory.
- Each mixed run lasts 30 active seconds, with 1, 5 or 10 closed-model virtual users, one shared sender/receiver, approximately 70% wallet reads and 30% new INR 1 transfers. VUs are test workers, not distinct customers.
- Redis request protection remains enabled with test-only budgets of 10,000 auth requests/minute and 100,000 API requests/minute. Normal protection limits are different. JSON logging remains enabled; trace sampling is 0.
- k6 rates below divide by total test runtime including setup, approximately 31-32 seconds. Each run has nine additional setup HTTP requests. Do not relabel the mixed request rate as payment TPS.

## Results

| Measurement | 1 VU | 5 VUs | 10 VUs |
|---|---:|---:|---:|
| Business requests | 1,448 | 3,399 | 5,131 |
| Wallet reads | 1,015 | 2,384 | 3,598 |
| Accepted transfers | 433 | 1,015 | 1,533 |
| Business requests/s, k6 reported | 46.10 | 107.00 | 163.10 |
| Accepted transfers/s, k6 reported | 13.79 | 31.95 | 48.73 |
| Wallet read p95 / p99, ms | 26.19 / 44.97 | 30.71 / 69.35 | 19.91 / 34.42 |
| Transfer p95 / p99, ms | 71.59 / 102.18 | 193.66 / 457.66 | 333.32 / 481.82 |
| HTTP / business failure rate | 0% / 0% | 0% / 0% | 0% / 0% |
| Rate-limited / server-error requests | 0 / 0 | 0 / 0 | 0 / 0 |
| Configured thresholds | All pass | All pass | All pass |

The 5- and 10-VU exits were explicitly reported as 0; the 1-VU JSON records all thresholds passing. Acceptance thresholds were wallet p95 < 1,000 ms, transfer p95 < 2,000 ms, HTTP and business failure rates < 1%. Better wallet-read latency at 10 VUs does not establish a tuning improvement: these are short sequential runs with different warm-up/cache/resource conditions. Increased transfer latency with concurrency is consistent with hot-wallet serialization, but no profiling established the bottleneck.

## Money, journal and asynchronous delivery reconciliation

Before the mixed tests, the replay fixture passed 11/11 checks. Two concurrent identical transfer requests returned one transaction, and a changed amount with the same key was rejected. PostgreSQL confirmed one INR 1 transfer and balances Alice INR 99,999 / Bob INR 1 after INR 100,000 issuance.

The following counts are cumulative across that replay fixture and all mixed tests. Every checkpoint reported zero negative wallets, zero unbalanced operations, zero duplicate keys, zero duplicate notices and zero open dead letters.

| Checkpoint after queue drain | After 1 VU | After 5 VUs | After 10 VUs |
|---|---:|---:|---:|
| Demo topups | 2 | 3 | 4 |
| Committed transfers | 434 | 1,449 | 2,982 |
| Wallet total = issued total, paise | 20,000,000 | 30,000,000 | 40,000,000 |
| Published outbox events | 436 | 1,452 | 2,986 |
| Consumed events | 436 | 1,452 | 2,986 |
| Expected = actual notices | 870 | 2,901 | 5,968 |

A topup produces one notice; each transfer here has two participants and produces two notices. Final arithmetic: 4 + 2,982 = 2,986 events; 4 + (2 x 2,982) = 5,968 notices. Total issued virtual money is INR 400,000 and remains in the wallets.

The first post-10-VU core snapshot showed 368 PENDING, 1 PROCESSING and 2,617 PUBLISHED events. The subsequent notification snapshot showed 2,658 consumed / 5,312 notices; queries ran at different instants while processing continued. A later check showed only 2,986 PUBLISHED, 2,986 consumed and 5,968 notices. No failure status remained. The queue drained successfully, but elapsed drain time was not timestamped. Do not infer a sustained notification processing rate from these snapshots or from API RPS. The configured relay claims up to 20 events per poll with a 2-second delay; burst acceptance can exceed background delivery speed.

## Resources and limits of the evidence

Before the 10-VU run Windows reported 8,266,868 KiB total visible memory and 1,192,928 KiB free, approximately 1.14 GiB free. A nearby Docker snapshot showed Redis 9.715 MiB, Kafka 731.1 MiB and PostgreSQL 116.2 MiB. These are pre-run samples, not peak measurements. Peak CPU/RAM, GC effects, a steady-state soak, saturation point, fixed-arrival-rate behavior, multi-instance balancing and HPA scaling were not measured. PostgreSQL query-plan tuning and ELK/Zipkin Docker/Kubernetes runtime acceptance remain separate work. Successful user database checks do not mean the authoring host's Docker integration tests ran.

**This is achieved local throughput under the stated workload, not maximum capacity, 10,000 concurrent users, production readiness or sustained end-to-end notification capacity.**

## Resume wording supported by the evidence

Load-tested a Spring Boot/PostgreSQL wallet backend with k6 at 10 virtual users, recording ~163 requests/sec across a 70% read / 30% transfer workload and 333 ms transfer p95 in a 30-second local run, with zero request failures.

Validated 2,982 cumulative transfers using SQL reconciliation: balanced ledger, conserved funds, zero duplicate operations, and complete delivery of 5,968 notifications after the Kafka/outbox backlog drained.

## Export safety

The initial 1-VU report accidentally included setup access tokens; the corrected summary exporter allowlists metrics, group checks, state and generated metadata. This evidence file never copies tokens, setup_data, passwords or private keys. See `m6-first-load.md` for the correction and existing-file sanitation instructions. Raw local summaries remain ignored by Git.
