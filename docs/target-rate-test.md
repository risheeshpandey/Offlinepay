# Direct 1,000 requests/second target attempt

This adds a constant-arrival-rate scenario to the existing Windows capacity runner. It attempts the requested schedule independently of response time, with a bounded worker pool and the same host-memory guards. Configuring a target is not evidence that it was achieved. The next actual run must execute on the user's Windows/PostgreSQL stack; authoring smoke fixtures supply no performance claim.

## Apply

Merge the patch ZIP's `offlinepay/scripts`, `offlinepay/benchmarks` and `offlinepay/docs` into the existing project. The updated `Find-LocalCapacity.ps1` supersedes the small native-launch fix and keeps that correction. No Java files, migrations, application controls, heap settings, private keys or environment secrets change. The four running JARs can stay running; no Maven rebuild/restart is needed.

Use the same isolated load deployment: core `offlinepay_load`, notifications `offlinepay_notifications_load`, dedicated `offlinepay.load.payment-committed.v1` topic, RedisDB1, raised but enabled test rate budgets, JSON logs, trace sampling0. Ordinary demo data must not receive load. The runner validates an existing synthetic load wallet/inbox through Gateway before creating fresh fixtures.

In a fifth project-root PowerShell window, first check readiness and current RAM:

```powershell
9130,9131,9132,9762 | ForEach-Object {
    $port = $_
    try {
        $health = Invoke-RestMethod "http://localhost:$port/actuator/health/readiness" -TimeoutSec 5
        "${port}: $($health.status)"
    } catch { "${port}: NOT_READY" }
}
```

```powershell
Get-CimInstance Win32_OperatingSystem | Select-Object TotalVisibleMemorySize,FreePhysicalMemory
```

When all four are UP, run separately:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned -Force
```

```powershell
Unblock-File -LiteralPath .\scripts\Find-LocalCapacity.ps1
```

```powershell
.\scripts\Find-LocalCapacity.ps1 -TargetRps 1000 -MaxVUs 40 -StageSeconds 30
```

The revision line is `Capacity runner: target-rate-1`. This directly requests1,000 business iterations/s for30 seconds; it does not run the old VU ladder first. Each business iteration makes one wallet-read or transfer HTTP request. Setup/login/topup requests are separate. The worker pool starts with10 VUs and can grow to40. The cap limits local resource use and is not a claim that40 VUs suffice for1,000 RPS.

The workload remains approximately70% reads /30% new INR1 transfers sharing one sender/receiver. Keep this separate from any later `-WalletPairs 8` run, which changes contention/workload. A1,000 mixed-RPS target is not1,000 payment transfers/s or1,000 simultaneous requests.

## How the outcome is decided

- Native `dropped_iterations` is recorded. If all workers are busy, scheduled iterations can be dropped. A zero-drop threshold, failed-request thresholds and endpoint p95 thresholds have abort-on-fail with a10-second evaluation delay.
- A stage passes only with k6 exit0, no external/memory stop, positive business/transfer samples, all error/latency thresholds passing, zero dropped iterations with its threshold passing, and at least the full configured number of completed iterations (30,000 for1,000/s x30s).
- Existing financial/notification SQL checks and accepted-transfer/topup delta checks must also pass after delivery drain. A canceled request can commit without its HTTP result being recorded; an aborted stage is never accepted just because its partial latencies/error rates look good.
- Status `TARGET_RATE_NOT_VERIFIED` means the configured target was not demonstrated. Dropped arrivals can reflect the worker cap, host generator constraints or long responses; they alone do not establish the backend's maximum. `HOST_MEMORY_LIMIT` means a host guard stopped the generator.
- The script does not run a longer confirmation after this single target attempt. A successful30-second test is still a short observation, not a sustained/production maximum.

## Read the right rate

`businessRps` is the existing k6 counter rate using total test runtime, including setup. The new `businessWindowRps` / `completedBusinessRpsExcludingSetup` divides completed business requests by the observed interval from the first business request start to the last completed business request. It excludes setup and includes the completion tail. These completed requests include accepted and failed responses; failure rates and accepted-transfer counts remain separate metrics. Small finite-window differences around the requested rate are possible. Do not replace measured values with the configured1,000.

The same `capacity-results.zip` contains redacted summaries, sampled resources and reconciliation snapshots. It excludes raw stdout/stderr and setup credentials. Share the generated ZIP after the runner finishes; applications remain running so committed work can drain. The default delivery wait is up to600 seconds per stage, and a long backlog can delay completion after traffic stops.

## Verification

On the authoring Linux host with PowerShell7.4.13 and k6v1.3.0:21 policy checks pass, including rejection of dropped/missing/incomplete target schedules; six real native-launch regressions pass; three real-k6 target export checks pass, including setup-token omission and the rate denominator. The real PowerShell/k6 process smoke exercises a successful20/s fixture schedule, an intentionally under-provisioned200/s fixture schedule that drops iterations and fails, ordinary VU mode, isolation-route checks and memory-triggered stop. Fake HTTP/SQL/CIM timings are not project capacity measurements. Actual Windows/PostgreSQL execution remains the user's next step. No Java tests were rerun for this script-only update.

References: [constant-arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/).
