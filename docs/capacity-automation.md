# One-command local capacity search

The updated `target-rate-1` revision also supports a **direct arrival-rate target attempt**. See [target-rate-test.md](target-rate-test.md); it retains native-launch fixes, resource stops and reconciliation. Without `-TargetRps`, the original VU ladder remains the default. [The actual first automatic-run analysis](m6-automated-load-analysis.md) records a successful short10-VU stage and a host-memory stop at20 VUs.

This runs the tests on **your Windows laptop**. It does not give the assistant remote control. The script automates the repeated commands and produces results for review. Do not describe an unexecuted runner, configured VU ceiling or memory-limited run as measured backend maximum capacity.

## Apply and start

Merge the ZIP's `offlinepay/benchmarks`, `offlinepay/scripts` and `offlinepay/docs` into the same folders in your existing project. There are no Java/application configuration changes and no Maven reload or rebuild. Keep PostgreSQL, Kafka, Redis and the four existing load-configured JARs running. Do not run another manual k6 session alongside this runner.

Prerequisites already established in the manual tests: k6 v1.3.0 on PATH, Docker Compose, `offlinepay_load` / `offlinepay_notifications_load`, dedicated load topics, Redis DB1, test-only raised but enabled rate budgets, trace sampling0, JSON logging, the existing capped heaps. The normal core/notification processes remain stopped. At least one existing `Load Alice` fixture with password `LoadDemoPassword123!` and a delivered notice must exist in the isolated databases. The preflight uses that public synthetic fixture to check Gateway routing before creating any new money/users. It creates a login session in the load environment, but never saves its tokens/cookies.

From `C:\Users\rishe\OfflinePay_Milestone_1\offlinepay`, run separately:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
```

```powershell
Unblock-File -LiteralPath .\scripts\Find-LocalCapacity.ps1
```

```powershell
.\scripts\Find-LocalCapacity.ps1
```

No database password needs to be pasted into the runner. Its read-only SQL uses the same existing container-local psql access used in the manual reconciliation. The running JARs retain their own environment configuration.

## What is automatic

1. Wait for any previous outbox/notification backlog to drain, validate SQL invariants, and wait for RAM headroom. Check the Gateway wallet/inbox against an existing isolated SQL fixture. A wrong route or missing fixture stops without starting financial load.
2. Run fresh stages at 10,20,30,40,60,80,100 VUs for30 seconds each. The business loop is the existing manual `mixed.js` workload: approximately70% reads and30% new INR1 transfers. By default all workers share one wallet pair. Each stage creates new demo users and INR100,000 virtual issuance per pair.
3. Sample Windows physical memory/CPU and all Java process working sets approximately every2 seconds; sample outbox counts and PostgreSQL lock-wait/active-connection counts about every10 seconds. Samples include startup/setup, with their timestamps; the nominal interval can lengthen during SQL/CIM calls. SQL query text, tokens and credentials are not collected. The lock-wait sample includes database-wide activity, and active connections include the monitoring query itself.
4. Stop the generator on fewer than512MiB free or at least95% used RAM immediately, or on at least90% used RAM for five consecutive samples. Before each new stage require at least800MiB free and below90% used, with up to120 seconds recovery wait. These guards reduce risk; they cannot guarantee that Windows will never become unresponsive.
5. k6 aborts failing HTTP/business error-rate or endpoint p95 thresholds after a10-second evaluation delay. Thresholds remain: failure rates <1%, wallet p95 <1s, transfer p95 <2s. Nonzero exits, missing summaries/thresholds, zero-workload runs and externally stopped runs do not count as successful capacity measurements.
6. After each stage, reconcile both databases automatically, waiting up to600 seconds for delivery. Check per-wallet available/reserved balances against their journal as well as total issued funds, negative balances, balanced operations, duplicate keys, outbox/event counts, notice counts, duplicate notices and open DLT. A successful stage must also have SQL deltas equal accepted transfers and one topup per fixture pair. The checks assume no other generator writes to these isolated databases.
7. Stop the search after a failed stage, resource/SQL failure, delivery timeout, configured search ceiling, or two consecutive stage transitions with <5% throughput gain and nondecreasing transfer p95. A plateau is a heuristic observation, not proof of an absolute maximum. Setup time is included in k6 counter rates; especially with more fixture pairs it affects comparisons.
8. If eligible and resources allow, repeat the best passing short-stage VU level for120 seconds. This is a two-minute API confirmation, not a production soak. Reconcile again and record delivery catch-up separately. No confirmation is started after a host-memory stop or serious monitoring/invariant failure.

`Ctrl+C` stops the runner; its `finally` attempts to stop only its own k6 child, restore the prior process environment and write partial results. Memory protection first uses k6's local REST stop endpoint; if it does not exit, only that k6 child is killed. Forced termination can lose the k6 summary; that stage is incomplete, never silently passed. Applications and Docker data are not stopped/reset. A failed monitor call stops the search; inspect saved status and leave applications running so committed work can drain.

## Result and meaning

The console prints the output directory under ignored `benchmarks/results/auto-...`. It contains:

- `capacity-report.md`: human-readable stage table, stop reason and confirmation status.
- `capacity-report.json`: the same status plus sampled host/queue measurements and final SQL snapshots.
- `*-summary.json`: k6 allowlisted measurements; no setup_data or fixture tokens.
- `*-resources.csv`: time-stamped resource observations, not guaranteed instantaneous peaks.
- `capacity-results.zip`: report, summaries and resource CSVs for sharing. Raw k6 stdout/stderr are excluded.

Send **capacity-results.zip** for analysis. If blocked before the first stage, the report still records the reason and preflight observations; do not call it a failed backend capacity test. A host-memory stop means this machine reached the configured guard. A search ceiling means higher stages were not tested. A threshold failure brackets a workload/configuration limit but is not a precise universal maximum.

API acceptance and notification capacity are separate. `queueGrowthDuringTest` is the unpublished-event count just after the k6 child exits, after a drained start; queue samples give additional context. A growing backlog followed by successful catch-up proves eventual completeness, not that notifications kept pace during load. The present relay claims up to20 events with a2-second scheduling delay, so a high API write burst can exceed its background delivery rate. Do not change thresholds/polling/heap limits silently to make results look better.

## Optional later workloads

After reviewing the one-pair report, a separate run can use independent wallet pairs:

```powershell
.\scripts\Find-LocalCapacity.ps1 -WalletPairs 8
```

This is a different workload; keep its report separate. Twenty or thirty VUs sharing one locked sender do not describe thousands of independent wallets. The stage list can be set explicitly, for example `-Stages 10,20,30,40`; values are limited to200 in this local script. Do not set10,000 on this8GB machine. Fixed-arrival-rate tests, separate load-generator hosts, replica balancing and longer soak tests remain additional exercises. No paid cloud resources are provisioned by this runner.

## Verification performed for this automation

Launcher correction `native-launch-fix-1`: the first user run stopped before load with `The system cannot find the file specified`. The old `Get-Command ... -CommandType Application` lookup can return multiple matches; assigning `.Source` directly to `ProcessStartInfo.FileName` joins them into one invalid filename. That failure was reproduced with duplicate PATH entries. The fix resolves exactly one existing `.Path` for explicit `docker.exe` / `k6.exe`, validates the executable and working directory, and retains native error details instead of hiding the message after a colon. The report records resolved paths and runner revision. Replace only `scripts/Find-LocalCapacity.ps1` to apply the small launch-fix ZIP; no JAR rebuild/restart is required.

Six additional native-launch regressions passed under PowerShell7.4.13/Linux: reproduced the old duplicate-match failure; resolved one path; actually launched a child from a path with spaces using the real SQL-stdin/JSON-output code; rejected nonzero query exit without exposing its fake stderr secret; rejected a missing executable; retained the OS code/message for an invalid binary. This closes the earlier smoke test's stubbed native-launch gap. It does not replace actual Windows/PostgreSQL acceptance.

- PowerShell7.4.13 parser and16 pure policy checks passed on the authoring Linux host: money/journal mismatch, missing outbox/notices, duplicates/DLT, memory guards, failed/missing thresholds and aborted stages cannot pass.
- Real k6v1.3.0 export regression:3/3 checks; actual final summary omits a fake setup-token sentinel.
- Real PowerShell/k6 process smoke passed using fake HTTP responses and stubbed SQL/CIM. It exercises a complete stage, isolated wallet/inbox route preflight, summary parsing, reconciliation and REST-triggered memory stop without falsely accepting the aborted stage. Fake API timings are not retained as project performance evidence.
- Actual Windows PowerShell5.1/CIM behavior and PostgreSQL SQL execution in this new runner remain to be exercised on the user machine. Compatible5.1 syntax/APIs were chosen; Linux process tests do not prove Windows integration. No Docker/Windows access exists on the authoring host. No application code changed and the historical Java test suite was not rerun for these script-only additions.

Technical references: [k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [k6 local REST stop](https://grafana.com/docs/k6/latest/reference/k6-rest-api/), [PowerShell Start-Process](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/start-process?view=powershell-5.1).
