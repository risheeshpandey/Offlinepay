# Milestone 1 learning guide

Read `OfflinePay_Skills_and_Code_Guide.pdf` for the full updated explanation with live source excerpts and line references. `scripts/build_learning_guide.py` regenerates it from the code. Its only documentation dependency is ReportLab; it is not needed to run the Java application.

## Concept-to-code map

Java source paths below are relative to `src/main/java/com/risheesh/offlinepay/`.

| Concept | File and method | Why it exists |
| --- | --- | --- |
| Spring Boot | `OfflinePayApplication.main` | Starts the web application and auto-configuration |
| Dependency injection | Constructors of `AuthService` and `WalletService` | Supplies database and security dependencies |
| Java records and DTOs | `AuthController.SignupRequest`; `WalletService.Payment` | Typed request/response data |
| Validation | `AuthController` request annotations; `WalletController.ledger` | Rejects missing/invalid fields and oversized page requests |
| Exact money | `common/Money.toPaise` | Rejects fractional paise and avoids floating point |
| Stateless JWT authentication | `security/SecurityConfig.securityFilterChain` and `jwtDecoder` | Verifies signature, expiry and issuer without HTTP sessions |
| Password hashing | `SecurityConfig.passwordEncoder`; `AuthService.signup/login` | Stores a BCrypt hash, never the original password |
| Token issuance | `AuthService.login` | Sets user subject, token ID and expiry |
| Ownership authorization | `WalletController.user`; `WalletService.payment` | Derives sender from JWT and restricts payment visibility |
| REST and HTTP status codes | Both controllers; `common/ApiExceptionHandler` | Maps operations and expected failures to APIs |
| JPA and Hibernate | Entity classes and `JpaRepository` interfaces | Maps Java objects and generates SQL |
| Lombok | `@Getter`, `@Builder`, `@RequiredArgsConstructor` | Generates accessors, builders and dependency constructors |
| Relationships | `@OneToOne`, `@ManyToOne`, `@JoinColumn` | Maps user/wallet/payment/journal foreign keys |
| Dirty checking | Managed `WalletEntity` objects in `transfer` | Flushes debit/credit changes at commit |
| ACID and rollback | `@Transactional` on `signup`, `transfer`, `demoTopup` | Commits every financial effect together |
| Row locking | `WalletRepository.findByIdForUpdate` with `@Lock` | Serializes concurrent modifications across app instances |
| Consistent lock ordering | `WalletService.lockInOrder` lexical UUID ordering | Avoids the two-wallet opposing-transfer deadlock cycle |
| Guarded debit | `WalletEntity.debit` after acquiring its database lock | Rejects insufficient funds without concurrent stale reads |
| Durable idempotency | `WalletService.findPreviousPayment` and `createPayment` | Stores a retry result in the same database transaction |
| SHA-256 request fingerprint | `common/Idempotency.fingerprint` | Rejects a key reused for a different normalized request |
| Double-entry journal | `LedgerService.recordTransfer/recordDemoTopup` | Stores equal opposing entries for each operation |
| Append-only history | `@Immutable` entities and Flyway V2 triggers | Blocks accidental edits/deletes of committed financial history |
| Deferred consistency check | V2 `verify_balanced_operation` | Validates journal equality at transaction commit |
| Migrations and constraints | Flyway V1 and V2 | Defines versioned schema, keys, foreign keys and CHECK rules |
| Indexing and pagination | V1 history index; `WalletService.ledger` | Bounds and supports wallet journal reads |
| Connection pool and timeouts | `application.yml` | Bounds database concurrency and lock waits |
| Profiles and secrets | `application-demo.yml`; `Setup-Local.ps1` | Makes virtual funding opt-in and creates local credentials |
| Docker and container networking | `Dockerfile`; `compose.yaml` | Builds with Java 21 and connects app to PostgreSQL by service name |
| Health checks | Spring Actuator; Compose database healthcheck | Exposes readiness information for local operation |
| Unit and application integration testing | `MoneyTest`; `PaymentFoundationTest` | Exercises exact money, API security and transactional failure cases |
| Concurrent testing | Latch + executor in `PaymentFoundationTest.parallel` | Creates competing requests and asserts money invariants |
| PostgreSQL integration testing | `PostgresPaymentIT` | Reuses scenarios against PostgreSQL and validates V2 triggers |
| Git and CI/CD foundations | Git repository; `.github/workflows/ci.yml` | Version history and automatic Java 21 checks |

## Follow one payment through the code

1. The security filter verifies the bearer token.
2. The controller validates input and derives the actor from the JWT.
3. `transfer` converts INR to paise and computes a fingerprint.
4. It locks both wallets in deterministic order.
5. It looks up the durable idempotency row; replay returns the existing payment.
6. It calls the locked sender's `debit` and receiver's `credit` methods.
7. It saves a `PaymentOperationEntity` through the repository.
8. `LedgerService` saves two `LedgerEntryEntity` objects inside the same transaction. Hibernate dirty checking flushes both balance updates.
9. PostgreSQL validates the balanced operation at commit.
10. After commit, the controller returns SUCCESS.

If any step fails before commit, financial changes roll back. If the HTTP response is lost after commit, the original key retrieves the completed payment. Redis is not required for this correctness rule.

## Exercises

- Run `Demo.ps1` and verify Alice 750 / Bob 250.
- Inspect a funded wallet: its CREDIT history minus DEBIT history must equal `available_paise`.
- Replay one transfer key; confirm there is one payment and two journal entries.
- Change the amount but reuse the key; expect 409.
- Submit two simultaneous INR 700 transfers against INR 1,000; exactly one should succeed.
- Read the rollback test and explain why a debit disappears when the credit fails.
- Read a payment as an unrelated user; expect 404.
- Run `mvn verify` with Docker and confirm journal UPDATE/DELETE and unbalanced operations are rejected.

## Planned skills are not current implementation claims

| Stage | Topics |
| --- | --- |
| Offline protocol | Device registration, Ed25519 keys, server-signed allowances, canonical payloads, local verification, reserve balances |
| Reconciliation | Store-and-forward, nonce/replay rules, allowance consumption, double-spend detection, expiry/grace windows, recovery, reversals |
| Events and resilience | Kafka, transactional outbox, consumer idempotency, retry/DLT, Redis rate limiting, circuit breakers, bulkheads, timeouts |
| Service boundaries | Gateway, separate service/database ownership, asymmetric JWT, config/discovery where useful, inter-service clients, saga concepts |
| Operations and scale | Micrometer, tracing/Zipkin, ELK, Kubernetes, k6, HPA, backpressure, query tuning |
| Mobile | Kotlin, Android, QR/BLE/NFC, Room, Keystore, background sync |

We will attach each new skill to a real code path, a reason for choosing it and a failure test. Signed allowances do not eliminate offline receiver risk on ordinary phones. Pending receipts cannot be re-spent until settled. No concurrency-capacity number is claimed before load testing.

## Annotation-based reading order

Start with `UserEntity` -> `UserRepository` -> `AuthService.signup`. Then read `WalletEntity` -> `WalletRepository.findByIdForUpdate` -> `WalletService.transfer` -> `LedgerService.recordTransfer`.

`@Entity` maps a class to a table. `@GeneratedValue(UUID)` generates new entity IDs. `JpaRepository` supplies `save`, `findById` and other CRUD methods. `@Lock(PESSIMISTIC_WRITE)` requests database row locking. `@Transactional` encloses related writes; the ledger's `Propagation.MANDATORY` prevents a separate journal commit. `@Immutable` prevents Hibernate dirty updates to history, while PostgreSQL triggers still protect SQL callers. `ddl-auto=validate` preserves Flyway's control of the existing schema.

The transfer first queries only the sender wallet ID, then loads its balance under a lock. Reading the entity before acquiring a lock can leave a stale balance in Hibernate's first-level cache. No second-level entity cache is enabled.

Application services contain no `JdbcTemplate`; test fixtures use it to independently inspect rows and force failure states. JDBC still exists underneath Hibernate as the database transport.

## Milestone 2, Part 1 skills

Read `milestone-2.md` for detailed manual steps.

| Concept | Code path |
| --- | --- |
| Available/reserved bucket accounting | WalletEntity.reserveOffline; OfflineAllowanceService.reserve |
| Durable reservation replay | PaymentOperationRepository + OfflineAllowanceRepository |
| Balanced internal movement | LedgerService.recordOfflineReservation; AccountKind.OFFLINE_RESERVED |
| Additive schema evolution | Flyway V3/V4; existing V1/V2 unchanged |
| Access + rotating opaque refresh tokens | AuthService.login/refresh; RefreshTokenService |
| Session-family serialization | RefreshSessionRepository.findByIdForUpdate |
| Hashed token storage | SecureRandom + SHA-256; RefreshTokenEntity.tokenHash |
| Replay revocation with committed 401 | RefreshTokenReplayException; narrowly scoped noRollbackFor |
| Request correlation and rolling logs | RequestLoggingFilter, MDC, X-Request-ID, application.yml |
| Honest committed-payment logs | PaymentCommittedEvent; AFTER_COMMIT listener |
| Manual API workflow | Postman collection |

OpenFeign is planned at milestone 5 service boundaries. Part 2 adds registered Ed25519 keys, signed certificates and local proof creation/verification; Android exchange and settlement remain later work. Refresh logout revokes future refresh access, not already-issued access JWTs. No performance capacity has been measured.

## Milestone 2 Part 2 skills and code map

| Concept | Implementation |
| --- | --- |
| HttpOnly/Secure/SameSite cookies | AuthCookies; AuthController.AccessResponse |
| CSRF protection for cookie endpoints | SecurityConfig CookieCsrfTokenRepository; GET auth/csrf |
| IST human-readable timestamps | TimeConfig Instant serializer; logging.pattern.dateformat |
| Public/private keys, possession proof | Ed25519; DeviceService.register |
| Canonical signed payloads | OfflineProtocol.canonical and registration |
| Device-bound server certificate | OfflineAllowanceService.reserve/certificate; ServerSigningService |
| Local signing and remaining budget | OfflineClient.pay; file lock + local state |
| Signature/recipient/validity checks | OfflineProofVerifier; receiver JWT binding in OfflineProofController |
| Non-destructive upgrade | Flyway V5; unsigned legacy allowance preservation |

Follow milestone-2-part-2.md manually. Signatures prove origin/integrity, not an honest clock, uncloneable device, final credit or global double-spend prevention.

## Milestone 3 code map

| Concept | Code |
| --- | --- |
| Receiver-bound sync and status ownership | OfflineSyncController; OfflineSettlementService.sync/get |
| Durable idempotency from signed payment ID | OfflineSettlementEntity.paymentId; proofHash; service replay branch |
| Explicit INSERT for external IDs | EntityManager.persist prevents save/merge collisions |
| Conflicting sequence and allowance budget | Unique (allowance_id, sequence_number); OfflineAllowanceEntity.settle |
| Cross-flow lock order | Sorted wallet UUIDs first, allowance row last |
| Atomic reserved debit/receiver credit | WalletEntity.consumeOffline; LedgerService.recordOfflineSettlement; @Transactional |
| Expiry versus submission deadline | verifyForSettlement versus verify; stored syncDeadline |
| Explicit unused-fund release | OfflineSettlementService.release; releaseOffline; recordOfflineRelease |
| Testable time | Clock bean and mocked Clock boundary/concurrency tests |
| Migration and append-only receipts | V6; PostgresMilestone3IT; V1-V5 unchanged |

Detailed manual lab: milestone-3.md. V5 walkthrough remains deferred. Rejected sync attempts currently use logs, not a durable dispute/audit table; reversals and human adjudication are follow-up scope.

## M6 additions

Read `milestone-6.md` and the updated cumulative PDF for metric counters versus balances, commit-only telemetry, histograms/p95, W3C trace propagation, durable-outbox trace boundaries, IST JSON logs, Prometheus alerts, optional ELK, automated k6 traffic, reconciliation and Kubernetes/HPA prerequisites. `milestone-6-report.md` separates authoring checks from user-machine PostgreSQL evidence. `m6-load-results.md` records successful1/5/10-VU local runs and SQL reconciliation; at10 VUs about163 mixed requests/s and333ms transfer p95, with zero request failures. API rate is separate from background queue drain. Maximum capacity, sustained notification throughput and deployment runtime scaling remain unmeasured. M4-M6 code walkthrough follows M6 testing in the same evolving codebase.
