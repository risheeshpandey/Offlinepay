> Historical Part 1 notes. For current cookie, device-registration and signed-proof behavior, use [Part 2](milestone-2-part-2.md).

# Milestone 2, Part 1 - Reservation, logs and refresh tokens

Same OfflinePay project continue ho raha hai. Is part mein offline money reserve hoti hai, lekin signed allowance aur actual disconnected exchange abhi implement nahi hain.

## Upgrade existing project

1. IntelliJ application stop karo. PostgreSQL running rehne do.
2. Updated bundle se `pom.xml`, `src`, `docs`, `scripts`, `postman`, `Dockerfile` aur `compose.yaml` copy karo. Existing `.env`, `.idea` and database volume keep karo.
3. Maven reload karo. Existing environment variables and `-Duser.timezone=Asia/Kolkata` keep karo.
4. Start application. Flyway applies **V3 and V4**; V1/V2 unchanged hain. Hibernate `ddl-auto=validate` new mapping check karta hai.
5. Existing passwords, wallet IDs, balances and payments preserved rehne chahiye. Old wallets ka new reserved balance starts at zero.

Docker app use kar rahe ho toh `docker compose up --build -d app` rebuild karega. IntelliJ use kar rahe ho toh `docker compose stop app` karke only PostgreSQL Docker mein rakho. Both backends ko port 9030 par simultaneously run nahi karna.

## 1. Offline balance reservation

Imagine available INR 750. Reserve INR 300:

| Bucket | Before | After |
| --- | ---: | ---: |
| Available | 750 | 450 |
| Offline reserved | 0 | 300 |
| Total | 750 | 750 |

Money create nahi hui; same wallet ke available account se reserved account mein move hui.

Postman:

```http
POST http://localhost:9030/api/v1/offline-allowances
Authorization: Bearer <sender-access-token>
Idempotency-Key: manual-reserve-001
Content-Type: application/json
```

```json
{
  "amount": 300,
  "deviceId": "DEVICE-RP-001"
}
```

HTTP 200 response includes `allowanceId`, `transactionId`, `walletId`, `deviceId`, `allocatedAmount`, `currency`, `status: RESERVED`, and `expiresAt`.

Repeat same body/key: same allowance return hogi; extra reserve nahi hogi. Same key with another device or amount: 409. Online transfer INR 500 from the remaining INR 450: 422. Reservation, topup and transfer share the user's durable idempotency-key scope.

Read endpoints:

```http
GET /api/v1/offline-allowances/{allowanceId}
GET /api/v1/offline-allowances?page=0&size=20
GET /api/v1/wallets/me
```

Balance response preserves original fields and adds `offlineReservedBalance` and `totalBalance`. Another user cannot fetch your allowance by ID; 404 return hota hai. List returns only your records.

### Code reading order

- `offline/OfflineAllowanceController`: JWT se user ID, DTO validation, idempotency header.
- `offline/OfflineAllowanceService.reserve`: wallet ID read, row lock, durable replay check, reserve, operation, allowance and journal save.
- `wallet/WalletEntity.reserveOffline`: `debit` available bucket, credit reserved bucket.
- `ledger/LedgerService.recordOfflineReservation`: WALLET debit + OFFLINE_RESERVED credit.
- `V3__offline_reservations.sql`: existing tables extended, old kinds retained, new allowance table.

Both journal rows refer to the same wallet and payment but different `accountKind`. `Entry` API now includes `accountKind` so these buckets can be distinguished. Wallet available balance reconciles only with `WALLET` entries; reserved balance reconciles with `OFFLINE_RESERVED` entries.

### Important protocol boundary

`deviceId` abhi client-supplied identifier hai, cryptographically verified device identity nahi. This row is **not a signed spending certificate**, and no offline transfer/sync endpoint exists yet. Default allowance expiry is 24 hours, but expiry **does not automatically release funds**. Release/grace-window rules will be defined with device signing and reconciliation before offline spending is enabled.

No unrestricted release endpoint is exposed: once signed certificates exist, premature release could let the same funds be spent online and offline.

## 2. Access and refresh tokens

Login returns the existing access-token fields plus `refreshToken` and `refreshExpiresIn`.

| Token | Default lifetime | Usage |
| --- | --- | --- |
| Access JWT | 30 minutes | Bearer header on protected wallet/payment APIs |
| Opaque refresh token | Login session expires after 7 days | Body of refresh/logout requests |

Refresh token SecureRandom se generate hota hai; database stores **only its SHA-256 hash**. JWT and refresh tokens are different credentials.

```http
POST http://localhost:9030/api/v1/auth/refresh
Content-Type: application/json
```

```json
{
  "refreshToken": "<refresh-token-from-login>"
}
```

Postman **No Auth** select karo. Expired access JWT attach karne ki zarurat nahi. On success, save **both replacement tokens** and use the new access token for protected requests. Session expiry remains anchored to the original login; rotation does not extend it indefinitely.

Old refresh token reuse: `401 REFRESH_TOKEN_REUSED`, and its **whole refresh session** gets revoked. Its newer descendant token is then invalid too. Client must serialize refresh calls. Concurrent refresh calls or retrying an old token after losing the response can trigger this protection; login again is the recovery path.

### Why the session row lock?

`RefreshTokenService.rotate` first reads only session ID, locks that session, then reads token state. This serializes all rotations/replays in the family and avoids stale first-level cached state. Locking only one old token is insufficient when another descendant is rotating concurrently.

`@Transactional(noRollbackFor = RefreshTokenReplayException.class)` applies only to the replay exception. Revocation must commit even though the API returns 401. Ordinary failures still roll back. `AuthService.refresh` adds no outer transaction that could undo this revocation.

Logout:

```http
POST /api/v1/auth/logout
```

```json
{
  "refreshToken": "<current-refresh-token>"
}
```

HTTP 204 revokes that login's refresh session. Other independently created login sessions are unaffected. Already-issued access JWTs remain valid until expiry; immediate access-token revocation/blacklisting is not implemented. Production deployment will require HTTPS and secure client token storage. No token values should be logged or shared in a filled Postman export.

## 3. Debugging logs

Application writes console logs and a rolling file, default `logs/offlinepay.log` relative to the process working directory. IntelliJ Working directory should be the existing `offlinepay` folder. Rotation: 10 MB per file, 7 days history, 100 MB total cap.

For IntelliJ on Windows:

```powershell
Get-Content .\logs\offlinepay.log -Tail 50 -Wait
```

For Docker:

```powershell
docker compose logs -f app
```

Docker log files live in a separate `offlinepay-logs` volume. Dockerfile makes `/app/logs` writable by the non-root application user.

Every HTTP request gets `X-Request-ID` in its response. A valid 8-64 character alphanumeric/hyphen incoming ID can be reused; unsafe IDs are replaced. The ID appears in MDC logs and is cleared after the request so thread reuse cannot carry it to another request.

Example log fields:

```text
[requestId=manual-request-001] http_request method=POST path=/api/v1/offline-allowances status=200 durationMs=...
[requestId=manual-request-001] operation_committed transactionId=... kind=OFFLINE_RESERVE amountPaise=30000
```

`@TransactionalEventListener(AFTER_COMMIT)` emits the payment success log only after commit. This is an in-process log event, **not a durable Kafka event/outbox**. A process crash can still lose the log; the database journal remains authoritative. Replays have a separate `payment_replay`/`reservation_replay` message.

Our filter does not log bodies, query strings or authentication headers. Passwords, access tokens and raw refresh tokens are excluded. Application-package DEBUG can be enabled locally if needed; broad SQL bind/payload logging should not be enabled with live credentials.

## 4. Postman collection

Import `postman/OfflinePay.postman_collection.json`. Set collection variables for your sender's mobile/password and receiverWalletId. Login stores access/refresh values locally. Refresh replaces both values automatically. Existing accounts can skip signup.

Stable `topupKey`, `transferKey` and `reserveKey` let you test replay with repeated Send clicks. Use a new key only for a new intended operation. The exported source collection contains no live tokens.

## 5. Database reconciliation

```sql
SELECT u.name,
       w.available_paise / 100.0 AS available_inr,
       w.offline_reserved_paise / 100.0 AS reserved_inr,
       (CAST(w.available_paise AS NUMERIC) + w.offline_reserved_paise) / 100.0 AS total_inr
FROM wallet w JOIN app_user u ON u.id = w.user_id;

SELECT wallet_id, account_kind,
       SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_paise ELSE -amount_paise END) / 100.0 AS journal_inr
FROM ledger_entry WHERE wallet_id IS NOT NULL
GROUP BY wallet_id, account_kind;
```

Compare each wallet's available balance with its WALLET sum, and reserved balance with OFFLINE_RESERVED sum. Both journal postings for one reserve sum to zero.

## 6. Remaining requirements

| Requirement | Placement |
| --- | --- |
| Simple Java, JPA and Lombok annotations | Every milestone; current code follows this style |
| Natural Hinglish explanations and PDF | Updated with each new code path |
| Device keys + server-signed allowances | Next part of milestone 2 |
| Actual disconnected QR/BLE/NFC exchange | Protocol/client work after signed allowance verification |
| Sync, double-spend detection, expiry grace, safe release | Milestone 3 |
| Kafka/outbox, Redis distributed limiter, circuit breaker, bulkhead, timeout, safe retries | Milestone 4 |
| OpenFeign for separate services | Milestone 5; named clients, explicit timeouts, request-ID propagation, service authentication |
| Tracing/Zipkin, ELK, Kubernetes, k6 and measured capacity | Milestone 6 |
| Android/Room/Keystore/background sync | Milestone 7 |
| GitHub publication and CI runs | Deferred by user; local Git history/workflow remain ready |

OpenFeign is not installed just for an internal same-JVM service call. We will add it at actual service boundaries, alongside safe handling of timeouts and idempotent payment commands. Logs now provide the foundation for later ELK/tracing.

## Verification

Run `mvn test` with Java 21 for fast H2 tests. Run `mvn verify` with Docker for real PostgreSQL suites, including V1/V2-to-V3/V4 upgrade preservation. See `verification.md` for the exact local evidence and pending checks. Request capacity remains unmeasured.
