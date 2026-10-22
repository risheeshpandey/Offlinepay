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
