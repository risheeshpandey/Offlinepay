# Milestone 5 add-on: Eureka discovery on8762

This upgrade follows the already completed notification database migration. No new Flyway migration, database reset, key generation or data copy is required. Keep all private `.env` values, keys, device/proof files and the existing PostgreSQL/Kafka/Redis volumes.

The previously used8761 belongs to another project. OfflinePay defaults to **8762**.

## What changed

| Application | Default local port | Eureka name / behavior |
|---|---:|---|
| Standalone registry | 8762 | `offlinepay-discovery`; does not register/fetch itself |
| Financial core (`demo,split`) | 9031 | `offlinepay-core`; registers, does not fetch instances |
| Notifications | 9032 | `offlinepay-notifications`; registers and fetches |
| API Gateway | 9030 | `offlinepay-gateway`; registers and fetches |

Gateway uses `lb://offlinepay-core` and `lb://offlinepay-notifications`. `CoreClient` uses `@FeignClient(name="offlinepay-core")` when its optional diagnostic URL is empty. Spring Cloud LoadBalancer resolves instances through Eureka; no hardcoded core/notification port is used by default. Automatic discovery routes are deliberately disabled: only the existing explicit public routes are exposed. JWT/CSRF, internal service credential and notification ownership checks are preserved.

The root single-app mode (`demo` without `split`) has Eureka disabled by default. It is still available for learning, but after split notification traffic the original inbox is stale: follow the documented ownership reconciliation before reverting to monolith mode. Do not run old and split consumers simultaneously.

## Merge and build

1. Stop **all three running Java applications** with Ctrl+C in their respective windows. Keep the infrastructure containers running.
2. Back up the current project, including your local comments. Extract `OfflinePay_M5_Eureka_Upgrade.zip` separately and merge the contents of its `offlinepay` folder into your existing project. There are no file deletions. Merge any personal comments in changed files before overwriting them.
3. The package also fixes `Import-LocalEnv.ps1`: environment names containing digits, such as JWT_SECRET_BASE64, now load correctly. If you already applied that one-line fix, the included file is the corrected version.
4. Close IntelliJ to leave memory for the applications. Open PowerShell at the project root and build all four JARs:

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.11"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" -f .\services\pom.xml "-Dmaven.test.skip=true" package
```

Local packaging skips tests to fit the8GB machine; automated maintainer checks are recorded separately in docs/verification.md. Updated module dependencies need a Maven reload if inspecting code in IntelliJ.

## Start four processes in order

Keep project root as the working directory in every window. JVM heap caps below do not cap total native/process memory; use Task Manager to watch actual usage. If memory pressure returns, stop the processes normally and reduce the running workload rather than forcing a shutdown.

### Window1: Eureka

No database password or `.env` needed for this process.

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\discovery\target\offlinepay-discovery-0.1.0-SNAPSHOT.jar
```

After `Started DiscoveryApplication`, open `http://localhost:8762`. The dashboard may initially have no registered clients; wait for the other processes to start. A self-preservation warning on a tiny local registry is not a reason to disable that mechanism.

### Window2: core

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
Unblock-File -LiteralPath .\scripts\Import-LocalEnv.ps1
. .\scripts\Import-LocalEnv.ps1
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms128m -Xmx384m -jar .\target\offlinepay-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo,split
```

### Window3: notifications

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
Unblock-File -LiteralPath .\scripts\Import-LocalEnv.ps1
. .\scripts\Import-LocalEnv.ps1
Remove-Item Env:CORE_URL -ErrorAction SilentlyContinue
$env:NOTIFICATION_CONSUMERS_ENABLED = "true"
$env:WORKERS_ENABLED = "true"
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\notifications\target\offlinepay-notifications-0.1.0-SNAPSHOT.jar
```

### Window4: Gateway

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
Remove-Item Env:CORE_URL -ErrorAction SilentlyContinue
Remove-Item Env:NOTIFICATIONS_URL -ErrorAction SilentlyContinue
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx192m -jar .\services\gateway\target\offlinepay-gateway-0.1.0-SNAPSHOT.jar
```

If `DISCOVERY_ENABLED=false` or an old `EUREKA_URL` is set in a terminal, remove/update it before starting clients. Default EUREKA_URL is `http://localhost:8762/eureka/`. CORE_URL/NOTIFICATIONS_URL overrides intentionally bypass discovery; do not leave them set for discovery acceptance.

## Manual acceptance

- Verify Eureka dashboard at8762 shows **OFFLINEPAY-CORE**, **OFFLINEPAY-NOTIFICATIONS**, **OFFLINEPAY-GATEWAY** asUP, with ports9031/9032/9030.
- Verify each `/actuator/health` separately. Registry UP means registered, not proof that every business dependency is healthy.
- Keep Postman public base URL `http://localhost:9030`. Get fresh CSRF, login Alice, then verify the unchanged balance and migrated inbox/readAt.
- New transfer with a new idempotency key: payment succeeds, each participant receives one notice. Replay retains the same transaction and no extra notices.
- Notification GET now tests Feign discovery of core for Bearer validation. New payment notification consumption also verifies its event digest through that same discovered Feign client.
- Public `/api/v1/internal/session` must remain404. Wrong/expired AT stays401 and ownership remains enforced.
- Optional demonstration: stop core normally and wait for registration/cache refresh. Requests needing core should fail; exact behavior during stale-cache periods can be connection error before the instance disappears. Restart core and wait for registry refresh before retrying with the same financial idempotency key.
- To demonstrate port independence, restart the only core with `--server.port=9035`, keeping `demo,split`; wait for discovery and repeat a wallet/inbox read through9030. Restore9031 afterward. Do not start a second core/worker casually for this demo.

## Failure semantics and deployment limits

Clients fetch every5 seconds; registry read-only response cache updates every5 seconds. LoadBalancer's extra cache is disabled because Eureka already caches instances. This does not promise a fixed maximum propagation time or instant failover. Local lease renewal is10 seconds and expiration30 seconds; self-preservation remains enabled and can retain stale registrations during a suspected network failure.

Existing cached instances may still work during an Eureka outage. A cold client with no registry entry cannot resolve the backend and returns503. A process can remain advertised while a business dependency is down; the security/DB layer still decides whether its request succeeds. No successful financial fallback or automatic transfer retry was added.

The registry is bound to127.0.0.1 and unauthenticated for local learning. Service instances also advertise127.0.0.1. This setup is intentionally single-host: production or containers on other hosts require private/routable advertised addresses, secured registry access/TLS, and a deliberate network/authentication design. No Eureka cluster, production HA or performance SLA is claimed.

## Verification

The supplementary `scripts/verify_eureka_routing.py` starts real registry, Gateway and notification/Feign JARs on random loopback ports, with an isolated H2 notification database and a small fake core API. It registers the fake core at a random port, verifies name-based routing and Feign identity lookup, then deregisters it and checks503 after cache refresh. It neither loads `.env` nor touches user databases, Kafka offsets or payment state. Use it as maintainer transport evidence; our normal acceptance remains manual Postman testing against PostgreSQL/Kafka/Redis.

M4/M5 code reading, M6 observability/measured load and M7 Android/automatic-sync work remain scheduled as discussed. Eureka is now part of the M5 code; it does not complete those later milestones.
