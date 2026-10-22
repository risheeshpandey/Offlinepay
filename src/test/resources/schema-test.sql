-- H2 test fixture matching V1 + V3 + V4. PostgreSQL integration tests run Flyway V1-V4.
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    mobile_number VARCHAR(10) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE wallet (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    available_paise BIGINT NOT NULL DEFAULT 0 CHECK (available_paise >= 0),
    offline_reserved_paise BIGINT NOT NULL DEFAULT 0 CHECK (offline_reserved_paise >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE payment_operation (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('TRANSFER', 'DEMO_TOPUP', 'OFFLINE_RESERVE', 'OFFLINE_SETTLE', 'OFFLINE_RELEASE')),
    sender_wallet_id UUID REFERENCES wallet(id),
    receiver_wallet_id UUID NOT NULL REFERENCES wallet(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CHECK ((kind IN ('TRANSFER', 'OFFLINE_SETTLE') AND sender_wallet_id IS NOT NULL AND sender_wallet_id <> receiver_wallet_id)
        OR (kind = 'DEMO_TOPUP' AND sender_wallet_id IS NULL)
        OR (kind IN ('OFFLINE_RESERVE', 'OFFLINE_RELEASE') AND sender_wallet_id IS NOT NULL AND sender_wallet_id = receiver_wallet_id))
);

-- Wallet accounts are liabilities: CREDIT increases balance; DEBIT decreases it.
-- DEMO_ISSUANCE is the balancing system account for virtual-money funding.
CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES payment_operation(id),
    wallet_id UUID REFERENCES wallet(id),
    account_kind VARCHAR(20) NOT NULL CHECK (account_kind IN ('WALLET', 'DEMO_ISSUANCE', 'OFFLINE_RESERVED')),
    entry_type VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (operation_id, entry_type),
    CHECK ((account_kind IN ('WALLET', 'OFFLINE_RESERVED') AND wallet_id IS NOT NULL)
        OR (account_kind = 'DEMO_ISSUANCE' AND wallet_id IS NULL))
);
CREATE INDEX ledger_wallet_history_idx ON ledger_entry(wallet_id, created_at, id);
CREATE INDEX payment_actor_history_idx ON payment_operation(actor_id, created_at);

CREATE TABLE offline_allowance (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    wallet_id UUID NOT NULL REFERENCES wallet(id),
    operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation(id),
    device_id VARCHAR(80) NOT NULL,
    allocated_paise BIGINT NOT NULL CHECK (allocated_paise > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'PARTIALLY_SETTLED', 'SETTLED', 'RELEASED')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX allowance_user_history_idx ON offline_allowance(user_id, created_at, id);
-- V2's deferred balanced-operation trigger also applies to OFFLINE_RESERVE.
-- Expiry does not release funds automatically. Reconciliation/release comes later.

CREATE TABLE refresh_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES refresh_session(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX refresh_token_session_idx ON refresh_token(session_id);
CREATE INDEX refresh_session_expiry_idx ON refresh_session(expires_at);

ALTER TABLE wallet ADD CONSTRAINT wallet_total_capacity_check CHECK (CAST(available_paise AS NUMERIC) + CAST(offline_reserved_paise AS NUMERIC) <= 9223372036854775807);

CREATE TABLE registered_device (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    device_id VARCHAR(80) NOT NULL,
    public_key VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (user_id, device_id)
);
ALTER TABLE offline_allowance ADD COLUMN device_public_key VARCHAR(100);
ALTER TABLE offline_allowance ADD COLUMN server_key_id VARCHAR(64);
ALTER TABLE offline_allowance ADD COLUMN server_signature VARCHAR(100);
ALTER TABLE offline_allowance ADD CONSTRAINT allowance_certificate_complete CHECK (
    (device_public_key IS NULL AND server_key_id IS NULL AND server_signature IS NULL)
    OR (device_public_key IS NOT NULL AND server_key_id IS NOT NULL AND server_signature IS NOT NULL)
);
-- Existing reservations retain balances and have no certificate. Never auto-bind legacy funds.

-- V6 H2 fixture; actual PostgreSQL suites execute the migration and triggers.
ALTER TABLE offline_allowance ADD COLUMN settled_paise BIGINT NOT NULL DEFAULT 0;
ALTER TABLE offline_allowance ADD COLUMN released_paise BIGINT NOT NULL DEFAULT 0;
ALTER TABLE offline_allowance ADD COLUMN sync_deadline TIMESTAMP WITH TIME ZONE;
ALTER TABLE offline_allowance ADD COLUMN released_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE offline_allowance ADD COLUMN release_operation_id UUID UNIQUE REFERENCES payment_operation(id);
UPDATE offline_allowance SET sync_deadline = DATEADD('HOUR', 48, expires_at);
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

-- V7
-- Additive migration: existing balances, certificates, receipts and keys are unchanged.
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES payment_operation(id),
    payload TEXT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PROCESSING','PUBLISHED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token UUID,
    published_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (id = operation_id),
    CHECK ((status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_until IS NULL AND lease_token IS NULL))
);
CREATE INDEX outbox_ready_idx ON outbox_event(status, next_attempt_at, lease_until);

CREATE TABLE consumed_payment_event (
    event_id UUID PRIMARY KEY REFERENCES outbox_event(id),
    payload_hash VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE user_notification (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_event(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    kind VARCHAR(30) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    delivery_status VARCHAR(20) NOT NULL CHECK (delivery_status IN ('IN_APP_ONLY','PENDING','PROCESSING','DELIVERED','FAILED')),
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    lease_token UUID,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(100),
    UNIQUE(event_id, user_id),
    CHECK ((delivery_status='PROCESSING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (delivery_status<>'PROCESSING' AND lease_until IS NULL AND lease_token IS NULL))
);
CREATE INDEX notification_user_history_idx ON user_notification(user_id, created_at, id);
CREATE INDEX notification_ready_idx ON user_notification(delivery_status, next_attempt_at, lease_until);
CREATE TABLE dead_letter_event (
    id UUID PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    partition_number INTEGER NOT NULL,
    offset_number BIGINT NOT NULL,
    event_key TEXT,
    payload TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(topic, partition_number, offset_number)
);
-- No historical backfill: old successful operations must not trigger surprise notifications.
