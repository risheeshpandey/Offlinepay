# OfflinePay

A Java backend portfolio project by **Risheesh Pandey**, implementing virtual-money wallets, retry-safe online transfers and signed offline payment proofs that settle when connectivity returns.

**Current implementation: Milestone 6.** Financial operations stay in a transactional core; notifications run as a separate service and database. Spring Cloud Gateway routes through Eureka, and OpenFeign validates notification access with core. Kafka transactional-outbox delivery, Redis request protection, structured logs and Micrometer metrics support testing and diagnosis. Phone-to-phone transport and automatic mobile sync remain planned work.

- **Financial correctness:** integer paise amounts, balanced journal entries, transaction boundaries, deterministic wallet locking and durable idempotency with changed-payload rejection.
- **Offline protocol:** device registration, Ed25519-signed allowances and proofs, reserved funds, receiver-authenticated settlement, duplicate/conflict checks and deadline-gated unused-fund release.
- **Authentication:** JWT access tokens, rotating HttpOnly refresh cookies, CSRF protection, BCrypt passwords and ownership checks.
- **Asynchronous delivery:** Kafka, an outbox, idempotent notification inbox, retries and dead-letter storage. Real SMS/push requires a provider adapter.
- **Verification:** [184 Java unit/integration tests passed](docs/ci-validation.md), with no failures or skipped tests. A separate hosted workflow measures capacity and reconciles the resulting financial and notification records.

**Stack:** Java 21, Spring Boot 3.5.16, Spring Security, Spring Data JPA/Hibernate, Lombok, PostgreSQL/Flyway, Kafka, Redis, Spring Cloud Gateway, Eureka, OpenFeign, Micrometer, Docker Compose, JUnit/Mockito, Testcontainers and GitHub Actions.

## Measured hosted performance

A real GitHub Actions run confirmed **300 concurrent virtual users**, **754 mixed requests/sec**, **0% HTTP/business failures** and **745ms transfer p95** during a 180-second test. The next tested level, 500 VUs, exceeded the wallet-read p95 rule (1101ms versus 1000ms).

This is a 4-vCPU/approximately 16GiB deployment, approximately 70% wallet reads/30% new INR 1 online transfers, one shared sender, with 100ms outbox polling and enabled test-only Redis budgets. The 300-VU confirmation accepted 40,536 transfers; notification delivery completed during 320.25 seconds of post-traffic drain. It is not sustained real-time notification throughput or an unconditional maximum.

Across the full suite, 120,038 operations and 240,065 in-app notifications reconciled with zero ledger imbalances or duplicate notices. [Exact results, retained JSON and failed 500-VU evidence](docs/capacity-results.md) and [copy-ready CV points and demonstrated skills](docs/resume-notes.md) provide the measurement scope.

## Run and inspect

- [Agreed current-code review, M7 order and deferred backlog](docs/project-roadmap.md)
- [Current startup, management ports and observability](docs/milestone-6.md)
- [Disposable GitHub Actions capacity test and measurement rules](docs/github-capacity-test.md)
- [Actual local stress observations and limitations](docs/m6-manual-stress-results.md)
- [Hosted capacity results and tested boundary](docs/capacity-results.md)
- [Hosted baseline: measured outbox bottleneck](docs/capacity-baseline.md)
- [Hosted CI validation evidence](docs/ci-validation.md)
- [Learning guide: concepts and code](docs/OfflinePay_Skills_and_Code_Guide.pdf)

Build all services with `mvn -f services/pom.xml package`. Local service ports are Gateway9030, core9031, notifications9032 and Eureka8762. The CI capacity workflow uses its own disposable infrastructure and ephemeral ports; it does not access laptop data or private keys.

The earlier milestone labs below document how the project evolved. Existing data, keys and personal code comments should be preserved when applying an upgrade.

## Why start here?

Balance movement, retry safety and journal correctness form the foundation for disconnected payments. Auth and financial writes remain in one core application so their database transactions stay local. Notifications have a separate service and database. Annotated JPA entities/repositories, Hibernate dirty checking and Lombok keep the application code readable.

## Milestone 4 reference

Read [the Milestone 4 upgrade and failure lab](docs/milestone-4.md). Start PostgreSQL, the separate OfflinePay Kafka (9094) and Redis (6381). Flyway adds V7 without rewriting old migrations/data/keys. Notifications appear for NEW committed operations, not historical retries. In-app delivery works by default; real SMS/push requires an external provider adapter. [Targeted operator recovery](docs/milestone-4-recovery.sql) is intentionally not a public API.

Agreed mobile/protocol follow-ups (signed sync deadline, longer configured grace, dual-party submission, automatic phone sync and 12-hour reminders) are tracked in the lab. They are not silently included in this infrastructure release.

## Milestone 3 reference

Read [the manual settlement lab](docs/milestone-3.md). Upgrade applies V6 without changing V1-V5 or replacing existing key/state/proof files. Sync payment proofs as the receiver, observe the reserved debit/receiver credit, repeat the exact proof and inspect the original receipt. Submit before the stored syncDeadline; default grace is 48 hours after allowance expiry. Release unused funds only after that deadline. V5 walkthrough remains deferred.

## Part 2 reference

Read [the manual Part 2 lab](docs/milestone-2-part-2.md) first. It explains upgrading, generating stable server keys via IntelliJ `OfflineClient.main`, cookie/CSRF Postman requests, device registration and offline proof verification. Keep existing `.env`, database and keys. V1-V4 stay unchanged; V5 is new. No script is required for the learning flow.

## Step 1: run on Windows

Install Docker Desktop and Git. Docker builds with Java 21 and Maven, so the Docker path does not require a local Maven installation. Use PowerShell from the extracted `offlinepay` directory:

```powershell
.\scripts\Setup-Local.ps1
# First generate local-keys with OfflineClient.main as explained in the Part 2 lab.
docker compose up --build -d
docker compose logs -f app
```

When the application has started, press Ctrl+C to leave the log view. The containers continue running. The app is at `http://localhost:9030` and PostgreSQL is at `localhost:5436`, Kafka at `localhost:9094` and Redis at `localhost:6381`, avoiding the existing eCommerce/Kafka ports. First build needs internet for images and Maven artifacts. Wallet/auth APIs require backend connectivity; OfflineClient pay/verify operate locally without the backend.

## Step 2: prove a transfer and its retry

```powershell
.\scripts\Demo.ps1
```

The script registers Alice and Bob, logs in, adds INR 1,000 of virtual demo money, transfers INR 250, replays the same request, checks changed payload returns 409, checks overspending returns 422, and verifies balances of INR 750 and INR 250. Demo funding is enabled only in the `demo` profile used by Compose. No bank connection or real-money funding exists.

## Step 3: inspect in DBeaver

Connect to database `offlinepay`, host `localhost`, port `5436`, user `offlinepay`, using the password in your local `.env`. Inspect `app_user`, `wallet`, `payment_operation`, `ledger_entry`, and `flyway_schema_history`.

```sql
-- Each committed operation must have exactly two equal opposing entries.
SELECT operation_id, COUNT(*) AS entries,
       SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) AS net_paise
FROM ledger_entry GROUP BY operation_id;

-- Wallet projection must agree with the journal.
SELECT w.id, w.available_paise,
       COALESCE(SUM(CASE WHEN l.account_kind='WALLET' AND l.entry_type='CREDIT' THEN l.amount_paise
                         WHEN l.account_kind='WALLET' AND l.entry_type='DEBIT' THEN -l.amount_paise ELSE 0 END),0) AS journal_paise
FROM wallet w LEFT JOIN ledger_entry l ON l.wallet_id=w.id
GROUP BY w.id,w.available_paise;
```

Expect `entries=2`, `net_paise=0`, and each wallet balance equal to its journal projection. In this accounting convention wallets are liability accounts, so credit increases a user's wallet and debit decreases it. Demo funding includes the opposing debit to a system issuance account.

## API contract

Amounts are decimal INR in JSON; storage uses integer paise. The sender is always derived from the authenticated JWT. Clients cannot choose another sender.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/auth/signup` | Create user and empty wallet atomically |
| GET | `/api/v1/auth/csrf` | CSRF cookie and masked header token |
| POST | `/api/v1/auth/login` | Access JWT in JSON, refresh in HttpOnly cookie |
| GET | `/api/v1/wallets/me` | Read own authoritative balance |
| POST | `/api/v1/demo/topups` | Add virtual money in demo profile |
| POST | `/api/v1/payments/transfer` | Transfer between wallets |
| GET | `/api/v1/payments/{id}` | Sender or receiver can inspect payment |
| GET | `/api/v1/wallets/me/ledger?page=0&size=20` | Own paginated wallet journal |
| POST | `/api/v1/offline-allowances` | Reserve available money for a registered active device |
| GET | `/api/v1/offline-allowances/{id}` | Read own reservation |
| GET | `/api/v1/offline-allowances` | List own reservations |
| POST | `/api/v1/auth/refresh` | Rotate refresh token and issue replacement access JWT |
| POST | `/api/v1/auth/logout` | Revoke the refresh session |
| POST | `/api/v1/devices` | Register public device key with possession signature |
| POST | `/api/v1/devices/{deviceId}/revoke` | Revoke device for new reservations/certificate retrieval |
| GET | `/api/v1/offline-allowances/{id}/certificate` | Owner's signed allowance |
| GET | `/api/v1/offline-protocol/server-key` | Trusted public signing key (pin before offline use) |
| POST | `/api/v1/offline-protocol/verify` | Receiver-bound proof check; no settlement |
| POST | `/api/v1/offline-sync/payments` | Receiver syncs signed proof; financial settlement |
| GET | `/api/v1/offline-sync/payments/{paymentId}` | Sender/receiver read committed receipt |
| POST | `/api/v1/offline-sync/allowances/{allowanceId}/release` | Owner releases unused reserve after deadline |
| GET | `/actuator/health` | Health status |

Signup: `{"name":"Alice","mobileNumber":"9000000001","password":"DemoPassword123!"}`. Login uses `mobileNumber` and `password`. Passwords accept 10-72 printable ASCII characters in this milestone to avoid BCrypt's byte-length truncation. Broader password handling is a future auth enhancement.

Transfer: `{"receiverWalletId":"<UUID>","amount":250.00}` with `Authorization: Bearer <token>` and `Idempotency-Key: <client-generated UUID>`. Demo topup uses `{"amount":1000}` with the same headers. Successful transfers and topups return HTTP 200 and `status=SUCCESS` only after the database transaction commits. Signup returns 201. There is no asynchronous settlement in milestone 1.

**Idempotency:** keys are scoped to the authenticated user across topups and transfers. Same key + same normalized operation returns the original payment. Same key + changed amount, recipient or operation returns 409. Only successful committed operations reserve a key; a failed insufficient-funds attempt may later succeed after funding. Keys have no TTL in this release. Transaction timestamps are normalized to database microsecond precision for consistent replay.

**Errors:** invalid input 400; missing/invalid JWT 401; disabled demo funding 403; inaccessible/missing payment 404; idempotency conflict 409; insufficient funds or storage-capacity overflow 422; database contention/unavailability 503. Security-layer 401 uses Spring Security's standard response; business errors use a JSON `code`, `message`, `timestamp` response. Retry an uncertain transfer with its original key.

## Correctness boundaries

- Both wallets are locked through `@Lock(PESSIMISTIC_WRITE)` in deterministic order before reading/changing their balances. No JVM-only `synchronized` or Redis lock guards money.
- Payment row, debit, credit and both journal entries commit in one local PostgreSQL transaction. Hibernate dirty checking flushes the managed wallet changes; LedgerService uses `Propagation.MANDATORY` to join the payment transaction.
- A unique `(actor_id,idempotency_key)` constraint adds database protection. Actor wallet locking serializes concurrent duplicates.
- PostgreSQL migration V2 blocks journal/payment updates and deletes, and checks balanced operation entries at commit. Schema ownership can bypass database triggers; a restricted runtime role is needed beyond the demo.
- The wallet balance is an optimized projection; the append-only journal records history. Reversal will be a new opposing operation, not an edit.
- H2 tests exercise application behavior. PostgreSQL-specific triggers and locking require `PostgresPaymentIT`; H2 alone is not proof of PostgreSQL behavior.

## Tests and CI

With Java 21 and Maven installed:

```powershell
mvn test
# Requires running Docker for Testcontainers:
mvn verify
```

`mvn test` runs fast H2 application tests and money conversion tests. `mvn verify` additionally runs the same payment scenarios against PostgreSQL 17.6 and tests journal triggers. GitHub Actions uses Java 21 and runs `mvn verify` on pushes and pull requests. See `docs/verification.md` for what actually ran while creating this milestone.

## Code reading order

1. `OfflinePayApplication` - application entry point.
2. `auth/AuthController` then `AuthService` - validation, password hashing, signup transaction and token issuance.
3. `security/SecurityConfig` - stateless bearer authentication and issuer validation.
4. `wallet/WalletController` - ownership comes from JWT, DTOs and pagination.
5. `wallet/WalletEntity` and `WalletRepository` - entity mapping, guarded debit/credit and `@Lock`.
6. `wallet/WalletService` - transfer orchestration, dirty checking, lock ordering and durable replay.
7. `ledger/LedgerService` and entity/repository classes - balanced journal recording in the existing transaction.
8. `common/Money` - exact decimal conversion.
9. `db/migration/V1...sql` and `V2...sql` - constraints and journal protection.
10. Tests - repeat requests, concurrent overspending, rollback and authorization.

See [the learning guide](docs/learning-guide.md) and the PDF in `docs/` for concept-to-code explanations and exercises.

## Switch from the earlier JDBC version

Stop the IntelliJ application. Copy the updated `pom.xml`, `src`, `docs` and `scripts` into your existing `offlinepay` project, replacing their earlier files. Keep your existing `.env`, `.idea` run configuration and PostgreSQL volume. Reload the Maven project in IntelliJ, then restart the application and run `Demo.ps1` again. Existing wallet IDs, balances and payments stay in the same tables; V1/V2 are unchanged. A V5 database receives V6; initial databases apply V1-V6. Generate stable server keys before startup (Part 2 lab). Copy Dockerfile/compose.yaml too if running the app in Docker, so rolling logs have a writable persistent directory.

Hibernate uses `ddl-auto: validate`, not `create`, `create-drop` or `update`. Flyway owns schema changes. Lombok annotation processing must be enabled in IntelliJ (Settings -> Build, Execution, Deployment -> Compiler -> Annotation Processors) if generated getters/builders are not recognized; Maven compilation already processes Lombok.

### Run the backend in IntelliJ with the database in Docker

```powershell
docker compose up -d postgres
docker compose stop app
```

Use Run -> Edit Configurations -> OfflinePayApplication. Set these environment variables:

| Name | Value |
| --- | --- |
| DB_URL | jdbc:postgresql://localhost:5436/offlinepay |
| DB_USER | offlinepay |
| DB_PASSWORD | Exact POSTGRES_PASSWORD value from .env |
| JWT_SECRET_BASE64 | Exact JWT_SECRET_BASE64 value from .env |
| SPRING_PROFILES_ACTIVE | demo |

Keep trailing `=` padding characters in generated values. Add `-Duser.timezone=Asia/Kolkata` in **VM options** for the observed Windows/PostgreSQL timezone-name mismatch. Changing Jackson's timezone alone does not change the JVM startup timezone sent by the JDBC driver. Keep only one backend listening on port 9030.

On Windows, if script execution is disabled, run the following in the current PowerShell session, then invoke the needed script:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
Unblock-File -LiteralPath .\scripts\Demo.ps1
.\scripts\Demo.ps1
```

## Milestone 2: next steps

Read [milestone-2-part-2.md](docs/milestone-2-part-2.md) for reservation examples, rotating refresh tokens, logs, ledger bucket reconciliation, migration behavior and all planned requirements. Import `postman/OfflinePay.postman_collection.json` to test manually. No live tokens are included in the source export.

Reserve INR 300 from INR 750: available becomes INR 450, reserved INR 300, total stays INR 750. Status is RESERVED; GET certificate provides its server-signed device-bound claims for new V5 reservations. Expiry alone does not unlock funds. Access tokens still expire after 30 minutes; refresh sessions default to 7 days with one-time token rotation and replay revocation.

## GitHub and resume

Suggested repository name: `offlinepay`. Use a public repository for a recruiter-readable link, after reviewing the source. Do not upload `.env`, logs, tokens or local database exports. If the GitHub integration is connected, this repository can be published directly. Otherwise, create an **empty** repository named `offlinepay` in GitHub (no initial README), then run:

```powershell
git init -b main
git add .
git commit -m "Build transactional wallet foundation"
git remote add origin https://github.com/YOUR_USERNAME/offlinepay.git
git push -u origin main
```

Replace `YOUR_USERNAME` with your actual GitHub username. If you use the local working repository with an existing initial commit, omit the first three commands. A username placeholder is not an existing repo or a resume link.

Current honest CV description: "Built a Java/Spring Boot virtual wallet with JWT authentication, PostgreSQL transactions, durable idempotency and a balanced payment journal; added concurrency and rollback tests." Use this only after running and understanding the milestone. Add offline payment and performance claims once implemented and measured.

## Roadmap

| Milestone | Deliverable | Completion evidence |
| --- | --- | --- |
| 1: wallet foundation | Auth, wallet, transfers, journal, durable idempotency | Demo script + real PostgreSQL tests |
| 2: offline protocol | Device key registration, server-signed reserves, local signing/verification (sync in milestone 3) | Two disconnected clients exchange signed payload; tampering and replay tests |
| 3: reconciliation | Allowance consumption, conflict decisions, pending receipts, recovery rules | Conflicting offline spends cannot both settle; balanced journal |
| 4: events and resilience | Outbox relay, Kafka, idempotent consumers, Redis limiter, Resilience4j | Crash/retry/failure injection checks |
| 5: service boundaries | Gateway, OpenFeign and auth/payment/ledger ownership, config/discovery where useful | Contract tests and safe cross-service transaction design |
| 6: observability and scale | Micrometer/tracing, ELK, Kubernetes, k6 | PostgreSQL 1/5/10-VU local runs and SQL reconciliation pass; deployment runtime acceptance remains |
| 7: mobile demo | Android, Room, Keystore, QR then BLE/NFC | Offline exchange, reconnection and settlement video |

An offline receipt is `PENDING_SETTLEMENT` and cannot be re-spent until settled. Software-only devices can clone or roll back local state; signed allowances alone cannot guarantee offline double-spend prevention. The server can detect/reject conflicting spends during sync, which leaves a receiver risk. New acceptance checks expiry and online device revocation blocks new allocations. Milestone 3 adds stored grace deadlines, explicit safe reserve release and durable online replay/conflict checks. Offline revocation distribution and dispute/reversal workflows remain follow-up work. There will be no backend HTTP call during the actual offline exchange.

10,000 concurrent virtual users is a future test target, not a capability claim. Virtual users, in-flight requests and RPS are separate measures. Rate-limited 429s, overload 503s and completed payments must be reported separately. Mandatory authorization/risk controls must not be bypassed when a circuit breaker opens.

## Stop local services

```powershell
docker compose down
```

This preserves the database volume. Existing databases keep their original database password; changing `.env` alone does not rotate it. Avoid deleting the volume unless you intend to discard the demo data.

## Part 2 contract changes

Auth POSTs require the CSRF cookie + X-XSRF-TOKEN header obtained from GET /api/v1/auth/csrf. Login/refresh return accessToken, tokenType and expiresIn only. Refresh/logout use offlinepay_rt HttpOnly SameSite=Strict cookies and empty bodies; Secure is default, demo allows HTTP localhost. Never add an expired Bearer header to these No Auth requests. Existing access tokens are not immediately revoked by logout.

API Instant timestamps and application log dates display Asia/Kolkata (+05:30). Signature claims use timezone-independent epoch milliseconds, JWT uses epoch seconds, database stores absolute instants. New reservations snapshot a registered public key and a stable server signature. Legacy reservations are preserved but unsigned. All disconnected proof verification is provisional and does not credit balances, prove an honest clock/device, or prevent global double spending.

## Settlement semantics

POST offline-sync/payments is a Bearer receiver endpoint. The signed paymentId is the durable idempotency identity; reuse the exact existing proof on retry. Same ID/details returns the original result, same ID changed proof or reused allowance sequence conflicts, and total accepted amounts cannot exceed that specific allowance. Different sequence numbers may arrive out of order. Sorted wallet locks precede the allowance lock, aligning with online transfer lock order. Balance/counters, append-only payment and ledger, verified proof and receipt commit atomically. System financial keys include ':' so they cannot collide with public Idempotency-Key values.

New offline acceptance still requires current allowance validity. Settlement accepts a proof whose signed time falls within validity until the DB sync deadline (default expiry + 48h). Device clocks are not trusted hardware clocks. First successful commits win finite reserve; no unconditional offline receiver guarantee exists. At deadline new claims close whether or not release was called. An already committed retry remains idempotent after closure/revocation. Current device revocation blocks new settlements, including proofs claiming earlier times.

Release is explicit, not scheduled. It only returns allocated minus settled minus released funds to the owner's available balance; a repeated release returns the original record. Unsigned legacy funds can be released after deadline but cannot be retroactively signed. Settlement debits OFFLINE_RESERVED / credits receiver WALLET; release debits OFFLINE_RESERVED / credits same-wallet WALLET. Local proof verification continues to return VERIFIED_NOT_SETTLED because that endpoint does not settle.

## Notification APIs (Milestone 4)

`GET /api/v1/notifications?page=0&size=20` lists only the Bearer user's notices. `POST /api/v1/notifications/{id}/read` marks only their own notice read, idempotently. The Kafka consumer does not change money. Eventual notification delivery is separate from synchronous settlement success. See docs/verification.md for exact executed evidence and limitations.

## Milestone 6 upgrade

For one-command local capacity exploration, use [the Windows automation runner](docs/capacity-automation.md). It automates bounded k6 stages, host/queue samples, resource stops, delivery drain and SQL reconciliation. It does not establish an unconditional maximum, provision infrastructure or claim10,000 concurrent requests. The user executes it once on the existing isolated load environment. [Continued20/30-VU measurements](docs/m6-capacity-progress.md) preserve the next manual checkpoint.

Operational visibility is added to the same project. Start with [M6 steps](docs/milestone-6.md), [verification and performance status](docs/milestone-6-report.md), [isolated automated load testing](benchmarks/README.md), and [application-only Kubernetes example](deploy/kubernetes/README.md). API URLs stay9030; optional private management ports are9130/9131/9132/9762 under the `observe` profile. No new financial migration or key/volume reset. Optional ELK must be run separately on the8GB laptop. [Measured PostgreSQL results](docs/m6-load-results.md): about163 mixed requests/s at10 VUs over30s locally,333ms transfer p95, zero request failures; final cumulative2,986 published/consumed events and5,968 notices reconcile. This is achieved local workload throughput, not maximum capacity or sustained notification throughput. Kubernetes runtime scaling remains unmeasured.
