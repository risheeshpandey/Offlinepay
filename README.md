# OfflinePay

A Java 21 / Spring Boot backend prototype for payments during internet outages. Users reserve virtual wallet funds while online, create and verify signed payment proofs locally, and submit proofs for settlement when connectivity returns.

**Scope:** virtual-money prototype. Bank/UPI integration, phone-to-phone transport and automatic mobile synchronization are planned. Local proof verification is provisional and does not credit a receiver or guarantee global offline double-spend prevention.

## Implemented features

- Wallets, online transfers and a balanced, append-only payment journal.
- PostgreSQL transactions, deterministic wallet locking and durable idempotency, including changed-payload rejection.
- Device registration, Ed25519-signed offline allowances/proofs, reserved balances, receiver-authorized settlement and deadline-gated unused-fund release.
- JWT access tokens, rotating HttpOnly refresh cookies, CSRF protection, BCrypt password hashing and account ownership checks.
- Kafka payment events through a transactional outbox, with an independent notification service/database, retries, duplicate checks and dead-letter storage.
- Redis request limits, structured JSON logs, request correlation, Micrometer/Prometheus metrics and optional tracing/log-search tools.

## Architecture

| Component | Responsibility | Local port |
| --- | --- | ---: |
| Gateway | Public API routing through service discovery | 9030 |
| Financial core | Authentication, wallets, allowances, payments and ledger | 9031 |
| Notifications | Kafka consumption and user-owned in-app notifications | 9032 |
| Eureka | Service registration and discovery | 8762 |
| PostgreSQL | Separate core and notification databases | 5436 |
| Kafka | Payment events and dead-letter topics | 9094 |
| Redis | Request rate limiting | 6381 |

The financial core commits balance changes, the payment record, journal entries and the outbox event in one PostgreSQL transaction. A relay publishes committed events to Kafka; notification consumers handle duplicates without changing money. Gateway uses Eureka for routing, and the notification service uses OpenFeign to validate caller identity with core.

Amounts are accepted as decimal INR and stored as integer paise. Both wallets are locked in deterministic order before a transfer. A retry with the same idempotency key and payload returns the original committed result; a changed payload returns a conflict. Financial correctness depends on PostgreSQL transactions and constraints, rather than JVM-only or Redis locks.

## Stack

Java 21, Spring Boot 3.5.16, Spring Security, Spring Data JPA/Hibernate, PostgreSQL, Flyway, Kafka, Redis, Spring Cloud Gateway, Eureka, OpenFeign, Micrometer, Docker Compose, JUnit, Mockito, Testcontainers, Maven and GitHub Actions.

## Run locally

Prerequisites: Java 21, Maven, Docker Desktop/Engine and local environment values. See [service startup and configuration](docs/milestone-6.md) and [notification database setup](docs/milestone-5.md) for the complete initial setup.

1. Use `.env.example` as the environment template and generate private local secrets/signing keys as described in the setup documentation. Keep them outside version control.
2. Build the four applications:

   ```powershell
   mvn -f services/pom.xml package
   ```

3. Start infrastructure:

   ```powershell
   docker compose up -d postgres kafka redis
   docker compose ps
   ```

4. Initialize the notification database for a fresh setup, or follow the documented migration procedure for existing data. Start discovery, core, notifications and gateway using the [four application commands](docs/milestone-6.md#four-application-windows).
5. Use the Postman collection in `postman/` or the documented REST endpoints. The gateway entry point is `http://localhost:9030`.

The base Compose `app` service is an earlier single-application demo mode; it is separate from the four-JAR setup and shares port 9030 with Gateway. The default infrastructure command above starts only PostgreSQL, Kafka and Redis.

Configuration uses environment variables for credentials and endpoints. Host applications connect to PostgreSQL at `localhost:5436`, Kafka at `localhost:9094`, and Redis at `localhost:6381`; containers use the Compose service names and internal ports. Use the same `Asia/Kolkata` JVM setting described in the Windows setup when required by the JDBC timezone configuration.

## API and offline flow

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/auth/csrf` | Obtain the CSRF cookie/header token |
| `POST /api/v1/auth/signup` and `/login` | Create an account and authenticate |
| `POST /api/v1/auth/refresh` and `/logout` | Rotate or revoke the refresh session |
| `GET /api/v1/wallets/me` | Read the authenticated user's balance |
| `POST /api/v1/payments/transfer` | Transfer funds with an `Idempotency-Key` |
| `POST /api/v1/devices` | Register a public device key and possession signature |
| `POST /api/v1/offline-allowances` | Reserve funds for a registered device |
| `GET /api/v1/offline-allowances/{id}/certificate` | Retrieve the signed allowance |
| `POST /api/v1/offline-sync/payments` | Settle a signed proof as its receiver |
| `POST /api/v1/offline-sync/allowances/{id}/release` | Release unused funds after the sync deadline |
| `GET /api/v1/notifications` | List the authenticated user's notifications |

Offline proof creation/verification uses the Java CLI without a backend HTTP call. [Device and proof setup](docs/milestone-2-part-2.md) and the [settlement examples](docs/milestone-3.md) describe the signed payload and receipt flow. Proof signatures detect tampering; ordinary software-only device state can still be copied or rolled back. The server detects/rejects conflicting claims at settlement. Expiry does not automatically release reserved money; the stored synchronization deadline and explicit release rules apply.

## Tests and measured performance

Run all module unit/integration checks on Java 21 with Docker available for Testcontainers:

```powershell
mvn -f services/pom.xml verify
```

[Hosted Java CI](docs/ci-validation.md) passed **184 unique Java unit/integration cases**, with zero failures, errors or skipped tests. Additional discovery/Feign and observability smoke checks are documented separately.

An actual hosted k6 run confirmed **300 concurrent virtual users at approximately 754 mixed API requests/sec** over **180 seconds**, with **zero observed request failures** and **745 ms transfer p95 latency**. The deployment had 4 assigned vCPUs and approximately 16 GiB RAM; the workload was approximately 70% wallet reads and 30% new online transfers using one shared sender wallet.

The next tested level, 500 VUs, failed the wallet-read latency rule. Intermediate levels are untested, so 300 is the highest tested and confirmed passing VU count, not an unconditional maximum. The 300-VU notification queue completed delivery during 320.25 seconds of post-traffic drain; the API rate is not sustained real-time notification throughput or offline proof-verification throughput.

Across the full benchmark suite, **120,038 operations and 240,065 notifications** reconciled with zero ledger imbalances or duplicate notifications. Full settings, failed-stage evidence, native summaries and reconciliation are retained in [capacity results](docs/capacity-results.md), [benchmark evidence](benchmarks/evidence/) and [reproduction methodology](docs/github-capacity-test.md).

## Optional diagnostics and deployment examples

- [Kafbat UI and ELK](docs/kafka-ui-and-elk.md): inspect Kafka messages/consumer progress and search stored application logs. Compose configuration is validated; live Kafbat/ELK checks remain pending.
- [Metrics, readiness and tracing](docs/milestone-6.md): private management ports, JSON logs, Prometheus and optional Zipkin.
- [Kubernetes examples](deploy/kubernetes/README.md): application manifests; runtime scaling/HPA behavior remains unmeasured.
- [Historical local measurements](docs/m6-manual-stress-results.md) and [the outbox bottleneck baseline](docs/capacity-baseline.md): earlier observations with their original scope and limitations.

Retain local database volumes when stopping services. `.env`, local signing/device keys, payment proofs, logs and unreviewed benchmark outputs are excluded from version control. Demonstration top-ups issue virtual funds only; they do not connect to a bank.
