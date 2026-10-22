ALTER TABLE wallet ADD COLUMN offline_reserved_paise BIGINT NOT NULL DEFAULT 0
    CHECK (offline_reserved_paise >= 0);
ALTER TABLE wallet ADD CONSTRAINT wallet_total_capacity_check
    CHECK (CAST(available_paise AS NUMERIC) + CAST(offline_reserved_paise AS NUMERIC) <= 9223372036854775807);

-- V1 ke auto-generated names par depend nahi karte: replace only kind checks.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'payment_operation'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%kind%'
    LOOP EXECUTE format('ALTER TABLE payment_operation DROP CONSTRAINT %I', rule.conname); END LOOP;
END $$;
ALTER TABLE payment_operation ADD CONSTRAINT payment_operation_kind_check
    CHECK (kind IN ('TRANSFER', 'DEMO_TOPUP', 'OFFLINE_RESERVE'));
ALTER TABLE payment_operation ADD CONSTRAINT payment_operation_check CHECK (
    (kind = 'TRANSFER' AND sender_wallet_id IS NOT NULL AND sender_wallet_id <> receiver_wallet_id)
    OR (kind = 'DEMO_TOPUP' AND sender_wallet_id IS NULL)
    OR (kind = 'OFFLINE_RESERVE' AND sender_wallet_id IS NOT NULL AND sender_wallet_id = receiver_wallet_id));

DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'ledger_entry'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%account_kind%'
    LOOP EXECUTE format('ALTER TABLE ledger_entry DROP CONSTRAINT %I', rule.conname); END LOOP;
END $$;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_account_kind_check
    CHECK (account_kind IN ('WALLET', 'DEMO_ISSUANCE', 'OFFLINE_RESERVED'));
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_check CHECK (
    (account_kind IN ('WALLET', 'OFFLINE_RESERVED') AND wallet_id IS NOT NULL)
    OR (account_kind = 'DEMO_ISSUANCE' AND wallet_id IS NULL));

CREATE TABLE offline_allowance (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    wallet_id UUID NOT NULL REFERENCES wallet(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation(id),
    device_id VARCHAR(80) NOT NULL,
    allocated_paise BIGINT NOT NULL CHECK (allocated_paise > 0),
    status VARCHAR(20) NOT NULL CHECK (status = 'RESERVED'),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX allowance_user_history_idx ON offline_allowance(user_id, created_at, id);
-- V2's deferred balanced-operation trigger also applies to OFFLINE_RESERVE.
-- Expiry does not release funds automatically. Reconciliation/release comes later.
