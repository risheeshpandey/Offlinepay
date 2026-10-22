# Verification - Milestone 4

Date: 19 October 2026. Target: Java 21, Spring Boot 3.5.16, PostgreSQL 17.6. Local development broker image: apache/kafka:3.9.1; Redis:7.4.11-alpine. This is a virtual-money portfolio application.

## Executed locally

OpenJDK 17.0.20 + Maven 3.9.11 with the explicit Java override (this environment has Java 17):

```text
mvn -B -Djava.version=17 package
Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| Suite | Passed |
| --- | ---: |
| MoneyTest | 2 |
| PaymentFoundationTest | 10 |
| Milestone2Test | 13 |
| RequestLoggingFilterTest | 2 |
| Milestone2Part2Test | 11 |
| CookiePolicyTest | 1 |
| Milestone3Test | 23 |
| Milestone4Test | 12 |
| KafkaNotificationTest | 1 |
| NotificationWebhookTest | 4 |
| RequestRateLimitFilterTest | 4 |
| **Total** | **83** |

The 21 new checks cover money/outbox commit and rollback; no extra event on payment retry; single active lease, reclaim and stale-worker fencing; persistent failed-publish backoff/parking; publish-before-marker crash/redelivery; 12 concurrent duplicate inbox deliveries; correct distinct participant notices; malformed/key/forged-content rejection; private list/read APIs and stable readAt; notification delivery retries/parking without financial effects; delivery lease recovery; idempotent DLT offset archive; actual embedded Kafka transport of duplicate records and poison-message DLT/archive; in-app-only default; HTTP provider idempotency key, circuit opening/half-open recovery, semaphore bulkhead and socket read timeout; filter 429/Retry-After, Redis-failure 503, verified JWT key and health bypass.

KafkaNotificationTest starts an actual in-process KRaft broker from spring-kafka-test; no Kafka Docker container is required for that test. It uses H2 for inbox/outbox storage. NotificationWebhookTest starts a loopback HTTP server and calls the Spring-proxied Resilience4j annotations. Filter tests mock the Redis limiter; they do not prove the Lua script on a Redis server.

All 62 previous tests were rerun with the new outbox listener: money precision, transaction rollback, locking/idempotency, refresh cookie/CSRF/session replay, device/certificate/local proof, offline settlement/release, concurrent conflict/overspend/UUID races and IST response behavior still pass. These are functional tests, not request-capacity measurements.

The executable Spring Boot JAR packaged successfully. Archive inspection confirms V7/new classes are present and test-only private keys are absent.

## Database and deployment boundary

H2 uses the V1+V3+V4+V5+V6+V7 fixture and Hibernate validation. It does not execute PostgreSQL deferred/append-only triggers or prove V7 migration on PostgreSQL. V1-V6 migration bytes are unchanged from the Milestone 3 commit (3cf4740). V7 adds only outbox, consumed-event, notification and dead-letter tables/indexes/constraints. No old events are backfilled, no deadlines/balances/certificate data are rewritten.

Docker is unavailable in this execution environment. The following are implemented but **NOT executed here**:

- PostgresPaymentIT, PostgresMilestone2IT, PostgresPart2IT, PostgresMilestone3IT.
- PostgresMilestone4IT: repeats M4 DB/transaction/lease/ownership checks with PostgreSQL Testcontainers/Flyway.
- RedisRateLimitIT: 50 concurrent calls, exactly 10 allowed and a positive key TTL on a real Redis container.
- Compose startup, named-volume permissions/persistence, Windows Java21 default build, actual PostgreSQL V6 -> V7 upgrade.
- Full Docker-backed `mvn verify` and GitHub Actions (GitHub publication remains deferred).

Real SMS/push was not sent. Webhook delivery was verified against a local test provider only. External delivery deduplication depends on the provider honoring Idempotency-Key. Single-node local Kafka is not a production HA/security deployment. No benchmark, RPS, p95/p99, 10K concurrency or production availability claim is made.

## Earlier manual evidence from the user

On 19 October the user tested Milestone 3 on Windows/PostgreSQL: INR40 + INR60 offline proofs settled; exact replay returned the original receipt; final sender INR650/receiver INR350/reserve0; INR300 unused reserve release/replay; invalid signature, wrong receiver token, no token401, owner-only release, early release rejection, saved receipt and paired ledger postings all checked. That evidence belongs to M3 and does not establish M4 deployment success.

## Remaining product work

See milestone-4.md for accepted protocol/mobile backlog and manual failure lab. No automatic reserve-release scheduler, late-claim adjudication, sender-side proof submission, actual phone exchange, mobile auto-sync/local reminders or longer policy default has silently been added. The rejected early-release/no-server-record idea and INR100 fine are not implemented. Milestone 3 entity/repository and V5/V6 detailed teaching remains deferred.


## Milestone 5 verification

Executed locally after M5 changes: **99 tests passed, zero failures/errors/skips** across the core and service modules: core86 (the previous83 plus3 split-contract tests), notifications10 (remote inbox/auth/rollback/concurrency/migration-mode tests plus a real HTTP Feign contract test), gateway3 (real HTTP upstream routing/cookies/header boundaries). Runtime here is OpenJDK17 with `-Djava.version=17`; project and CI target Java21.

Commands: `mvn -f services/pom.xml -Djava.version=17 test` for the initial core regression; after service fixes, `mvn -f services/pom.xml -pl notifications,gateway -Djava.version=17 test`. The successful final service run is recorded in Surefire XML. Notification consumer startup suppression is explicitly tested, so initializing the new schema does not accidentally advance group offsets. Feign/session tests cover401,503 and propagated429.

Service startup testing found and corrected mixed Resilience4j2.2/2.3 transitive versions, a listener/factory initialization cycle, and dependency authentication outage handling. The notification module imports the Resilience4j2.3 BOM before the Cloud BOM. Mockito uses the same subclass test mock maker as the existing project.

The reactor packages three independently executable Spring Boot JARs. Shared notification source is compiled without including wallet/auth/outbox repository classes in the notification JAR. Full Windows/PostgreSQL/Kafka/Redis cutover acceptance is **pending**; use docs/milestone-5.md. No Docker binary is available in this execution environment, so existing Docker integration tests and the new PostgresNotificationIT are not reported as run. The latter exercises the actual notification Flyway migration and PostgreSQL duplicate-key concurrency when `verify` runs with Docker. We have not measured heap/RSS under this user's three-service Windows workload; configured heap caps are initial tuning values.

M4 manual evidence already observed on the user's machine: successful transfer/replay, one notice per participant, stable read timestamp, cross-owner404, Kafka outage recovery with7 publish attempts, Redis fail-closed503/recovery,429 reset and poison-event DLT archive. This is M4 evidence, not proof of an M5 deployment.

No RPS, p95/p99, maximum concurrency, production uptime, public GitHub deployment or Android automatic-sync claim is made.


## M5 Eureka add-on (port8762)

Implemented a standalone Eureka registry plus core, notification and gateway client registration. Default public routes use lb:// service IDs; blank default Feign URL resolves offlinepay-core through Spring Cloud LoadBalancer/Eureka. A fixed diagnostic URL remains an explicit override; the default acceptance flow clears it. No database migration or money transaction boundary changed.

Executed locally on OpenJDK17 (`-Djava.version=17`), with project/CI still targeting Java21: **99 tests, zero failures/errors/skips in final Surefire results** (core86, notifications10, gateway3). An initial busy-host run exposed an existing M4 lease test timing assumption: its mock clock advanced from test start while BCrypt/payment creation used actual wall time, so the event could still be in the future. The test helper now anchors its clock to the committed outbox next-attempt timestamp; the full12-test M4 suite then passed. No production lease behavior was changed to make a test pass.

Supplementary actual-process transport test also passed: a real Eureka server, real Gateway and real notification application/Feign client run on random loopback ports. An isolated H2 database and a small fake core endpoint stand in for PostgreSQL and the financial core. Evidence checks: registry/dashboard health, client registration, gateway name-based core/notification routing, real Feign identity lookup, invalid Bearer401, internal-route404, missing-instance503, core deregistration and eventual503 after cache refresh. This exercises real discovery without relying on localhost9031/9032. It does not prove the complete PostgreSQL/Kafka financial stack or actual Windows RAM usage.

CI now runs the supplementary transport harness after reactor verify. Docker integration checks remain unexecuted here; the user's Windows/PostgreSQL/Kafka/Redis Eureka acceptance is the next step. Registry self-preservation remains enabled, and cached/stale instances mean no instant-failover guarantee. No RPS, latency percentile, maximum concurrency or HA claim is added.

Manual progress before Eureka: user built the M5 JARs on Java21, initialized and migrated the notification database (2 consumed markers,4 notices,1 DLT), verified matching source/destination counts, and successfully started core9031, notifications9032 with assigned Kafka partitions, then Gateway9030. The JWT import issue was fixed by allowing digits in the environment variable name regex. Those are pre-Eureka deployment observations.

## User-reported M5 Eureka acceptance (22 October 2026 IST)

The user verified the four applications UP, preserved inbox migration counts/read timestamps, gateway wallet/login, a new transfer and replay without a second debit/notice, cross-owner404, stable mark-read timestamp, notifications-down outbox publish and catch-up on restart, no-auth401, private-route404, identity-down503 and recovery, refresh200 with new AT and HttpOnly RT cookie. Core was moved to9035 and Gateway/notification Feign continued resolving it through Eureka. Latest reported Bob wallet354 and4 notifications. This is manual evidence from the user's Windows/PostgreSQL/Kafka/Redis setup, not authoring-host performance measurement. Normal M6 startup restores core9031.

## M6

See [milestone-6-report.md](milestone-6-report.md) for exact executed100-test and actual-process functional evidence, Docker initialization failures and static configuration validation. The user subsequently executed PostgreSQL 1/5/10-VU,30-second local runs: all configured thresholds passed and both databases reconciled at each checkpoint. The10-VU run achieved163.10 mixed requests/s, transfer p95/p99 333.32/481.82ms and zero request failures. Final cumulative counts:2,982 transfers,2,986 published/consumed events,5,968 expected/actual notices; zero negative wallets, unbalanced operations, duplicate keys/notices or open DLT;40,000,000paise wallet funds equal issuance. A post-run outbox backlog drained on a later check; its exact elapsed time was not measured. [Full scope and credential-free evidence](m6-load-results.md). No maximum-capacity, sustained notification-rate or HPA runtime claim is made. Code reading of M4-M6 is deferred until after M6 implementation/testing, per the updated user plan.
