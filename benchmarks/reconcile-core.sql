-- Read-only checks on an ISOLATED benchmark core database, after traffic stops.
-- All problem counts must be zero; wallet sum must equal demo issuance.
SELECT COUNT(*) AS negative_wallets FROM wallet WHERE available_paise < 0 OR offline_reserved_paise < 0;
SELECT COUNT(*) AS unbalanced_operations FROM (
  SELECT operation_id FROM ledger_entry GROUP BY operation_id
  HAVING COUNT(*) <> 2 OR SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) <> 0
) problems;
SELECT COUNT(*) AS duplicate_keys FROM (
  SELECT actor_id,idempotency_key FROM payment_operation GROUP BY actor_id,idempotency_key HAVING COUNT(*) > 1
) problems;
SELECT (SELECT COALESCE(SUM(available_paise + offline_reserved_paise),0) FROM wallet) AS wallet_total_paise,
       (SELECT COALESCE(SUM(amount_paise),0) FROM payment_operation WHERE kind='DEMO_TOPUP') AS issued_paise;
SELECT kind, COUNT(*) AS committed_operations FROM payment_operation GROUP BY kind;
SELECT status, COUNT(*) AS events FROM outbox_event GROUP BY status;
-- Once drained, expected notices = sum of DISTINCT participants per operation.
SELECT SUM(CASE WHEN sender_wallet_id IS NULL OR sender_wallet_id=receiver_wallet_id THEN 1 ELSE 2 END) AS expected_notices
FROM payment_operation;
