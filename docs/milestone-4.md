# Milestone 4 - Reliable events and notifications

This release keeps the same application and database. It adds a transactional outbox, Kafka delivery, a durable idempotent notification inbox, retry/DLT handling, optional webhook delivery with Resilience4j and Redis-backed request limits. No real SMS/push provider credentials are bundled. Actual money settlement remains synchronous in PostgreSQL; Kafka never debits/credits a wallet.

## Upgrade without losing data or learning comments

1. Stop the application. Back up your existing project (including your comments) before merging source. Keep PostgreSQL data and Docker volumes.
2. Extract the ZIP elsewhere. Merge the CONTENTS of its inner `offlinepay` folder into your existing `C:\Users\rishe\OfflinePay_Milestone_1\offlinepay`. Do not nest another offlinepay folder. Replace source/config/docs/pom/Postman files, but preserve your `.env`, `.idea`, local-keys, local-device-rp, local-demo, all private keys, proof files and local state. Do not regenerate keys or rerun setup.
3. Reload Maven in IntelliJ. Target remains Java 21; retain working directory = project root and `-Duser.timezone=Asia/Kolkata`.
4. From the project directory run:

```powershell
docker compose up -d postgres kafka redis
docker compose ps
```

This Kafka is separate from your existing Kafka on 9092: OfflinePay uses host **9094** and Redis **6381**. PostgreSQL stays 5436, app stays 9030. Start only one app (IntelliJ OR Compose).

5. Run the app with your existing demo/JWT/database configuration. Flyway applies **V7 only** to a V6 database. V1-V6 are unchanged. No balances, existing deadlines, certificates or proofs are rewritten. The three new Docker volumes for infrastructure/logs are managed independently of the existing DB volume; never run `down -v`.
6. Check `GET http://localhost:9030/actuator/health`. Redis now participates in aggregate health. Check startup logs for topic creation/consumer assignment. A broker outage may delay startup topic checks; the outbox retains new events until recovery. If this is the first startup and topics were never created, bring Kafka up and restart the app so KafkaAdmin creates them; broker auto-creation is disabled.

If ports are customized, set KAFKA_BOOTSTRAP_SERVERS/REDIS_PORT for IntelliJ accordingly. App-container Kafka address is kafka:29092, not localhost. Compose supplies that address automatically. A blank `NOTIFICATION_WEBHOOK_URL` is the default: in-app notifications work without an external provider.

## The complete path

1. A NEW payment operation publishes a synchronous Spring event inside its money transaction.
2. OutboxWriter inserts immutable event identity/payload in the same transaction. Failure rolls back money, journal and outbox together. Existing history is not backfilled.
3. A scheduled relay claims up to 20 candidates using short row-lock transactions, a 60-second lease and random ownership token.
4. Outside DB transactions it sends a String JSON record keyed by event/transaction UUID to `offlinepay.payment-committed.v1`. Producer uses acknowledgements=all and idempotence. The relay marks PUBLISHED only after a send acknowledgement. A late stale worker cannot override a newer lease.
5. Consumer verifies key/schema and payload hash against this application's authoritative outbox, locks that event, checks consumed_payment_event, inserts one notice per distinct participating user and marks consumed in one transaction. This intentionally shares the monolith DB; independent service ownership comes later.
6. Kafka offset acknowledgement happens after that transaction commits. Crash/redelivery can repeat transport, but not the inbox effect. There is no cross-system exactly-once guarantee.
7. If configured, a separate delivery worker posts notifications to your provider adapter outside the money transaction. The stable notification UUID is the provider Idempotency-Key. Provider must honor it; otherwise duplicate external deliveries remain possible.

The existing AFTER_COMMIT listener still writes successful financial audit logs. It is not the durable outbox writer. New events cover topups, online transfers, reservations, offline settlements and reserve releases. Same-wallet operations produce one user notice; transfers/settlements produce two. This release does not change the receiver-only submission authorization from Milestone 3.

## Manual Postman lab (no scripts required)

Import the updated collection, or create these requests manually. Use the working cookie/CSRF login flow to get your AT; the APIs below require Bearer AT only.

### 1. Trigger a NEW operation

Old settled-proof retries return their original receipt and create no new event/notification. For a small repeatable lab, Alice can transfer INR 1 to Bob with a fresh Idempotency-Key using the existing transfer request. This changes your demo balances by INR 1; record before/after rather than expecting the old 650/350 snapshot forever. Alternatively create a fresh reservation and valid proof within its stored deadline.

Keep the returned transactionId. Wait several seconds for Kafka consumption.

### 2. Read each participant's own inbox

```http
GET http://localhost:9030/api/v1/notifications?page=0&size=20
Authorization: Bearer <Alice-or-Bob-AT>
```

Expect a notice referencing that transactionId. Login as the other participant and check their own inbox. There is no request userId parameter; identity comes from JWT. Response amounts are INR and Instants serialize to IST. It does not include provider configuration, tokens, raw proofs or contact details.

### 3. Mark read and verify privacy

```http
POST http://localhost:9030/api/v1/notifications/<own-notificationId>/read
Authorization: Bearer <owner-AT>
```

Body none. Repeat: readAt remains the original time. Another user's token must return NOTIFICATION_NOT_FOUND. No token returns 401. page >= 0 and size 1..100 are required.

### 4. Reconcile the event pipeline

Use the transaction UUID from step 1 (not paymentId from the offline device):

```sql
SELECT id, operation_id, status, attempts, next_attempt_at, last_error, published_at
FROM outbox_event ORDER BY created_at DESC LIMIT 20;
SELECT * FROM consumed_payment_event ORDER BY consumed_at DESC LIMIT 20;
SELECT id, event_id, user_id, kind, delivery_status, delivery_attempts, read_at
FROM user_notification ORDER BY created_at DESC LIMIT 20;
SELECT id, topic, partition_number, offset_number, event_key, status, created_at
FROM dead_letter_event ORDER BY created_at DESC LIMIT 20;
```

PUBLISHED means Kafka acknowledged the producer, not that every recipient read a message. Consumed record means inbox committed. DELIVERED means provider returned HTTP success, not that a person read it. IN_APP_ONLY means no webhook was configured. User readAt is independent of delivery status.

### 5. Broker failure and recovery

`docker compose stop kafka`, then make a NEW demo payment while PostgreSQL/Redis remain running. Its money transaction should succeed; outbox will retry. Restart with `docker compose start kafka`. Pending events should publish and create inbox notices. A crash after broker acceptance but before the marker can produce duplicates; inbox uniqueness makes the result stable. Do not leave Kafka down through all retry attempts unless testing FAILED handling. Tests also simulate rollback before commit and stale lease recovery.

### 6. Redis protection

Defaults: 30 auth requests/IP per 60-second window, 120 other API requests/authenticated user (anonymous requests use socket IP). Fixed window starts on first request; boundary bursts are possible. Auth bucket includes CSRF/login/signup/refresh/logout, even if a Bearer is sent. Forwarded headers are not trusted. Different app instances share Redis counts. NAT users share the anonymous/auth IP budget.

For a brief manual test set `API_RATE_LIMIT=3` in the IntelliJ run configuration and restart. Call an authenticated GET 4 times: expect 429 and Retry-After:60. Counters may already exist: wait for their expiry. Restore 120 afterwards. Health bypasses the filter. Authentication/CSRF failures can be rejected before the limiter; this is not an edge/WAF or complete brute-force defense.

Stop Redis: protected requests return 503 RATE_LIMIT_UNAVAILABLE and do not execute financial work. Restart Redis to recover. This deliberately fails closed; it trades API availability for request protection. Automatic clients must retry safely with the same payment/idempotency identity. Redis restarts/counter loss can reset limits; limits are abuse protection, never financial correctness.

### 7. Optional external notification delivery

Configure an operator-controlled HTTPS adapter URL with `NOTIFICATION_WEBHOOK_URL` and optional `NOTIFICATION_WEBHOOK_TOKEN` (keep token in local env, never commit). The adapter receives notificationId, transactionId, userId, kind and amountPaise. It owns recipient resolution and actual SMS/push delivery; our inbox does not store phone numbers in events.

Create a NEW operation after configuring: notices get PENDING delivery status. Existing IN_APP_ONLY notices do not automatically become external sends. Provider outage causes durable retries, max eight failures, then FAILED. Per-call connect timeout 2s, read timeout 3s, circuit breaker opens after at least 5 calls and >=50% failures in last 10; 30s open wait; 2 half-open probes. Semaphore bulkhead allows 4 concurrent calls with no queue. A blocked DNS resolver is not a hard total-request timeout; no stronger deadline is claimed. HTTP calls run outside DB transactions. No successful fallback conceals delivery failure.

## Retries, dead letters and operator recovery

- Outbox/notification delivery: exponential delay 2,4,8... capped at 300s, max eight failed attempts, durable FAILED state. Queues are not auto-deleted. A 60s expired lease can be reclaimed after crash. Ordering across different operations is not guaranteed; app must fetch authoritative balance/receipt.
- Kafka consumer: initial attempt + 3 retries at 1s for transient errors. Invalid payload/key/schema goes directly to DLT. Publishing to DLT must be acknowledged; DLT publish failure does not silently advance the record. The DLT listener retries DB archive failures indefinitely and never routes into another DLT.
- `dead_letter_event` records the DLT topic/partition/offset (not the original source offset). Original Kafka DLT headers remain on the broker. Retention is Kafka's configured retention, not infinite archival. The DB archive is the durable inspection record once it commits.
- Operator recovery SQL lives in `docs/milestone-4-recovery.sql`. Resolve root cause, then reset ONE known FAILED outbox/notification row. Replayed events use their original identity. No public admin replay endpoint is exposed. A DLT payload is untrusted; replay the authoritative outbox event, never edit financial history or blindly republish arbitrary JSON.
- If a transient valid event reached DLT, reset its outbox to PENDING using the targeted recovery query. If inbox already consumed it, no duplicate notice is created. If only provider delivery failed, reset that specific notification, not the outbox. Mark archive RESOLVED only after checking recovery.

## Reading order

OutboxWriter -> OutboxEvent -> OutboxTransactions -> OutboxRelay -> KafkaPaymentPublisher -> MessagingConfiguration -> PaymentMessageListener -> NotificationInbox -> NotificationService/Controller -> delivery transactions/worker/webhook -> rate filter and Redis Lua. One class at a time after manual testing.

## Explicit follow-up backlog

Agreed: longer sync window (duration not yet chosen), signed deadline in a compatible next protocol, sender OR receiver proof submission, mobile durable proof storage, primary automatic sync/retry, 12-hour local reminders and opposite-device status refresh. The notification infrastructure is now ready, but no real phone/app SMS delivery or sender-side settlement is claimed here. No INR100 fine. Early release based on 'no server payment record' was rejected. Existing deadline-based release remains. Entity/repository + V5/V6 detailed teaching stays deferred.

Still later: OpenFeign with actual microservices, tracing/ELK/metrics/load measurement/Kubernetes, trusted device time/hardware, late-claim adjudication and recovery policy, automatic reserve release, phone exchange. No RPS or 10K concurrency promise. Local Kafka is a single-node development broker without TLS/ACL/high availability; application ports are bound to loopback for this lab.

## Official references

- https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- https://redis.io/docs/latest/commands/incr/
- https://resilience4j.readme.io/docs/getting-started-3
- https://resilience4j.readme.io/docs/circuitbreaker
