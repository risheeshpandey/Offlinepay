> Part 2 reference. Current settlement/release flow is in [milestone-3.md](milestone-3.md); /verify remains non-settling.

# Milestone 2 Part 2: manual learning lab

This release adds cookie refresh, registered device keys, server-signed allowance certificates and a local Java payment-proof client. It extends the same project. A verified proof is not settled money: no receiver credit, global replay/double-spend detection, trusted device counter, automatic expiry release or bank integration is implemented. Those boundaries matter even when every signature is valid.

## 1. Upgrade without resetting your database

Stop the app, keep PostgreSQL running. Merge the new source into the existing `offlinepay` folder. Preserve `.env`, `.idea`, existing keys (if any), and the PostgreSQL volume. Reload Maven. Java target stays 21; `ddl-auto` stays `validate`. V1-V4 are unchanged; Flyway applies only V5 to an existing V4 database. Do not rerun setup or use `docker compose down -v`.

One new startup prerequisite: stable server signing keys. In IntelliJ open `client/OfflineClient.java`, run its `main` with program arguments:

```text
keygen local-keys
```

Set the run configuration working directory to the project root (`$PROJECT_DIR$`). This main does NOT start Spring or connect to a database. It writes `local-keys/private-key.txt`, `public-key.txt`, and `trusted-server-key.json`. It refuses to overwrite existing keys. Keep the private key local and backed up securely; never put it in GitHub or Postman. Test-resource keys are public test fixtures only and are not application keys.

Now start `OfflinePayApplication` with the existing DB/JWT variables and `SPRING_PROFILES_ACTIVE=demo`. Default signing paths point to `local-keys`. Outside the demo profile cookies default to Secure and require HTTPS. Existing IntelliJ VM option `-Duser.timezone=Asia/Kolkata` may remain. Compose mounts `./local-keys` read-only; its non-root user must be able to read the files. For Linux, use a deliberate read-permitted owner/group for the container or an appropriate secrets mount; do not make the private key world-readable. Postgres and API ports remain 5436 and 9030.

Old reservations remain reserved and retain their history. They have no key-bound certificate. GET certificate for such a row returns `409 LEGACY_ALLOWANCE_UNSIGNED`; it does not silently attach today's key to old funds, re-debit or release them. Use a NEW reservation/key for the Part 2 lab, with available funds. Safe release remains Milestone 3.

## 2. CSRF before signup/login/refresh/logout

Postman: create this request, Authorization `No Auth`:

```text
GET http://localhost:9030/api/v1/auth/csrf
```

Postman stores the `XSRF-TOKEN` cookie. Body contains `headerName` (`X-XSRF-TOKEN`) and a masked `token`. Copy the body token into a Postman variable `csrfToken`. On every auth POST add:

```text
X-XSRF-TOKEN: {{csrfToken}}
```

Keep the same Postman cookie jar and host (`localhost`, not sometimes `127.0.0.1`). The header token and cookie must belong together. A missing/wrong pair returns 403. This CSRF cookie is separate from the refresh cookie. Neither cookie authenticates wallet APIs; those still require Bearer access tokens. No cross-origin CORS access is enabled; a future browser frontend should use same-origin routing or an explicitly allowed origin with credentials.

Login with your existing JSON mobile/password and `No Auth`. Expected 200 body:

```json
{"accessToken":"...","tokenType":"Bearer","expiresIn":1800}
```

RT is absent from JSON. In response headers/cookie manager inspect `offlinepay_rt`: HttpOnly, SameSite=Strict, Path=/api/v1/auth. Demo HTTP sets Secure=false; production/default sets Secure=true. DevTools/Postman can inspect a cookie even when JavaScript cannot read it. Responses carrying tokens have Cache-Control=no-store.

Refresh: `POST /api/v1/auth/refresh`, No Auth, X-XSRF-TOKEN header, Body **none**. Cookie is sent automatically. Body returns only replacement AT and metadata; Set-Cookie rotates RT. Serialize refresh requests: replaying a consumed RT revokes the whole refresh session. To test replay, deliberately restore the previous RT cookie, send once (401 REFRESH_TOKEN_REUSED), then restore the saved descendant (401 INVALID_REFRESH_TOKEN). Errors clear the RT cookie. Never share token values.

Logout: `POST /api/v1/auth/logout`, No Auth, same CSRF header, Body none -> 204, RT cookie cleared and refresh session revoked. Existing access JWT remains valid until expiry; other login sessions are independent. Re-fetch CSRF if your cookie jar is cleared. Postman collection has a GET-CSRF request which saves the masked header token; it does not put RT into variables.

## 3. Register a device manually

Get your existing user ID in DBeaver (not the wallet ID):

```sql
SELECT id, name, mobile_number FROM app_user
WHERE mobile_number = '9000000101';
```

Run `OfflineClient.main` twice, changing program arguments each time:

```text
keygen local-device-rp
register local-device-rp YOUR_USER_UUID DEVICE-RP-002
```

Open `local-device-rp/registration.json`. It contains deviceId, publicKey and signature. Send that JSON with your access token:

```text
POST http://localhost:9030/api/v1/devices
Authorization: Bearer YOUR_AT
```

Expected 200. Registration signature proves possession of the key and binds the signed registration to userId + deviceId + publicKey. An identical retry is safe. Existing IDs cannot silently change keys; revoked IDs stay revoked. A key cannot be registered to two devices/users. This proves key possession, not hardware attestation or an honest device owner. `POST /api/v1/devices/{deviceId}/revoke` prevents new reservations and certificate retrieval; it cannot reach an already-offline receiver or release funds.

## 4. Reserve and download a certificate

If your Part 1 balance is still available INR 450, reserved INR 300, reserve INR 100 using this registered device and a new idempotency key:

```text
POST /api/v1/offline-allowances
Authorization: Bearer YOUR_AT
Idempotency-Key: part2-reserve-001
```

```json
{"amount":100,"deviceId":"DEVICE-RP-002"}
```

Expected available INR 350, reserved INR 400, total INR 750. If balances differ, calculate from your current balance. Reusing a successful key returns its original allowance; choose a new key for a new allocation. New allocations require an active registered device.

Create `local-demo` folder. Save the following GET responses as files using Postman Save Response (or paste exact JSON into IntelliJ files):

```text
GET /api/v1/offline-allowances/YOUR_ALLOWANCE_UUID/certificate
-> local-demo/certificate.json
GET /api/v1/offline-protocol/server-key
-> local-demo/trusted-server-key.json
```

Both GETs use Bearer AT. Fetch/pin the trusted server key over an authenticated trusted channel before going offline. Never trust a sender-supplied replacement server key. Issuer, key ID, allowance/wallet/device IDs, device public key, allocated paise, currency and validity are covered by the server signature. The certificate snapshots the device key at reservation time. It remains byte-for-byte stable on retries; keep the server key stable across restarts. Key rotation/old-key distribution is not automated yet.

## 5. Create and verify a payment proof without a running backend

Use Bob's existing wallet UUID, not user UUID. For a INR 100 allocation, create a INR 40 payment (4000 paise):

```text
pay local-device-rp local-demo/certificate.json local-demo/trusted-server-key.json BOB_WALLET_UUID 4000 local-demo/payment1.json
```

You can stop only the backend during this step to confirm there is no network call. The client checks the pinned server signature, allowance validity, device key, recipient, amount and local remaining budget. It signs the payment with the device private key. UUID payment ID + increasing local sequence identify the intent; the signed amount is integer paise. Repeated payments use a new output filename. The local state file and file lock prevent accidental overspending by this client, but a malicious owner can clone/rollback files; this is NOT a secure hardware wallet.

Receiver-side local verification:

```text
verify local-demo/trusted-server-key.json local-demo/payment1.json BOB_WALLET_UUID
```

Expected `VERIFIED_NOT_SETTLED`. Copying/editing the amount, receiver or signature must fail verification. Expired allowances fail new acceptance. The client refuses cumulative payments above the locally stored allowance, but independent receivers cannot detect all conflicting spends while offline. The verifier is intentionally stateless: verifying the same proof twice does not credit twice, because it never credits at all. Milestone 3 owns durable deduplication and settlement.

Optional API demo, after restarting backend and logging in as Bob:

```text
POST /api/v1/offline-protocol/verify
Authorization: Bearer BOB_AT
Body: complete payment1.json
```

Expected 200 VERIFIED_NOT_SETTLED. API derives expected recipient from Bob's JWT; Alice cannot verify it as her own incoming payment. Inspect balances: creating/verifying a proof has not changed them. No backend endpoint accepts device private keys or signs a payment on the user's behalf.

## 6. IST timestamps and canonical signatures

Human-readable API Instant fields (createdAt, expiresAt, error timestamp) serialize as ISO-8601 with +05:30. Logs explicitly format Asia/Kolkata. Database Instant/TIMESTAMPTZ handling stays UTC/absolute time; JWT expiry uses epoch seconds. Signed claims use epoch milliseconds so all clients sign identical bytes regardless of display timezone. Example: `2026-10-15T21:00:00+05:30` and `2026-10-15T15:30:00Z` denote the same instant. DBeaver display depends on its own session timezone, not API JSON formatting.

The canonical payload contract is `protocol/OfflineProtocol.java`: fixed order, newline-delimited UTF-8, constrained IDs, decimal integers, no trailing newline. X.509 public keys and PKCS#8 private keys use standard Base64. Payment signatures bind a SHA-256 hash of the complete allowance payload + server signature as well as payment fields. JSON property ordering is not signed directly. Ed25519 is signing, not encryption; proof fields are visible.

## Code reading order

1. AuthCookies + AuthController + SecurityConfig: cookie transport and CSRF; RefreshTokenService keeps the tested rotation/transaction logic.
2. TimeConfig: Instant serializer; application.yml: log date format.
3. DeviceController/Service/Entity: authenticated registration, possession proof and immutable key binding.
4. Ed25519: JDK keys/signature API; OfflineProtocol: exact signed bytes.
5. OfflineAllowanceService.reserve/certificate: snapshot + persisted server signature; V5 migration.
6. OfflineProofVerifier: trusted issuer/key, signature, recipient, amount and time checks.
7. OfflineClient: local keygen/register/pay/verify and cumulative demo state.

Primary references: Spring Security 6.5 CSRF documentation (https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html), Spring ResponseCookie API, RFC 8032 (https://www.rfc-editor.org/rfc/rfc8032.html). No throughput or real-money safety claims are made.
