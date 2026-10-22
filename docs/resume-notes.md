# OfflinePay: resume and interview notes

Project: https://github.com/risheeshpandey/Offlinepay

## Copy-ready project bullets

- Built a Java 21/Spring Boot payment backend with PostgreSQL and JPA/Hibernate, implementing signed offline allowances, reserved balances and transactional settlement with durable idempotency.
- Integrated Kafka transactional-outbox delivery and an independent notification service using Spring Cloud Gateway, Eureka and OpenFeign; implemented JWT authentication, rotating HttpOnly refresh cookies, CSRF protection and Redis request limits.
- Benchmarked wallet-read/online-transfer APIs at **754 requests/sec with 300 concurrent virtual users** in a **180-second k6 test** on a **4-vCPU/16GiB runner**, observing **zero request failures** and **745ms transfer p95** under a 70% read/30% transfer workload.
- Reconciled **120,038 financial operations and 240,065 in-app notifications** across benchmark stages after queue drain, with zero ledger imbalances or duplicate notifications; passed **184 Java unit/integration tests** in GitHub Actions.

Use the bullets that fit your resume. Keep the workload, duration and hardware alongside the performance claim. 500 VUs failed the read-latency rule; 300 is the highest tested and confirmed passing count, not an exact universal maximum.

## Skills demonstrated in code

| Area | Skills | Where to explain them |
| --- | --- | --- |
| Java backend | Java 21, REST, Spring Boot, Lombok, Java records, JPA/Hibernate | Controllers, service records, annotated entities/repositories |
| Transactional data | PostgreSQL, Flyway, ACID, row locks, lock ordering, integer money, balanced ledger, durable idempotency | WalletService, WalletRepository, LedgerService, PaymentOperationRepository, migrations |
| Offline protocol | Ed25519, canonical payloads, device possession proof, allowance certificates, proof validation and settlement | Ed25519, DeviceService, OfflineProtocol, OfflineProofVerifier, OfflineSettlementService |
| Authentication | Spring Security, BCrypt, JWT, refresh-token rotation/reuse detection, HttpOnly cookies, CSRF, ownership checks | SecurityConfig, AuthService, RefreshTokenService, AuthCookies |
| Distributed delivery | Kafka, transactional outbox, retries, leases, idempotent inbox, dead-letter handling | OutboxRelay/OutboxTransactions; notifications KafkaInbox/InboxTransaction |
| Service boundaries | Spring Cloud Gateway, Eureka, OpenFeign, internal identity validation | services/gateway, services/discovery, notifications CoreClient/RemoteSecurity |
| Request protection | Redis Lua counters, rate limits, fail-closed behavior | RedisRequestLimiter, RequestRateLimitFilter |
| Verification | JUnit, Mockito, Testcontainers, k6, SQL reconciliation, GitHub Actions, Maven | src/test, services/*/src/test, benchmarks, .github/workflows |
| Observability | Micrometer/Prometheus, W3C tracing, JSON logs, MDC correlation | observe profiles, shared logback config, RequestLoggingFilter, metrics classes |
| Reproducible runtime | Docker Compose, heap budgets, isolated databases/topics, scoped cleanup | compose.capacity.yaml, scripts/run_capacity_ci.py |

## Know the measurement before an interview

The workload is approximately 70% balance reads and 30% fresh INR 1 online transfers, sharing one sender wallet. 754 mixed requests/sec is not 754 payment transfers/sec: the observed accepted-transfer rate was 224.56/sec. 300 VUs means 300 active k6 workers. A closed-model worker waits for its previous request; this is not a fixed 1000-RPS arrival-rate result.

The 300-VU confirmation completed 136,156 business calls and 40,536 transfers over 180.509 business seconds. Read p95 was 659.60ms, transfer p95/p99 were 744.73/953.05ms, and HTTP/business failures were 0%. 39,762 events remained unpublished immediately after traffic; all delivery completed during 320.25 seconds of drain. This is eventual delivery after load, not continuous notification processing at 754 req/s. Financial correctness was checked independently of HTTP responses.

The final full-suite totals include preflight, warmup, all search stages (including the latency-failing 500-VU stage) and both 180-second confirmations. They are not counts from a single 180-second stage. Every operation had an outbox event, every event was consumed and all 240,065 participant notifications were present after drain.

The deployment uses one core instance, one notification instance and loopback HTTP, with the load generator on the same runner. Test budgets keep Redis protection enabled at 10,000 auth/min and 100,000 API/min. Outbox polling is 100ms in this deployment, compared with the normal 2000ms application default. It differs from the 8GB Windows laptop.

Runtime horizontal scaling, a mobile app, QR/BLE/NFC exchange, an SMS provider and Kubernetes/HPA behavior remain unmeasured or planned. Describe the implemented backend and measured test rather than claiming those capabilities.

## Evidence

- [Current capacity measurements, failure boundary and retained reports](capacity-results.md)
- [184 Java cases and hosted CI links](ci-validation.md)
- [Capacity methodology and reproduction](github-capacity-test.md)
- [Skills and code guide](OfflinePay_Skills_and_Code_Guide.pdf)
