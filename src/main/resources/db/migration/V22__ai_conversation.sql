CREATE TABLE ai_conversation_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    scenario VARCHAR(30) NOT NULL,
    difficulty VARCHAR(10) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    lease_id VARCHAR(36),
    busy_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ai_conversation_user ON ai_conversation_sessions(user_id, created_at);

-- Audio is private and is deleted along with the session/account.
CREATE TABLE ai_conversation_audio (
    session_id VARCHAR(36) NOT NULL REFERENCES ai_conversation_sessions(id) ON DELETE CASCADE,
    turn_number INTEGER NOT NULL,
    variant VARCHAR(12) NOT NULL,
    wav BYTEA NOT NULL,
    PRIMARY KEY (session_id, turn_number, variant)
);
