# Automated load tests

**GitHub Actions capacity run:** [github-capacity-test.md](../docs/github-capacity-test.md) describes disposable Docker startup, fixed-VU stages, longer confirmation, resource/error diagnostics and financial/notification reconciliation. This pipeline must actually execute before any new capacity number can be claimed.

**Direct1,000-RPS target:** [target-rate-test.md](../docs/target-rate-test.md) adds `-TargetRps 1000` to the guarded runner and records dropped iterations plus a measured business-window rate. [Actual automatic-run analysis](../docs/m6-automated-load-analysis.md) records the successful189.4-RPS short stage, memory stop, delivery drain and final reconciled counts from the uploaded user report.

**One-command staged search:** [capacity-automation.md](../docs/capacity-automation.md). `scripts/Find-LocalCapacity.ps1` runs bounded stages, samples host/queue activity, stops on pressure/failures, waits for delivery and reconciles both load databases. The user starts it once on Windows; it does not grant remote access or promise a10,000-concurrent-request result. [20/30-VU manual exploration](../docs/m6-capacity-progress.md) is recorded separately from the fully reconciled1/5/10-VU baseline.

k6 generates requests automatically. Postman is for manual behavior checks; it is not necessary to click thousands of times. A short closed-model run gives an achieved rate under its exact workload, not a maximum users/RPS guarantee.

**Current evidence:** `docs/m6-load-results.md` records the user's PostgreSQL 1/5/10-VU, 30-second runs and successful financial/notification reconciliation. The 10-VU run achieved about163 mixed requests/s, transfer p95 333ms, zero request failures and complete eventual queue drain. These are scoped local measurements, not maximum or sustained notification capacity. The authoring host used an H2 functional smoke to verify script/API wiring; it could not run PostgreSQL/Docker integration tests. See `docs/milestone-6-report.md` for those separate scopes and `docs/m6-first-load.md` for the summary-export correction.

## Isolated Windows benchmark deployment

Run every command from project root, one at a time. Keep IntelliJ, DBeaver and ELK closed. Stop all four normal application JARs first; keep the existing infrastructure containers/data. Do not point the load runner at the normal core/notification databases. Never delete/reset existing volumes.

1. Create **new** benchmark databases with the existing roles (once). These commands do not alter the normal databases. If either name already exists, inspect it before deciding to reuse it; do not drop it blindly.

```powershell
docker compose exec postgres psql -U offlinepay -d postgres -c "CREATE DATABASE offlinepay_load OWNER offlinepay;"
docker compose exec postgres psql -U offlinepay -d postgres -c "CREATE DATABASE offlinepay_notifications_load OWNER offlinepay_notifications;"
```

2. Create dedicated topics. The normal topic/offsets remain intact:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic offlinepay.load.payment-committed.v1 --partitions 3 --replication-factor 1
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists --topic offlinepay.load.payment-committed.v1.DLT --partitions 3 --replication-factor 1
```

3. Start Eureka/Gateway as in `docs/milestone-6.md`, profile `observe`. In the **core** PowerShell window, after importing the existing private environment and before starting its JAR, set:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5436/offlinepay_load'
$env:AUTH_RATE_LIMIT = '10000'
$env:API_RATE_LIMIT = '100000'
$env:SPRING_DATA_REDIS_DATABASE = '1'
$env:TRACE_SAMPLE_PROBABILITY = '0'
```

Start core with these **additional** flags:
`--offlinepay.messaging.topic=offlinepay.load.payment-committed.v1 --logging.file.name=logs/load-core.log`.
The core profile must be `demo,split,observe`. Keep rate limiting enabled: the benchmark explicitly raises its budgets to avoid measuring the normal120/min protection policy. These settings apply only to this isolated process.

4. In the **notifications** window, after environment import and before its JAR:

```powershell
$env:NOTIFICATION_DB_URL = 'jdbc:postgresql://localhost:5436/offlinepay_notifications_load'
$env:TRACE_SAMPLE_PROBABILITY = '0'
```

Add the same topic flag and `--logging.file.name=logs/load-notifications.log`. Flyway initializes only the two new empty databases; no M5 import is used for these new fixtures. Leave listeners/workers enabled. Keep normal applications stopped so there is only one active owner for these consumer groups. Core uses Redis DB1 here; it does not clear DB0.

5. In **Gateway and Eureka** windows also set `$env:TRACE_SAMPLE_PROBABILITY='0'` before starting. Wait for all services registered/UP, all management readiness endpoints200 and the two new Kafka topics available. Public test URL remains9030. Record `docker stats --no-stream` and Windows total memory before/after each stage. Heap caps are not total RSS. If the machine becomes unresponsive or approaches sustained90% memory, stop the run normally and reduce the workload; that run is inconclusive, not an API capacity result.

## Run k6 (separate PowerShell window)

Download the pinned **k6 v1.3.0** binary from the official Grafana/k6 GitHub release and verify its checksum. Put `k6.exe` on PATH or use its full path. The JS does not require Node/npm. Create the ignored output folder:

```powershell
New-Item -ItemType Directory -Force .\benchmarks\results
$env:ALLOW_DEMO_LOAD = 'yes'
$env:BASE_URL = 'http://localhost:9030'
$env:RUN_ID = 'm6-replay-001'
k6 run .\benchmarks\replay.js
```

That creates fresh users, adds demo funds and sends two concurrent identical payments. Both must return the same transaction ID; changed payload with the same key must return409. It does not print/persist access or refresh tokens.

For a first **1 VU /30s** run:

```powershell
$env:RUN_ID = 'm6-one-vu-001'
$env:VUS = '1'
$env:DURATION = '30s'
$env:SUMMARY = '.\benchmarks\results\one-vu.json'
k6 run .\benchmarks\mixed.js
python .\scripts\read_load_summary.py .\benchmarks\results\one-vu.json
```

The Python summary reader is optional. If Python is not installed, share the JSON file directly for analysis.

If memory and checks remain healthy, set a new RUN_ID, VUS=5 and SUMMARY path, then run again. Move to10 VUs only after reviewing the previous run. A VU is a concurrent executing test worker, **not** a unique logged-in customer: this scenario intentionally shares one hot sender and receiver to exercise lock contention. The loop targets7 wallet reads and3 fresh INR1 transfers per10 iterations; actual mix at the end of the run varies slightly. No think-time by default. Setup/signup/login/topup is separate from custom business latency metrics; k6's total HTTP metric also includes setup requests.

Thresholds: <1% HTTP/business failures, wallet p95<1s, transfer p95<2s. They are chosen acceptance budgets, not promises or previous measurements. Nonzero k6 exit means a failed run: inspect429/5xx and CPU/memory/queue metrics before changing thresholds. The default normal API limiter120/min will intentionally reject this workload if the isolated overrides were missed. Authentication uses existing CSRF-cookie handling; business APIs use Bearer ATs. JSON log writing stays enabled while trace export is sampled0 for this run; record that choice.

## Reconcile after each run

Wait for all core outbox events PUBLISHED and notification consumer drain; a successful synchronous transfer does not mean its asynchronous notice already arrived. The current relay polls20 events/2s, so a write-heavy burst may build a backlog. Record drain time separately from API latency; do not claim sustained end-to-end capacity while the queue keeps growing.

```powershell
Get-Content -Raw .\benchmarks\reconcile-core.sql | docker compose exec -T postgres psql -U offlinepay -d offlinepay_load -v ON_ERROR_STOP=1 -f -
Get-Content -Raw .\benchmarks\reconcile-notifications.sql | docker compose exec -T postgres psql -U offlinepay_notifications -d offlinepay_notifications_load -v ON_ERROR_STOP=1 -f -
```

Require zero negative balances, unbalanced operations, duplicate keys and duplicate notices. Wallet total must equal demo issuance, consumed events must equal committed operations, notices must match distinct participants, no unexplained DLT or FAILED event may remain. API response success alone is insufficient. Run `explain.sql` queries in their respective database after populating realistic row counts; do not assume a query-plan improvement without inspecting it.

Share the JSON summary, both SQL outputs, resource snapshots, Java version and run settings for analysis. They contain test identifiers and metrics, but inspect files for secrets before sharing. No maximum-capacity CV claim until this PostgreSQL run and reconciliation pass. Restore normal startup in **fresh** PowerShell windows to remove benchmark-only environment overrides. Normal DB balances and topics are retained.

## Summary export correction

Update `mixed.js` before another run. The initial exporter accidentally included k6 setup_data and test access tokens; the corrected exporter uses a field allowlist. Apply the cleaning steps in `docs/m6-first-load.md` to the existing report. `benchmarks/summary-test.js` verifies the export without API calls or data changes.
