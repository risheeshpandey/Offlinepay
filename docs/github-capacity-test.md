# Capacity testing on a disposable GitHub Actions runner

This is execution infrastructure, not a new payment implementation. The existing Java services, PostgreSQL schema, wallet locking, outbox, Redis protection and notification code remain unchanged. The only change in `mixed.js` is returning its response for an optional diagnostic wrapper; existing Windows callers retain their behavior.

## Repository setup

Put the project contents at the GitHub repository root: `pom.xml`, `services`, `compose.capacity.yaml` and `.github` must be siblings. Do not upload only the outer `OfflinePay_Milestone_1` folder or nest `.github` under `offlinepay` inside the repository. Preserve local `.env`, device keys and Docker volumes on the laptop; none is required by this runner.

For a new repository, initialize it with a README so it has a default branch. The connected GitHub app must have access to that repository. Repository creation and workflow dispatch are not exposed by the current GitHub connector; an authorized push to branch `capacity-testing` starts this workflow instead. This project must not be uploaded into an unrelated eCommerce repository.

Two entry points:

1. Push the capacity files to branch `capacity-testing`; the path-filtered workflow starts once for that push.
2. Once the workflow is on the default branch, use **Actions → OfflinePay measured capacity → Run workflow**. Default inputs are `10,25,50,100,200,300,500,1000` VUs, `30` seconds per short stage and `180` seconds for confirmation, with recorded outbox polling of `100`ms. Input strings are passed through environment variables, quoted and validated; they are not interpolated as executable shell code.

Standard public-repository Ubuntu runners currently provide 4 vCPUs and 16 GB RAM; private repository limits/billing differ. Record the actual run's hardware rather than assuming it matches this description. The workflow uses `ubuntu-24.04` and a 90-minute job ceiling; the measurement process has a 70-minute external ceiling. Default tests normally stop earlier, but queue drain and image/build downloads can take time. A timeout or incomplete confirmation is not a passing result.

## What runs

- Java 21 build and Java unit checks, Python report-policy checks, a real-k6 summary privacy check, and Compose configuration validation.
- Pinned k6 1.3.0 download verified against the official release checksum. PostgreSQL 17.6, Kafka 3.9.1 and Redis 7.4.11-alpine use a unique disposable Compose project and loopback-only ephemeral host ports. Actual image IDs and software/hardware versions are recorded.
- Fresh `offlinepay_load` / `offlinepay_notifications_load` databases, separate notification role, random passwords/JWT/internal secret/signing key. No user `.env` or existing volume is read. Redis DB1 is dedicated to the new server. Protection stays enabled with explicit test budgets, and tracing sampling is zero.
- Eureka, core, notifications and Gateway JARs. The core pool stays10 and the notification pool3; Redis timeout remains500ms. Heap limits are core512 MiB, notifications256 MiB, Gateway256 MiB, Eureka192 MiB. Kafka heap is512 MiB. Outbox polling is explicitly overridden to100ms (batch20), compared with the normal2000ms application default. This is a measured deployment choice, recorded as `outboxPollMs`, not a Java business-logic change. These are not the same deployment settings as the Windows laptop.
- Concurrent replay preflight followed by SQL reconciliation and an actual Gateway login/wallet/notification identity check against the new databases.
- A 5-VU/20-second warmup, then fixed-VU short stages. Each business iteration is the existing approximately70% wallet reads /30% fresh INR1 transfers, one shared sender. Setup creates fresh synthetic accounts and a virtual INR100,000 topup for each stage. Setup/login/topup traffic is separate from the business window.
- The original rules: HTTP and business failure rates each `<1%`, wallet-read p95 `<1000ms`, transfer p95 `<2000ms`. No fixed-RPS target or zero-dropped-arrival rule is added to this closed-model test. Failed requests are still failures and are counted explicitly.
- No further higher stage after a failing stage. Among passing short stages, the highest measured throughput and the largest passing VU count are selected separately. They need not be the same stage. A longer confirmation is run for the best-throughput VU count; if successful and the highest-concurrency count differs, that count receives its own longer confirmation. If they are the same, one confirmation covers both. An unsuccessful confirmation preserves the short-stage evidence without promoting it to a longer-run claim.

## Reconciliation and resource guards

The runner uses the existing read-only `capacity-core.sql`, `capacity-notifications.sql` and `capacity-queues.sql` snapshots. Negative balances, duplicate keys/notices, unbalanced operations, per-wallet journal mismatches, inconsistent issued funds or open dead letters fail validation. Operation/outbox counts must agree. Each traffic stage must create exactly one topup and the database transfer delta must equal accepted client transfers. If a request committed without its response being recorded, the mismatch is retained and the stage is not passed.

After traffic, delivery can drain for up to600 seconds. Two equal, reconciled snapshots five seconds apart are required: all outbox events published/consumed and expected notifications delivered. A clean, fully reconciled drain is also required before confirmation begins, so a prior failed stage cannot carry its backlog into new confirmation traffic. API latency and eventual notification delivery are separate observations; a growing queue is not evidence of sustained real-time notification throughput. Drain time and the immediate after-traffic snapshot are retained.

Before traffic the runner waits up to120 seconds for at least800 MiB free and less than90% memory usage. During load it samples memory approximately every2 seconds and requests a stop for free memory below512 MiB, usage at least95%, or five consecutive samples at least90%. Docker/SQL/management samples add overhead and can lengthen that interval; this is a best-effort guard, not a guarantee. CPU counters, Java RSS, Docker stats, DB lock waiters/queues and JVM GC/pool metrics are sampled less frequently. The load generator and services share one machine, a limitation recorded in the report.

Only owned application/k6 processes and the randomly named Compose project are stopped/removed. Laptop containers and volumes are never part of this workflow.

## Read the artifact

**Actions → completed run → Artifacts → `offlinepay-capacity-<run>-<attempt>`** includes:

- `report.json` / `report.md`: hardware, configuration, every short stage, stop reason, confirmation and SQL snapshots.
- Per-stage summaries: observed business-window rate, whole-run rate including setup, p95/p99, errors and counts. `setup_data` and fixture access tokens are excluded.
- `resources.jsonl`, `jvm-metrics.jsonl`, `diagnostics.json`: scoped resource samples and Java exception class names. Arbitrary exception messages, raw console logs, environment files, private keys, cookies, response bodies and credentials are excluded.
- Safe failure counters distinguish503/504 responses, Redis-protection unavailability, database unavailability, insufficient virtual funds and other errors. Missing classified errors mean they were not observed, not that all dependencies were healthy.

`CONFIRMED_OBSERVATION` means the selected throughput and concurrency confirmation runs passed the configured rules and reconciliation. Their observed rates can differ from the short stages. It does not mean an unconditional maximum. If every configured level passes, the search ceiling was reached and higher levels remain untested. A resource stop describes that runner. Report concurrent **virtual users**, measured mixed API requests/sec, duration, failure rate, transfer/read latency, workload and hardware together. Do not call mixed RPS payment transfers/sec or turn a short observation into a production guarantee.

## Executed validation and baseline

The Java21 hosted CI completed184 unit/integration tests with zero failures or skipped tests, plus discovery and observability smoke checks. See [validation evidence](ci-validation.md). The first actual capacity run exposed an outbox throughput limit at the original2000ms poll interval; its failed confirmation is preserved in [baseline results](capacity-baseline.md). It is not a passing end-to-end capacity claim.

The runner now explicitly selects100ms polling for a new, separately recorded deployment measurement. Latency/error rules, batch20, database pools, financial checks and600-second drain budget remain the same. Increasing the drain budget or ignoring delivery would conceal the bottleneck; neither is used here.

Authoring checks include20 Python policy/selection tests,4 real-k6 summary checks and2 real-k6/mock-HTTP wiring cases. Mock timings are not performance evidence. The current authoring environment cannot host the full Docker stack; performance measurements execute in GitHub Actions.

References: [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners), [workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [workflow artifacts](https://docs.github.com/en/actions/tutorials/store-and-share-data), [k6 custom summaries](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/).

The initial tuned run passed the 200-VU ceiling. An extended search then confirmed 300 VUs for 180 seconds and stopped at 500 VUs because read p95 exceeded 1000ms; 1000 VUs was not executed. Both the throughput and concurrency confirmations passed financial and eventual-delivery reconciliation. See [actual results and retained reports](capacity-results.md). These observations do not establish an unconditional maximum.
