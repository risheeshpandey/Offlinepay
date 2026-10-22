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
