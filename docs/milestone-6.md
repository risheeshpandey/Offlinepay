# Milestone 6: observe, test and prepare to deploy

M6 adds operational visibility to the existing four applications. No new financial migration or change to allowance expiry, sync deadlines, settlement, release, JWT/cookie behavior or database ownership is included. Code walkthroughs for M4-M6 are deferred until after M6 implementation/testing, as requested.

| Addition | Files / behavior |
|---|---|
| Prometheus metrics | All four POMs and `application-observe.yml`; HTTP histograms, JVM/process/Hikari metrics, bounded application labels |
| Business counters | `PaymentMetrics`: increments after a committed financial operation, not on rollback/replay; process-local telemetry, not an accounting source |
| Queue gauges | `OutboxMetrics`, `NotificationMetrics`: cached DB counts every15s; refresh-failure counter exposes possible stale values |
| Distributed HTTP traces | Brave/Micrometer + Zipkin reporter; W3C propagation through Gateway, notification HTTP and OpenFeign |
| JSON logs | Shared `observability/shared-resources/logback-spring.xml`; familiar console, rolling JSON files, IST, requestId/traceId/spanId MDC allowlist |
| Optional search stack | `compose.observe.yml` plus `observability/logstash.conf`; Elasticsearch/Logstash/Kibana for stored logs |
| Automated traffic | `benchmarks/mixed.js`, `replay.js`, SQL reconciliation and summary reader; virtual demo fixtures only |
| Deployment preparation | Java21 non-root module images, application-only Kubernetes Deployments/Services/probes/Secrets references and CPU HPAs |

## Upgrade without losing the working project

1. Stop the four application JARs with Ctrl+C. Keep infrastructure/data. Back up the whole current project, especially personal code comments. The incremental M6 ZIP starts from the M5 Eureka add-on; apply that baseline first if missing.
2. Extract the upgrade elsewhere. Merge the **contents of its `offlinepay` folder** into your existing `offlinepay` project. Merge personal comments in changed files; blindly replacing a Java file cannot preserve comments you added locally. No deletions or DB reset are required.
3. Preserve `.env`, `.idea`, existing keys, device files, proofs/local state and Docker volumes. There is **no V8 financial migration**. Keep the previous project backup for comparison.
4. Close IntelliJ after any Maven reload. From project root, execute these commands separately:

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.11"
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" -f .\services\pom.xml "-Dmaven.test.skip=true" package
docker compose up -d postgres kafka redis
docker compose ps
```

Wait for infrastructure healthy. Build skips tests on the small laptop; executed maintainer checks are listed in `milestone-6-report.md`. Java21 remains the project/CI target.

## Four application windows

Use project root in every PowerShell window. Each process stays running in its own window; use a fifth window for checks. Keep the existing private environment loader. Heap caps below do not cap total process RSS.

**Window1, discovery**:

```powershell
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\discovery\target\offlinepay-discovery-0.1.0-SNAPSHOT.jar --spring.profiles.active=observe
```

**Window2, core** (the temporary9035 port test is over; this command restores default9031):

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
Unblock-File -LiteralPath .\scripts\Import-LocalEnv.ps1
. .\scripts\Import-LocalEnv.ps1
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms128m -Xmx384m -jar .\target\offlinepay-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo,split,observe
```

**Window3, notifications**:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
Unblock-File -LiteralPath .\scripts\Import-LocalEnv.ps1
. .\scripts\Import-LocalEnv.ps1
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\notifications\target\offlinepay-notifications-0.1.0-SNAPSHOT.jar --spring.profiles.active=observe
```

**Window4, Gateway**:

```powershell
& "$env:USERPROFILE\.jdks\ms-21.0.11\bin\java.exe" "-Duser.timezone=Asia/Kolkata" -Xms64m -Xmx256m -jar .\services\gateway\target\offlinepay-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=observe
```

Clear diagnostic CORE_URL/NOTIFICATIONS_URL and old SPRING_PROFILES_ACTIVE/server-port overrides in a fresh shell if they were used earlier. Eureka remains8762. Public API remains9030. No benchmark-only DB/rate-limit environment belongs in the normal startup windows.

## First checks: no heavy telemetry containers required

| Application | API port | Private local management |
|---|---:|---|
| Core | 9031 | http://localhost:9131/actuator/health/readiness |
| Notifications | 9032 | http://localhost:9132/actuator/health/readiness |
| Gateway | 9030 | http://localhost:9130/actuator/health/readiness |
| Discovery | 8762 | http://localhost:9762/actuator/health/readiness |

All should return200/UP. `/actuator/health/liveness` checks process state. Core readiness additionally requires Postgres+Redis, notification readiness requires its DB; gateway/discovery readiness is process-local. A Redis outage should make core readiness503 while liveness stays200. Kafka outage does not fail money API readiness: durable outbox is the intended outage policy. Inspect queue metrics to see delivery delay.

Open `/actuator/prometheus` on those **management ports**, No Auth, to inspect metrics. Management defaults to loopback only. `/actuator/env` is not exposed. Gateway has no public route for management endpoints. Metrics are diagnostic, not a source of financial balances. Do not expose management listeners or Eureka to the public internet.

Repeat a normal login and read-wallet request through9030. Then inspect `logs/offlinepay.log`, `notifications.log`, `gateway.log`, `discovery.log`: each file contains JSON events, while console remains readable. `X-Request-ID: m6-manual-check-001` helps find an HTTP request. File timestamps use `+05:30`; database timestamps may still displayUTC depending on the SQL client. Tokens, cookies, passwords and request bodies are not intentionally logged. Do not enable Feign full-header/body logging.

## Tracing, separately from capacity testing

Optional Zipkin uses **9412** to avoid9411 conflicts:

```powershell
docker compose -f .\compose.observe.yml --profile tracing up -d zipkin
```

Before starting/restarting **each** Java application, set:

```powershell
$env:ZIPKIN_ENDPOINT = 'http://localhost:9412/api/v2/spans'
$env:TRACE_SAMPLE_PROBABILITY = '1.0'
```

Trigger GET `/api/v1/notifications` with a valid AT through9030; it makes a Feign identity call to core. Search that trace in http://localhost:9412 using the traceId from the notification/core log. These three HTTP applications should share one trace with child spans. Export sampling defaults to0 because Zipkin is optional; set10% for sampled tracing, or100% for a short diagnostic check. If Zipkin is stopped, requests still work; exporter may report dropped spans. For normal operation without Zipkin or benchmark runs use probability0 explicitly in every process; do not include failed trace export overhead unknowingly in capacity measurements.

Kafka templates/listener factories also enable observations. **The original HTTP context is not persisted in the durable outbox.** The later scheduled relay starts its own trace; Kafka can propagate that context to its consumer. Correlate the HTTP operation and durable relay by transaction/event UUIDs. Do not describe the entire delayed outbox flow as one uninterrupted original HTTP trace.

## Prometheus, on the host

To retain loopback-only management listeners, run the Prometheus native Windows binary on the same host, not inside a container trying to reach host-loopback. Pin3.15.1 from the official download page and verify its checksum. From its extracted folder, point to the project's config with an absolute path:

```powershell
.\prometheus.exe --config.file="C:\Users\rishe\OfflinePay_Milestone_1\offlinepay\observability\prometheus.yml" --web.listen-address="127.0.0.1:9095" --storage.tsdb.retention.time=3d
```

The config resolves `alerts.yml` relative to itself. http://localhost:9095/targets should show fourUP targets. Useful PromQL (choose one service when assessing throughput; adding Gateway and Core counts counts the same client request more than once):

```promql
sum(rate(http_server_requests_seconds_count{application="offlinepay-core",uri="/api/v1/payments/transfer"}[1m]))
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="offlinepay-core",uri="/api/v1/payments/transfer"}[5m])))
offlinepay_outbox_events{status=~"PENDING|PROCESSING|FAILED"}
offlinepay_dead_letters_open
jvm_memory_used_bytes{area="heap"}
hikaricp_connections_active
```

HTTP histogram values are seconds, whereas k6 custom latency output is milliseconds. Five alert rules cover down targets, parked outbox, open DLT, gauge-refresh failures and server-error ratio. They appear in Prometheus; external delivery needs an Alertmanager/contact integration not included here. Gauges refresh15s and keep their last value on SQL failure; watch the refresh-failure counter. Committed-payment counters reset on process restart and are not a replacement for ledger reconciliation. Full historical outbox counts can be expensive at large scale; inspect plans and observation overhead before production sizing.

## Search old logs with ELK, as a separate exercise

Stop the application JARs and other unnecessary containers before running the optional log-search stack on the8GB laptop. Existing log files remain on disk. Use a new PowerShell window at project root:

```powershell
docker compose -f .\compose.observe.yml --profile logs up -d
```

This separate Compose project uses Elasticsearch9201 and Kibana5602. It does not replace existing infra volumes. Logstash reads only project `logs/*.log` JSON files; old non-JSON lines are dropped. In Kibana http://localhost:5602, create a data view `offlinepay-logs-*` with time field `@timestamp`. Search `requestId : "m6-manual-check-001"` or a traceId. Kibana's display timezone is its own UI setting: choose Asia/Kolkata if needed. Application rolling gzip archives are not imported by this simple tailer. Events are ingested at least once, so file rotation/reingestion may duplicate log records; this has no effect on money or notification idempotency.

This single-node local search demo disables Elastic authentication and publishes only loopback ports. It is not an internet deployment. Its container limits total over3GB and are not a promise it fits alongside the apps. Search-index disk retention is not automatic; the100MB/7-day limit applies to each application's rotated files, not Elasticsearch indexes. Stop after inspection:

```powershell
docker compose -f .\compose.observe.yml --profile logs stop
```

Keep data volumes unless you deliberately want to discard that log-search history. Do not use `down -v` on the existing project.

## Automated traffic and deployment exercises

Follow `benchmarks/README.md` for **separate databases/topics**, 1VU first, then5/10 only if memory permits. `mixed.js` records wallet/transfer p95/p99, accepted writes, rejected requests and thresholds. `replay.js` tests concurrent duplicate identity. Run both reconciliation SQL files after queues drain. No guessed CV numbers. `deploy/kubernetes/README.md` explains the application-only manifests and external infrastructure/Secrets/HTTPS/Metrics Server prerequisites; a schema check is not a running autoscaler test.

After M6 acceptance, read M4-M6 code together. M7 remains Android/Room/Keystore, QR offline exchange then BLE/NFC and automatic durable sync. Accepted longer-window/signed-deadline and sender-or-receiver submission/reminder changes remain backlog for that work. No early release/fine was added.
