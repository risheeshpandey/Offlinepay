-- Single read-only PostgreSQL snapshot. No passwords, mobile numbers or JWTs.
SELECT json_build_object(
 'negativeWallets', (SELECT COUNT(*) FROM wallet WHERE available_paise<0 OR offline_reserved_paise<0),
 'unbalancedOperations', (SELECT COUNT(*) FROM (
   SELECT p.id FROM payment_operation p LEFT JOIN ledger_entry l ON l.operation_id=p.id
   GROUP BY p.id HAVING COUNT(l.id)<>2 OR
   COALESCE(SUM(CASE WHEN l.entry_type='CREDIT' THEN l.amount_paise ELSE -l.amount_paise END),0)<>0
 ) x),
 'duplicateKeys', (SELECT COUNT(*) FROM (
   SELECT actor_id,idempotency_key FROM payment_operation GROUP BY actor_id,idempotency_key HAVING COUNT(*)>1
 ) x),
 'walletLedgerMismatches', (SELECT COUNT(*) FROM wallet w LEFT JOIN (
   SELECT wallet_id,
     SUM(CASE WHEN account_kind='WALLET' THEN CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END ELSE 0 END) AS available,
     SUM(CASE WHEN account_kind='OFFLINE_RESERVED' THEN CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END ELSE 0 END) AS reserved
   FROM ledger_entry WHERE wallet_id IS NOT NULL GROUP BY wallet_id
 ) l ON l.wallet_id=w.id WHERE w.available_paise<>COALESCE(l.available,0) OR w.offline_reserved_paise<>COALESCE(l.reserved,0)),
 'walletPaise', (SELECT COALESCE(SUM(available_paise+offline_reserved_paise),0) FROM wallet),
 'issuedPaise', (SELECT COALESCE(SUM(amount_paise),0) FROM payment_operation WHERE kind='DEMO_TOPUP'),
 'topups', (SELECT COUNT(*) FROM payment_operation WHERE kind='DEMO_TOPUP'),
 'transfers', (SELECT COUNT(*) FROM payment_operation WHERE kind='TRANSFER'),
 'operations', (SELECT COUNT(*) FROM payment_operation),
 'outboxEvents', (SELECT COUNT(*) FROM outbox_event),
 'unpublished', (SELECT COUNT(*) FROM outbox_event WHERE status<>'PUBLISHED'),
 'failedEvents', (SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'),
 'expectedNotices', (SELECT COALESCE(SUM(CASE WHEN sender_wallet_id IS NULL OR sender_wallet_id=receiver_wallet_id THEN 1 ELSE 2 END),0) FROM payment_operation)
);
