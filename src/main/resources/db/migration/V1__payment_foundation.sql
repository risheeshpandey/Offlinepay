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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE payment_operation (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('TRANSFER', 'DEMO_TOPUP')),
    sender_wallet_id UUID REFERENCES wallet(id),
    receiver_wallet_id UUID NOT NULL REFERENCES wallet(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (actor_id, idempotency_key),
    CHECK ((kind = 'TRANSFER' AND sender_wallet_id IS NOT NULL AND sender_wallet_id <> receiver_wallet_id)
        OR (kind = 'DEMO_TOPUP' AND sender_wallet_id IS NULL))
);

-- Wallet accounts are liabilities: CREDIT increases balance; DEBIT decreases it.
-- DEMO_ISSUANCE is the balancing system account for virtual-money funding.
CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES payment_operation(id),
    wallet_id UUID REFERENCES wallet(id),
    account_kind VARCHAR(20) NOT NULL CHECK (account_kind IN ('WALLET', 'DEMO_ISSUANCE')),
    entry_type VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (operation_id, entry_type),
    CHECK ((account_kind = 'WALLET' AND wallet_id IS NOT NULL)
        OR (account_kind = 'DEMO_ISSUANCE' AND wallet_id IS NULL))
);
CREATE INDEX ledger_wallet_history_idx ON ledger_entry(wallet_id, created_at, id);
CREATE INDEX payment_actor_history_idx ON payment_operation(actor_id, created_at);
