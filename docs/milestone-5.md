> Eureka add-on: service discovery now uses the separate registry on8762. Read [the Eureka upgrade/start guide](milestone-5-eureka.md) for current startup order. If you already completed the notification data migration, do not repeat it.

# Milestone 5: service boundaries, Gateway and OpenFeign

This is the same evolving repository. Keep your existing folder, `.env`, keys, proof files and PostgreSQL volume. Back up the whole folder before merging the upgrade ZIP. No old source files are moved or deleted. Maven at the root still builds the compatible single application. `services/pom.xml` builds the core plus two separate applications.

## What runs where

| Process | Local port | Owns | Does not own |
|---|---:|---|---|
| Gateway | 9030 | Public routing, request ID, header boundary | Financial transactions, identities, database |
| Financial core | 9031 | Identity, refresh sessions, wallets, journal, allowances, settlements, transactional outbox | Split-mode notification APIs and workers |
| Notifications | 9032 | Separate `offlinepay_notifications` database: inbox, consumed markers, provider retry queue, DLT archive | Money, passwords, JWT signing secret, core database access |

Identity stays with the financial core because signup currently creates the user and wallet in one transaction. We have extracted one bounded context, not claimed every package is an independent microservice. Reserve/transfer/settle/release retain one PostgreSQL transaction. Notification delivery remains eventually consistent.

The PostgreSQL server is shared locally to save RAM; databases and login roles are distinct. Financial records never move. Shared notification domain classes are compiled from the existing source directory using an explicit Maven include list. This is source reuse, not a runtime dependency on the core JAR or its tables. The two applications import different inbox implementations.

## Request and event flow

1. Postman calls Gateway9030 using the existing routes, cookies and Bearer AT.
2. Gateway sends `/api/v1/notifications/**` to9032 and other `/api/v1/**` to9031. It preserves auth/CSRF cookies and headers. Each backend still enforces authentication itself.
3. Notifications uses OpenFeign `CoreClient.session` to validate AT at the core. It receives only the user ID. No JWT signing secret is copied to this service. Core Redis rate limiting applies to this introspection request. A429 is returned as429 with Retry-After; an identity outage returns503. A core outage blocks notification reads rather than accepting unauthenticated traffic.
4. Money commits with its outbox, then the core relay publishes to Kafka as before.
5. Notifications validates the Kafka payload shape and fetches its authoritative SHA256 digest with OpenFeign `CoreClient.event`. A random service credential authenticates this read-only request. A user AT cannot authorize this operation.
6. After the HTTP call finishes, one local database transaction inserts the consumed marker with `ON CONFLICT DO NOTHING` and both participants' notices. Concurrent duplicates serialize on the local unique key. Any notice failure rolls the marker back too.
7. Malformed/forged events go to DLT. Core/DB outages retry without acknowledging the record; this can block the affected partition until recovery. Other partitions can progress. Provider failures still use bounded durable retries and a stable notification ID.

Gateway does not expose `/api/v1/internal/**` or `/internal/**`, and strips client-supplied service/identity headers. Local backends bind to127.0.0.1. Internal tokens must be random32+ characters and transmitted over trusted local networking here; production needs private networking, TLS/service identity, secrets management and Kafka ACLs. This Compose broker is a local demo, not a secured production cluster.

The initial M5 setup used direct service URLs. The Eureka add-on replaces default downstream URLs with service-name discovery and adds a registry on8762. Explicit URL overrides are retained for diagnostics. A Config Server is not included. No automatic financial HTTP retry or fake-success fallback is configured. The core intentionally does not trust client forwarded IP headers: auth calls through this local gateway share its socket-IP auth budget (30/min by default); authenticated API calls use user IDs. Production needs a validated trusted-proxy/client-IP design before sharing the gateway among many users.

## Build on your Windows machine

Close IntelliJ while running the three JARs. Open PowerShell in:

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.11"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" -f .\services\pom.xml "-Dmaven.test.skip=true" package
```

`maven.test.skip` is only for your memory-constrained local packaging. Maintainer/CI checks run tests separately. JARs:

- `target/offlinepay-0.1.0-SNAPSHOT.jar`
- `services/notifications/target/offlinepay-notifications-0.1.0-SNAPSHOT.jar`
- `services/gateway/target/offlinepay-gateway-0.1.0-SNAPSHOT.jar`

## First-time database cutover: preserve the old inbox

Do this once, with both old and new Java applications stopped. PostgreSQL/Kafka/Redis may run. Never run the M4 and M5 notification consumers together: they intentionally use the same consumer group IDs.

1. Start infrastructure from the project folder:

```powershell
docker compose up -d postgres kafka redis
```

2. Open psql as your existing local admin role:

```powershell
docker compose exec postgres psql -U offlinepay -d offlinepay
```

Run the following **inside psql**, one command at a time. If the role/database already exists, inspect it instead of recreating or deleting it.

```sql
CREATE ROLE offlinepay_notifications LOGIN;
\password offlinepay_notifications
CREATE DATABASE offlinepay_notifications OWNER offlinepay_notifications;
\q
```

The password prompt sets a NEW notification database password. This is separate from the existing `.env` PostgreSQL password. Do not paste credentials into Git or screenshots.

3. Add these two new values to your private `.env` (keep all old values):

```dotenv
NOTIFICATION_DB_PASSWORD=<the new database password>
INTERNAL_SERVICE_TOKEN=<a new random value of at least 32 characters>
```

Generate the service token in PowerShell if needed:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes)
$rng.Dispose()
```

4. Load `.env` in each application terminal using the optional, inspectable `scripts/Import-LocalEnv.ps1` or set variables manually. Java does not load `.env` automatically. The helper changes only the current PowerShell process, prints no secrets, preserves Base64 trailing `=`, and does not run tests or requests.

```powershell
. .\scripts\Import-LocalEnv.ps1
```

If PowerShell blocks the local helper, use `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force` and `Unblock-File .\scripts\Import-LocalEnv.ps1`, or set `$env:NOTIFICATION_DB_PASSWORD` and `$env:INTERNAL_SERVICE_TOKEN` manually. Existing core still needs DB_PASSWORD and JWT_SECRET_BASE64.

5. Start notifications **with consumers and delivery workers disabled**, allowing Flyway to create its own tables. No financial core is needed for this initialization.

```powershell
$env:NOTIFICATION_CONSUMERS_ENABLED = "false"
$env:WORKERS_ENABLED = "false"
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\notifications\target\offlinepay-notifications-0.1.0-SNAPSHOT.jar
```

When startup succeeds, stop it with Ctrl+C. It must not consume events before the old inbox and consumed markers are copied.

6. With every Java application stopped, export the three M4 notification tables to temporary files **inside the existing PostgreSQL container**. This avoids PowerShell CSV encoding/BOM issues. Files are local migration intermediates, not a new source of truth.

```powershell
docker compose exec postgres psql -U offlinepay -d offlinepay -v ON_ERROR_STOP=1 -c "COPY consumed_payment_event TO '/tmp/offlinepay-m5-consumed.csv' WITH CSV; COPY user_notification TO '/tmp/offlinepay-m5-notices.csv' WITH CSV; COPY dead_letter_event TO '/tmp/offlinepay-m5-dlt.csv' WITH CSV;"
```

Import into the new database with this PowerShell pipeline:

```powershell
Get-Content -Raw .\docs\milestone-5-import.sql | docker compose exec -T postgres psql -U offlinepay -d offlinepay_notifications -v ON_ERROR_STOP=1 -f -
```

The import script requires an empty destination, runs in one transaction, preserves IDs/read timestamps/provider attempts, and never deletes the original tables. If a prior attempt failed, inspect the error; do not truncate tables. Existing `PROCESSING` leases can expire and be reclaimed normally. Preserve the same provider idempotency contract.

Verify row counts in both databases before enabling consumers:

```powershell
docker compose exec postgres psql -U offlinepay -d offlinepay -c "SELECT (SELECT COUNT(*) FROM user_notification) AS notices, (SELECT COUNT(*) FROM consumed_payment_event) AS consumed, (SELECT COUNT(*) FROM dead_letter_event) AS dlt;"
docker compose exec postgres psql -U offlinepay -d offlinepay_notifications -c "SELECT (SELECT COUNT(*) FROM user_notification) AS notices, (SELECT COUNT(*) FROM consumed_payment_event) AS consumed, (SELECT COUNT(*) FROM dead_letter_event) AS dlt;"
```

For your M4 test data, each successful transfer should still have two notices; Alice's previously read notice must retain `readAt`. If starting from a fresh installation, the copy may contain zero rows. Do not skip copying on an existing installation just because group offsets are retained: replay and dedup need the local markers too.

## Start split mode, one process at a time

Keep the project root as the working directory in all three PowerShell windows. Import environment in each new window. Keep IntelliJ/DBeaver and extra desktop apps closed. Heap caps are tuning starting points, not total process memory guarantees. Stop if Windows memory pressure returns; this hardware may need staging the tests or more RAM.

Terminal1 (financial core):

```powershell
. .\scripts\Import-LocalEnv.ps1
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms128m -Xmx384m -jar .\target\offlinepay-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo,split
```

Terminal2 (notifications):

```powershell
. .\scripts\Import-LocalEnv.ps1
Remove-Item Env:NOTIFICATION_CONSUMERS_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:WORKERS_ENABLED -ErrorAction SilentlyContinue
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\notifications\target\offlinepay-notifications-0.1.0-SNAPSHOT.jar
```

Terminal3 (gateway; no database/JWT secret needed):

```powershell
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx192m -jar .\services\gateway\target\offlinepay-gateway-0.1.0-SNAPSHOT.jar
```

Health checks:9031/core,9032/notifications,9030/gateway `/actuator/health`. A gateway health UP means its own process is running; verify each backend separately. Existing public Postman base URL remains `http://localhost:9030`. Always use localhost consistently for the cookie jar. Core9031 without Gateway has no split inbox route.

## Manual Postman checklist (we will do together)

- Fresh CSRF then Alice login through9030: AT in JSON; RT and CSRF cookies stay in the jar. Refresh works without a body RT.
- Alice and Bob existing balances unchanged by migration. Your last recorded values were648/352 INR, assuming no later operations.
- Existing notifications and Alice `readAt` survived cutover.
- New1 INR transfer, new idempotency key `m5-split-transfer-001`; both users receive one notice each. Replay same key/body produces no additional payment/notices.
- Bob cannot mark Alice's notice read; missing/expired AT gets401; excessive page size gets400.
- `GET /api/v1/internal/session` via9030 gives404. Supplying `X-Service-Token` to Gateway cannot authorize internal calls.
- Stop only notification JAR: transfer still succeeds via Gateway/core. Restart notification JAR: queued event is delivered, no duplicate credit.
- Stop only core: notification requests cannot authenticate and must not reveal data. Restart core and retry.
- Stop Kafka: transfer/outbox remain durable; restore Kafka and inspect retry as in M4. Redis outage still blocks protected core requests.
- Inject malformed event as in M4: DLT archive now belongs to `offlinepay_notifications`, not the archived old core table.
- All API and log timestamps remain IST. No AT, RT, service key, password or private key in logs.

## Rollback and compatibility

Before any split-mode traffic, stop all three JARs and run the original core with only `demo` profile on9030. The old inbox tables were retained, so this pre-cutover rollback is immediate. **After split traffic, do not simply re-enable the old consumer**: the old tables are stale while Kafka group offsets advanced. First stop producers/consumers, back up both databases, reconcile/copy the complete notification-owned tables and markers back under controlled maintenance, verify counts and provider lease states, then resume one owner. Financial core data needs no reverse migration. The optional monolith mode is for learning/compatibility, not active-active ownership.

## Evidence and honest CV scope

See `docs/verification.md` for executed tests and limitations. No fabricated throughput, concurrency, latency or production claims. Candidate CV bullets after local acceptance:

- Built an offline-payment backend with Spring Boot, JPA and PostgreSQL, preserving atomic wallet/ledger updates and idempotent settlement.
- Extracted notifications into a separate service and database, integrating Spring Cloud Gateway, OpenFeign and Kafka transactional-outbox events.
- Implemented local consumer deduplication, durable retry/DLT handling, authenticated service contracts and cookie-based refresh-token rotation with CSRF protection.

A public GitHub link and deployment are still pending. M6 measured performance/observability and M7 Android offline transport are not complete.

## Deferred work we agreed to keep

After M5 testing, resume M4 code reading, then M5. Detailed M3 V5/V6/entity notes can follow. Longer sync window (duration undecided), signed syncDeadline, sender-or-receiver proof upload, mobile durable proof queue/automatic sync/retry,12-hour local reminder and opposite-party settlement status updates remain backlog. No fine and no early release merely because a payment is absent from the server. Existing M3 deadline/release rules are unchanged.
