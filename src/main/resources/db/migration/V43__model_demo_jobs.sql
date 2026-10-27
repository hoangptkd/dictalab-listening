-- Additive, empty job ledger. Core owns learner access and accepted conversation turns.
CREATE TABLE model_demo_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    request_key VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) REFERENCES ai_conversation_sessions(id) ON DELETE CASCADE,
    mode VARCHAR(10) NOT NULL CHECK (mode IN ('practice','compare')),
    payload TEXT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    submitted BOOLEAN NOT NULL DEFAULT FALSE,
    deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_id VARCHAR(36),
    lease_until TIMESTAMP WITH TIME ZONE,
    poll_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result_json TEXT,
    error_code VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, request_key)
);
CREATE INDEX model_demo_dispatch ON model_demo_jobs(status,poll_at);
CREATE INDEX model_demo_history ON model_demo_jobs(user_id,created_at);
