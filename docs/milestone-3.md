# Milestone 3 - offline sync, settlement and safe release

The same project now settles signed offline payment proofs. A receiver submits one proof over an authenticated HTTP connection. A successful first settlement consumes the sender's reserved bucket, credits the receiver's available bucket, writes two balanced ledger entries and stores the immutable receipt/proof in one transaction. The earlier `/offline-protocol/verify` API and local `verify` command remain non-settling checks.

V5 code walkthrough remains deferred at your request. You do not need to edit V5: this upgrade applies a new V6 migration and preserves V1-V5 byte-for-byte.

## 1. Rules to learn first

| Rule | Behavior |
| --- | --- |
| Who syncs? | Only the payment's receiver, authenticated by Bearer AT. User/receiver identity is derived from JWT + own wallet. |
| Retry identity | The signed `paymentId`; no extra Idempotency-Key header. Retry the exact existing proof. |
| Same ID, same proof | Original transaction/result; no second debit, credit or journal pair. |
| Same ID, different valid proof | 409 PAYMENT_ID_CONFLICT; concurrent cross-allowance UUID collisions also have a database uniqueness backstop. |
| Same allowance + sequence, different ID | 409 SEQUENCE_CONFLICT. |
| Out-of-order sequence | Allowed: sequence 2 can arrive before sequence 1. Uniqueness and aggregate budget still apply. |
| Multiple valid claims exceed allocation | First successfully committed claims consume the budget; later excess claims get 409 ALLOWANCE_EXHAUSTED. No ordering based on untrusted device clock. |
| Time | Signed payment time must be within allowance validity and not in the future relative to server now. New settlement may arrive after expiry but must arrive before stored syncDeadline. |
| Release | Explicit owner-only request, only when server time >= syncDeadline. Release only allocated - settled - already released. |
| Already committed retry | Returns original result even after deadline, release or later device revocation; it moves no money. |
| Revocation | Currently revoked device blocks all NEW settlements, even if the proof claims an earlier time. Existing settled results remain readable. This conservative policy can reject a legitimate delayed receiver claim. |

The default new-payment window is 24 hours. Submission grace defaults to another 48 hours. Deadline is stored per allowance; later config edits do not change existing rows. V6 gives existing signed/unsigned reservations deadline = expires_at + 48 hours, with zero settled/released counters. The existing signed V1 certificate bytes/signatures are unchanged. V1 certificates do not sign the sync deadline, so receivers must know the agreed server submission policy separately; the database deadline is authoritative.

No software-only signature proves an honest device clock, an uncloneable counter, or that a proof was really created before expiry. A malicious key holder may backdate during the submission window. Offline acceptance still carries receiver risk. This portfolio simulates virtual money; it does not claim unconditional offline double-spend prevention. Durable accepted-payment deduplication and bounded reserve consumption happen online at settlement.

## 2. Upgrade manually

1. Stop only the application. Keep PostgreSQL and its volume.
2. Extract the full ZIP into a temporary folder. Merge `src`, `docs`, `scripts`, `postman`, `pom.xml`, Dockerfile/compose and ignore files into your existing `offlinepay` directory; do not nest another offlinepay directory.
3. Preserve `.env`, `.idea`, `local-keys`, every `local-device-*` directory, `local-demo`, and client state/proof files. Do NOT regenerate server/device keys or rerun setup. Keys must match existing certificates.
4. Reload Maven. Keep Java 21, demo profile and the same database variables. Both OfflinePayApplication and OfflineClient working directories must be `C:\Users\rishe\OfflinePay_Milestone_1\offlinepay`.
5. Start app; Flyway applies V6. No reset or ddl-auto change. Then check GET `/actuator/health`.

An unsigned Part 1 reservation cannot become a payment certificate retrospectively; it can be released by its owner after its stored deadline. Already signed Part 2 proofs remain compatible. If their deadline has passed, create a fresh allowance/proofs after releasing old unused funds as appropriate. Never edit signed timestamps, amounts, IDs or signatures to reuse an expired proof.

## 3. Bob syncs payment1.json

Keep your existing original proof files unchanged. If they are still within the stored deadline, the previous lab used:

```text
Sender wallet: d0ce339d-9d74-4655-9b20-8a02789ef99d
Bob wallet:    8ab61880-d1f0-4919-a083-934789401db1
Allowance:     a5053a1e-2390-4df2-a916-ccca399fa597
Payment 1:     INR 40
Payment 2:     INR 60
```

Confirm Bob's actual mobile number in DBeaver instead of guessing:

```sql
SELECT u.id, u.name, u.mobile_number, w.id AS wallet_id
FROM app_user u JOIN wallet w ON w.user_id = u.id
WHERE w.id = '8ab61880-d1f0-4919-a083-934789401db1';
```

Login as Bob with his existing password (earlier example: DemoPassword123!). First GET `/api/v1/auth/csrf`, No Auth, Body none. Keep its cookie jar and copy body token into `X-XSRF-TOKEN` for POST `/api/v1/auth/login`. Login JSON contains Bob's mobileNumber/password. Save Bob's AT separately from sender AT. Refresh/logout keep the cookie flow implemented in Part 2.

Now Postman:

```text
POST http://localhost:9030/api/v1/offline-sync/payments
Authorization: Bearer BOB_AT
Content-Type: application/json
Body: complete original local-demo/payment1.json
```

No separate Idempotency-Key and no CSRF header are required for this Bearer-only API. Do not paste a file path as body. Paste the complete certificate + payment + device signature JSON. `200 OK` returns paymentId, transactionId, allowanceId, senderWalletId, receiverWalletId, amount, currency, sequence, status SETTLED, settledAt in IST. Server stores the verified proof snapshot, no credentials/private keys.

If your prior balances were sender 350 available + 400 reserved = 750 total and Bob 250 available, expected changes are:

| Stage | Sender available | Sender reserved | Sender total | Bob available |
| --- | ---: | ---: | ---: | ---: |
| Before sync | 350 | 400 | 750 | 250 |
| After payment1 (40) | 350 | 360 | 710 | 290 |
| Repeat same payment1 | 350 | 360 | 710 | 290 |
| After payment2 (60) | 350 | 300 | 650 | 350 |

The remaining INR 300 is the separate Part 1 allowance; it is not consumed by these proofs. Query current balances first if you have other transactions. System-wide wallet totals remain unchanged by settlement. Expired AT gets 401; obtain a fresh AT without regenerating payment proofs.

## 4. Retry, read and inspect

- Send the exact payment1 body again: same transactionId/settledAt, unchanged balances. This remains safe after app restart.
- Submit payment2 body as Bob: another settlement, same allowance, sequence 2. The allowance becomes SETTLED with settledAmount 100 and remainingAmount 0.
- Either sender or receiver may GET `/api/v1/offline-sync/payments/{paymentId}` with their AT; unrelated user gets 404.
- Owner GET `/api/v1/offline-allowances/{allowanceId}` now also shows syncDeadline, settledAmount, releasedAmount, remainingAmount and status.
- GET wallet ledger for sender: OFFLINE_RESERVED DEBIT for each settlement. Bob: WALLET CREDIT. The financial operation kind is OFFLINE_SETTLE.
- Sending Bob's proof with sender AT -> 400 INVALID_OFFLINE_PROOF (recipient mismatch); malformed/tampered signatures also 400.
- Do not merely edit paymentId or sequence to generate a valid conflict: those fields are signed. Automated tests construct independently signed conflicting proofs to check the server rules.

## 5. Grace period and release

New offline acceptance (`OfflineClient pay/verify`, `/offline-protocol/verify`, certificate retrieval) still requires the allowance to be currently unexpired. Settlement uses a separate verifier mode: signature + signed payment time remain required, but an existing proof can sync during grace. After deadline, new claims are rejected even if release has not been called. A committed duplicate is returned before deadline/revocation checks, after identity/signature checks.

To release, login as the allowance owner and send:

```text
POST /api/v1/offline-sync/allowances/{allowanceId}/release
Authorization: Bearer SENDER_AT
Body: none
```

Before deadline -> 409 SYNC_WINDOW_OPEN, including immediately after allowance expiry. At/after deadline -> 200 with releasedAmount and transactionId. Repeating returns the original release; no second movement. Fully settled allowance has amount 0, status SETTLED and no release transaction. No timer/job runs automatically. Wallet available increases by unused funds, reserved decreases by same amount, total unchanged. The ledger has OFFLINE_RESERVED DEBIT / WALLET CREDIT under OFFLINE_RELEASE.

Default 24h+48h is intentionally not shortened in normal code. To learn boundaries quickly using NEW demo allowances only, set OfflinePayApplication environment variables `OFFLINE_ALLOWANCE_LIFETIME=PT2M` and `OFFLINE_SYNC_GRACE=PT1M`, restart, reserve a fresh small amount, create the proof before expiry and test sync/release around its stored deadline. Never change old certificate/database times. Restore normal defaults afterward; old allowances retain their stored deadline. Agreements about submission windows are a server policy, not a signed field of V1 certificates.

## 6. Database checks

```sql
SELECT id, device_id, allocated_paise, settled_paise, released_paise,
       allocated_paise - settled_paise - released_paise AS remaining_paise,
       status, expires_at, sync_deadline, released_at
FROM offline_allowance ORDER BY created_at DESC;

SELECT payment_id, allowance_id, sequence_number, operation_id, receiver_wallet_id, settled_at
FROM offline_settlement ORDER BY settled_at DESC;

-- Each operation still has two equal opposing entries.
SELECT operation_id, COUNT(*) AS entries,
       SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) AS net_paise
FROM ledger_entry GROUP BY operation_id;

-- All allowance remainders must agree with the wallet reserve projection.
SELECT w.id, w.offline_reserved_paise,
       COALESCE(SUM(a.allocated_paise-a.settled_paise-a.released_paise),0) AS allowance_reserved_paise
FROM wallet w LEFT JOIN offline_allowance a ON a.wallet_id=w.id
GROUP BY w.id,w.offline_reserved_paise;
```

Test fixtures deliberately corrupt DB values in isolated rollback tests only. Do not run direct UPDATE/DELETE against live demo financial history to simulate settlement.

## 7. Reading order and limitations

1. OfflineSyncController: receiver Bearer identity, routes and collision error translation after rollback.
2. OfflineProofVerifier.verifyForSettlement: signatures and signed time; current-expiry acceptance stays separate.
3. OfflineSettlementService.sync: sorted wallet locks -> allowance lock -> stored certificate match -> durable payment retry -> deadline/device/sequence/budget checks -> money/ledger/receipt.
4. WalletEntity.consumeOffline; LedgerService.recordOfflineSettlement: reserved debit and receiver available credit.
5. OfflineAllowanceEntity.settle/release; service.release: allowance counters and explicit deadline-gated release.
6. OfflineSettlementRepository + V6: paymentId PK, unique allowance sequence, append-only accepted receipt; V5 walkthrough remains deferred.

A caller-provided paymentId uses EntityManager.persist (INSERT), never JpaRepository.save/merge, so a cross-allowance UUID collision cannot silently merge into an existing settlement while moving money. All failures roll back before the controller sends an error. Internal financial idempotency keys include ':' which public key validation rejects, preventing clients from occupying the system namespace.

Accepted proofs/receipts are stored durably. Rejected attempts are logged with code/request ID, not kept in a durable dispute table. No chargeback/reversal/adjudication workflow, automated release scheduler, trust-key rotation, receiver offline delivery UI or production risk guarantee is included. Those are explicit follow-up scope, with Kafka/outbox and resilience still in Milestone 4. Pending proofs must not be re-spent as settled balance.
