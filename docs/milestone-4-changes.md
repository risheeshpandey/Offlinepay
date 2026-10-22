# Milestone 3 -> 4 change map

Use OfflinePay_Milestone_4_Upgrade.zip for your existing Milestone 3 project. Merge the contents of its `offlinepay` folder into your existing project root. It contains only added/changed files, not the full old source. Back up your project/comments first.

## Existing production Java files changed

- `security/SecurityConfig.java`: installs the optional Redis request filter after Bearer authentication.
- `common/ApiExceptionHandler.java`: translates annotation constraint violations into a normal 400 response.

`OfflineSettlementService`, `OfflineSyncController`, WalletService, existing allowance/protocol/device/auth code and V1-V6 migrations are unchanged. The existing Spring payment event is reused for a new synchronous outbox listener. Comments in untouched files therefore remain if you apply the upgrade ZIP rather than overwrite from the full ZIP.

## New packages/files

- `messaging`: versioned payment event, outbox entity/repository/writer/lease transactions/relay, Kafka publisher/config/listeners, DLT archive.
- `notification`: durable notice/inbox, authenticated list/read APIs, delivery leases/worker/webhook.
- `security`: Redis Lua limiter and request filter.
- `V7__reliable_notifications.sql`: additive tables, constraints and indexes.
- M4 tests and Docker integration tests.

## Other changed files

pom.xml, compose.yaml, application.yml, test profile/fixture, README, Postman collection, verification document, cumulative skills PDF/generator and M4 lab/recovery/change documents. Maven reload is required. Do not copy/delete .env, local keys, local proofs/state, .idea or DB volumes. No GitHub push is performed.
