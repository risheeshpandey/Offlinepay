# First Windows/PostgreSQL load result and export correction

**Final follow-up:** the 1-, 5- and 10-VU runs now all have successful wallet, ledger, outbox and notification reconciliation. See [m6-load-results.md](m6-load-results.md) and its credential-free JSON for the complete measured results. The initial report below preserves the export correction history.

Source: user-provided k6 v1.3.0 result for `m6-one-vu-001`, 22 October2026 IST. See `m6-first-load-redacted.json` for the selected measurements; no setup data or access tokens are copied.

| Measurement | Result |
|---|---:|
| Workload | 1 worker,30s; one hot sender;7 reads /3 INR1 transfers per10 iterations |
| Business requests | 1448 (1015 reads,433 accepted transfers) |
| k6-reported business rate | 46.10 requests/s |
| Wallet read p95 / p99 | 26.19ms /44.97ms |
| Transfer p95 / p99 | 71.59ms /102.18ms |
| Request failure rate /429 /5xx | 0% /0 /0 |
| Configured thresholds | All passed |

The k6 counter rate uses31.4072946 seconds including setup. The active workload was30s. Total HTTP requests1457 also include9 setup requests. These are observed results under this workload, not maximum capacity or distinct-customer counts. Client and all services run on the same8GB Windows laptop. Shortly before the run, available RAM was about977MiB; peak resource usage was not captured. Test-only rate budgets were raised while Redis protection stayed enabled, trace export sampled0 and JSON logging enabled. Subsequent reconciliation passed:436 published/consumed events,870 expected/actual notices, conserved funds and zero negative wallets, unbalanced operations, duplicate keys/notices or open DLT.

The separate concurrent replay test passed11/11 checks; PostgreSQL confirmed one INR1 transfer, sender99999 and receiver1 after100000 demo issuance. That reconciles the replay fixture, not the433-transfer load run.

## Export bug and fix

The first exporter serialized the entire k6 summary object. k6 includes `setup_data` containing in-memory access tokens. The comment claiming tokens were excluded was incorrect. User output exposed this bug. `benchmarks/mixed.js` now constructs a report from an allowlist: root_group, state, metrics and generated run metadata. It never serializes setup_data. No access token from the user's output is reproduced in this report.

The report regression passed in real k6 v1.3.0:3/3 checks. It checks omitted setup/extra secrets, retained measurements and unmodified input; a real setup token sentinel also exercises k6's own handleSummary invocation. The following command makes no HTTP calls or database writes:

```powershell
k6 run -e ALLOW_DEMO_LOAD=yes -e SUMMARY=benchmarks/results/summary-test.json .\benchmarks\summary-test.js
```

Apply the small export-fix ZIP by replacing `benchmarks/mixed.js` and adding the included regression test. Also update `scripts/read_load_summary.py` for UTF8-BOM reports created by Windows PowerShell; the ZIP includes that small reader fix. Applications can keep running; no Java rebuild is needed for these external test scripts. The existing test measurements remain valid. Do not rerun the financial load just to recreate a report.

To clean the existing report in PowerShell without printing credentials:

```powershell
$loadReport = Get-Content -LiteralPath .\benchmarks\results\one-vu.json -Raw | ConvertFrom-Json
$loadReport | Select-Object root_group,state,metrics,offlinepayMetadata | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath .\benchmarks\results\one-vu.json -Encoding UTF8
```

The old pasted text still contains the test ATs; sanitizing a file does not revoke or erase copies of them. Access-token lifetime is30 minutes; no credential is repeated or placed in the patch. Use the allowlisted export for any future report sharing. Rate metric `business_failures.values.fails=1448` counts false observations of the failure predicate: the actual failure rate is0, matching the1448 successful business checks.
