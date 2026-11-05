# Inspect Kafka messages and application logs

These are optional diagnostic tools for the existing project. Kafka UI and ELK serve different purposes: Kafbat shows records retained in Kafka and consumer progress; Kibana searches application logs. Neither is the financial source of truth; use the PostgreSQL ledger and receipts to confirm settlement.

## 1. Kafbat UI: existing OfflinePay Kafka

The base `compose.yaml` now includes a `kafbat-ui` service under the optional `kafka-ui` profile. The image is pinned to `ghcr.io/kafbat/kafka-ui:v1.5.0`. It joins the same default Compose network as Kafka and connects to `kafka:29092`, not host `localhost:9094`.

From the project root, with Docker Desktop running and the existing local `.env` present:

```powershell
cd C:\Users\rishe\OfflinePay_Milestone_1\offlinepay
docker compose --profile kafka-ui up -d kafbat-ui
docker compose ps
```

This starts the Kafka dependency if necessary; it does not start the Java app, PostgreSQL or Redis. If the UI is still starting, inspect:

```powershell
docker compose logs --tail 50 kafbat-ui
```

Open http://localhost:8082 and select `offlinepay-local`. Port 8082 avoids the earlier standalone Kafbat UI on 8081. To choose another port, add `KAFBAT_UI_PORT=8083` to your local `.env` and recreate just `kafbat-ui`.

- Open Topics, then `offlinepay.payment-committed.v1`, then Messages. Select String/JSON display and an earliest/oldest offset when inspecting previously published records.
- Inspect `offlinepay.payment-committed.v1.DLT` for dead-letter records. It can be empty during normal operation.
- The isolated load-test topics use `offlinepay.load.payment-committed.v1` and its `.DLT` topic.
- Inspect consumer groups for committed offsets and lag. Lag means delivery is behind; it does not mean a financial transfer rolled back.
- During a normal authenticated INR 1 demo transfer, locate the event using the transaction ID returned by the API. Kafka publication/notification processing is asynchronous, so refresh after the outbox relay publishes.
- Kafka retains a record after a consumer reads it, subject to topic retention. An empty page can be a selected-offset/filter issue or expired retention; it does not alone prove that nothing was published.

The cluster is read-only in the UI, so message production, topic deletion and offset changes are disabled there. This is a UI restriction, not Kafka broker authorization. Dynamic configuration is disabled; only the loopback address is published. No Docker socket or private application keys are mounted.

The container has a 512 MiB memory limit and a 256 MiB Java heap cap. This does not promise enough host RAM for every other service. Keep this tool off during capacity measurements, so historical benchmark results remain comparable.

Stop only the UI when done:

```powershell
docker compose stop kafbat-ui
```

## 2. Existing ELK: logs to Logstash, Elasticsearch and Kibana

ELK is already configured in the independent `compose.observe.yml`; do not duplicate it in the base Compose file.

- Elasticsearch 9.5.5 stores/searches logs: http://localhost:9201.
- Logstash 9.5.5 reads project `logs/*.log` through the read-only `/input` mount and parses JSON using `observability/logstash.conf`.
- Kibana 9.5.5 searches/displays the indexed logs: http://localhost:5602.
- Named volumes `search-data` and `logstash-state` preserve indexes and ingestion checkpoints.

Run the existing Java applications with the `observe` profile as described in [M6 startup](milestone-6.md), make a normal API request with `X-Request-ID: offlinepay-observe-demo-001`, and check that JSON records were written to the project's `logs` folder. These commands assume the Java JARs run on the host with project root as their working directory. The old base `app` service writes to a named log volume, which this host-folder pipeline does not mount.

On the 8 GB laptop, collect logs first, stop the application JARs with Ctrl+C, and stop unneeded infrastructure before running ELK:

```powershell
docker compose stop kafbat-ui postgres kafka redis
docker compose -f .\compose.observe.yml --profile logs up -d
docker compose -f .\compose.observe.yml --profile logs ps
```

Do not use that stop command while a payment or test is in progress. The three ELK container limits total 3 GiB; the full Java/Kafka/ELK stack is not sized to run together on this laptop. On a host with sufficient RAM the host log folder can also be tailed live.

Once Kibana starts, go to Stack Management -> Data Views -> Create data view:

- Name/index pattern: `offlinepay-logs-*`
- Timestamp field: `@timestamp`

In Discover, select the data view, widen the time range to include the collected logs, and try:

```text
requestId : "offlinepay-observe-demo-001"
level : "ERROR"
service : "offlinepay-core"
```

Set Kibana's display timezone to `Asia/Kolkata` if desired. Use the same `requestId` or `traceId` from a log to find related entries; exporting traces to the optional Zipkin stack is a separate feature.

Only active `*.log` files are read, not rotated `.gz` archives. Old non-JSON lines are dropped. Log ingestion is at least once and may show duplicates after rotation/reingestion; this is unrelated to financial or notification duplicate protection. Search-index retention is not automatically configured. Existing ELK authentication is disabled for the local exercise and ports are loopback-only.

Stop ELK without removing its volumes:

```powershell
docker compose -f .\compose.observe.yml --profile logs stop
```

Restart only the infrastructure needed for the next application session:

```powershell
docker compose up -d postgres kafka redis
```

## What to compare during a payment

| Place | What it tells you |
| --- | --- |
| API receipt / PostgreSQL ledger | Whether the payment committed and how balances changed |
| Kafbat topic | Whether the committed payment event reached Kafka |
| Kafbat consumer group | Whether the notification consumer has progressed |
| Notification API | Whether the user's in-app notice exists |
| Kibana | What the applications logged while handling the operation |

## Validation scope

The change adds optional Kafbat configuration and reuses the existing ELK pipeline. It does not alter financial code, topic names, existing volume names or the default infrastructure startup command. Validated with Docker Compose 2.39.4: the default configuration excludes Kafbat, the kafka-ui profile resolves the internal broker and localhost:8082 binding, and the logs profile resolves Elasticsearch/Logstash/Kibana without Zipkin. YAML parsing and the existing volume names were also checked. Live container/browser ingestion checks remain pending; configuration validation does not establish runtime acceptance. No new capacity benchmark was performed.

References: [Kafbat configuration](https://ui.docs.kafbat.io/configuration/misc-configuration-properties), [Kafbat 1.5.0 release](https://github.com/kafbat/kafka-ui/releases/tag/v1.5.0), [existing ELK configuration](../compose.observe.yml).
