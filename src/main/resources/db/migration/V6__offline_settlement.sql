-- V1-V5 unchanged. Existing certificates keep the same signed fields and signature.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'payment_operation'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%kind%'
    LOOP EXECUTE format('ALTER TABLE payment_operation DROP CONSTRAINT %I', rule.conname); END LOOP;
END $$;
ALTER TABLE payment_operation ADD CONSTRAINT payment_operation_kind_check
    CHECK (kind IN ('TRANSFER', 'DEMO_TOPUP', 'OFFLINE_RESERVE', 'OFFLINE_SETTLE', 'OFFLINE_RELEASE'));
ALTER TABLE payment_operation ADD CONSTRAINT payment_operation_check CHECK (
    (kind IN ('TRANSFER', 'OFFLINE_SETTLE') AND sender_wallet_id IS NOT NULL AND sender_wallet_id <> receiver_wallet_id)
    OR (kind = 'DEMO_TOPUP' AND sender_wallet_id IS NULL)
    OR (kind IN ('OFFLINE_RESERVE', 'OFFLINE_RELEASE') AND sender_wallet_id IS NOT NULL AND sender_wallet_id = receiver_wallet_id));
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'offline_allowance'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP EXECUTE format('ALTER TABLE offline_allowance DROP CONSTRAINT %I', rule.conname); END LOOP;
END $$;
ALTER TABLE offline_allowance ADD COLUMN settled_paise BIGINT NOT NULL DEFAULT 0;
ALTER TABLE offline_allowance ADD COLUMN released_paise BIGINT NOT NULL DEFAULT 0;
ALTER TABLE offline_allowance ADD COLUMN sync_deadline TIMESTAMP WITH TIME ZONE;
ALTER TABLE offline_allowance ADD COLUMN released_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE offline_allowance ADD COLUMN release_operation_id UUID UNIQUE REFERENCES payment_operation(id);
-- Legacy signed and unsigned allocations get the documented 48-hour submission grace.
UPDATE offline_allowance SET sync_deadline = expires_at + INTERVAL '48 hours';
ALTER TABLE offline_allowance ALTER COLUMN sync_deadline SET NOT NULL;
ALTER TABLE offline_allowance ADD CONSTRAINT allowance_sync_window CHECK (sync_deadline > expires_at);
ALTER TABLE offline_allowance ADD CONSTRAINT allowance_consumption CHECK (
    settled_paise >= 0 AND released_paise >= 0
    AND CAST(settled_paise AS NUMERIC) + CAST(released_paise AS NUMERIC) <= allocated_paise);
ALTER TABLE offline_allowance ADD CONSTRAINT allowance_status_check CHECK (
    (status = 'RESERVED' AND settled_paise = 0 AND released_paise = 0)
    OR (status = 'PARTIALLY_SETTLED' AND settled_paise > 0 AND settled_paise < allocated_paise AND released_paise = 0)
    OR (status = 'SETTLED' AND settled_paise = allocated_paise AND released_paise = 0)
    OR (status = 'RELEASED' AND released_paise > 0 AND settled_paise + released_paise = allocated_paise));
ALTER TABLE offline_allowance ADD CONSTRAINT allowance_release_record CHECK (
    (released_paise = 0 AND released_at IS NULL AND release_operation_id IS NULL)
    OR (released_paise > 0 AND released_at IS NOT NULL AND release_operation_id IS NOT NULL));
CREATE INDEX allowance_sync_deadline_idx ON offline_allowance(sync_deadline);

CREATE TABLE offline_settlement (
    payment_id UUID PRIMARY KEY,
    allowance_id UUID NOT NULL REFERENCES offline_allowance(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation(id),
    receiver_wallet_id UUID NOT NULL REFERENCES wallet(id),
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    proof_hash VARCHAR(64) NOT NULL,
    proof_json TEXT NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (allowance_id, sequence_number)
);
CREATE INDEX settlement_receiver_history_idx ON offline_settlement(receiver_wallet_id, settled_at, payment_id);
CREATE TRIGGER settlement_append_only BEFORE UPDATE OR DELETE ON offline_settlement
FOR EACH ROW EXECUTE FUNCTION deny_financial_history_mutation();
-- Release is explicit, owner-only and deadline-gated. No automatic balance rewrite.
