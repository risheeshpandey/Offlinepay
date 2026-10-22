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
