# M6 verification and benchmark status

**Current PostgreSQL result:** the user completed1/5/10-VU,30-second runs and both databases reconciled after every stage. At10 VUs:163.10 mixed requests/s,333.32ms transfer p95, zero request failures. Final cumulative2,986 published/consumed events and5,968 notices match. See [m6-load-results.md](m6-load-results.md) and its credential-free JSON for exact measurements, settings and limitations.

Date: 22 October 2026 (IST). Project target/CI: Java21. User benchmark runtime: Java21.0.11. Authoring runtime: OpenJDK17.0.20, Maven3.9.11 with the explicit `-Djava.version=17` override. The two execution environments are distinct.

## Authoring-host checks executed previously

| Check | Result | Scope |
|---|---|---|
| Four-module Maven package + Surefire | **100 tests, 0 failures/errors/skips** | Core87, notifications10, gateway3; includes real embedded Kafka test and a cached-gauge SQL-failure test |
| Eureka regression transport check | **PASS** | Existing real registry/Gateway/notification-Feign harness still passes routing,401/404/503 and deregistration cases |
| Real four-JAR HTTP smoke | **PASS** | Random loopback ports, Eureka routing, actual financial core and actual notification/OpenFeign; disposable H2 databases, real Redis7.4.11 |
| Prometheus endpoint | **PASS** | All four management listeners expose JVM and HTTP histogram metrics; env not exposed; no public gateway metrics route |
| Counter transaction semantics | **PASS** | Exactly1 committed transfer increments counter; replay adds0; rejected insufficient-funds attempt adds0 |
| Cross-service HTTP trace | **PASS** | Gateway, notifications and core export spans sharing one traceId to a local Zipkin-format HTTP collector; actual Zipkin UI/server not started |
| JSON logs | **PASS** | Parsed JSON with service, requestId/traceId, timestamps+05:30; known smoke password/JWT secret/AT absent in captured app files |
| Readiness/liveness | **PASS** | All readiness200 initially; stopping isolated Redis makes core readiness503 while liveness remains200 |
| k6 mixed functional smoke | **PASS** | k6v1.3.0,1VU,10s,150ms think time,59 business requests:42 reads/17 accepted transfers,0 business failures,0 rate-limit rejections,0 server errors |
| k6 concurrent replay | **PASS** | Two concurrent identical requests return one transaction ID; changed payload with same key409 |
| Prometheus config/rules | **PASS** | `promtool`3.15.1: config and5 rules valid |
| Kubernetes schema | **PASS** | `kubeconform`0.7.0 strict validation against1.35.0:13 valid,0 errors/skips |

The10-second smoke uses H2 and artificial think time. Its request count is functional evidence, **not** PostgreSQL throughput, maximum capacity, end-to-end Kafka delivery or a CV performance figure. The local Zipkin-format collector validates export/propagation, not Zipkin search UI or the Docker image. Log secret absence in a controlled smoke is not a proof that arbitrary future log statements can never leak data.

## Remaining checks and authoring-host limits

- The user-machine PostgreSQL load/reconciliation checks above passed. The authoring host could not run PostgreSQL: no Docker/Podman is installed, and native PostgreSQL17.6 initdb refuses its only available uid0. That safeguard was not bypassed. H2 smoke timings do not substitute for the user PostgreSQL results.
- Full Maven `verify` was attempted, but six root Testcontainers integration classes failed at environment initialization because Docker was unavailable. They are **not passed tests**. The successful100 count is the subsequent reactor `package`/Surefire run; it excludes Docker-dependent integration suites. Notification PostgreSQL integration is also pending. CI is configured to run verify on a Docker-capable Java21 runner when published.
- Elasticsearch/Logstash/Kibana Compose startup, actual Zipkin Docker startup, Java21 container image builds and Kubernetes/HPA runtime scaling are pending. The delivered examples are not a claim of deployed or production-ready infrastructure.
- PostgreSQL query plans/tuning and durable consumer backlog drain under sustained load are pending. Existing rate limits, leases, retries and bounded provider bulkhead remain the backpressure controls; M6 does not turn them into unlimited capacity.

## Reproducible PostgreSQL workload

Follow `benchmarks/README.md`: separate core/notification databases, dedicated topics, RedisDB1, fresh virtual-money users and raised but enabled test-only limits. Start1VU/30s, then5 and10 only if the8GB laptop remains responsive. Capture achieved request rate, both endpoint p95/p99, accepted/rejected counts, queue growth/drain, resource snapshots and both reconciliation SQL outputs. Record Java21 build/runtime and same-host k6 contention.

| Report field | Current10-VU result / scope |
|---|---|
| PostgreSQL achieved business requests/s | **163.10**, mixed reads/transfers,30 active seconds |
| Transfer p95 / p99 | **333.32 /481.82ms** |
| Wallet read p95 / p99 | **19.91 /34.42ms** |
| Accepted/rejected PostgreSQL load requests | **5,131 accepted; zero business/HTTP failures** |
| Sustained notification drain rate/backlog | Backlog observed then fully drained; elapsed drain time and sustained rate unmeasured |
| Post-load wallet/ledger/notices invariants | **PASS** at1/5/10 VUs; final2,986 events /5,968 notices |
| Maximum concurrent customers | **Not established by a shared-wallet VU test** |

The financial total must equal demo issuance, every operation must balance, one key must not create two operations, and notification counts must match distinct participants after drain. Failed thresholds or growing queues must be reported, not removed from the result. A test budget and configured HPA are not performance achievements.

A defensible measured CV point: “Load-tested a Spring Boot/PostgreSQL wallet backend with k6 at10 virtual users, recording ~163 requests/sec across a70% read /30% transfer workload and333ms transfer p95 in a30-second local run, with zero request failures.” The workload uses one hot sender, same-host k6, raised test-only rate budgets, JSON logging and trace sampling0. This does not establish maximum capacity, payment-only TPS, sustained notification throughput or production readiness.

## User PostgreSQL reconciliation and exporter correction

See `m6-load-results.md` for all three runs, successful SQL checkpoints and asynchronous backlog snapshots. Final totals conserve40,000,000paise issued funds;2,982 transfers have balanced journals, no duplicate keys and no negative wallets. All2,986 events are published and consumed; expected/actual notices equal5,968 with zero duplicates and open DLT. The initial post10-VU snapshot still had368 pending and1 processing outbox row; a subsequent check confirmed complete drain, without a timestamped duration. API throughput and eventual notification completeness are separate results.

`m6-first-load.md` records accidental setup_data/access-token inclusion in the initial exporter and its correction. The exporter now allowlists report fields. Three actual-k6 regression checks pass for omitted credentials/unknown fields, preserved measurements and unmodified input. A real setup sentinel also exercises the final summary export. None of the original access tokens is included in the redacted evidence or source ZIPs. These are documentation/script checks, not additional Java tests or a rerun of the historical100-test suite.
