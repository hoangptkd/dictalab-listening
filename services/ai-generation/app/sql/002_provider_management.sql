-- Applied explicitly by the registry owner. Runtime account permissions stay read-only.
CREATE TABLE ai_provider_management (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO ai_provider_management(singleton) VALUES (1);
CREATE TABLE ai_provider_labels (
    source_id VARCHAR(64) PRIMARY KEY REFERENCES ai_provider_accounts(id),
    name VARCHAR(120) NOT NULL
);
CREATE TABLE ai_provider_audit (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id BIGINT NOT NULL,
    source_id VARCHAR(64),
    action VARCHAR(32) NOT NULL,
    changes JSONB NOT NULL
);
CREATE TABLE ai_provider_usage (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_id UUID NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    model VARCHAR(100) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    request_done BOOLEAN NOT NULL,
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0)
);
CREATE INDEX ai_provider_usage_time ON ai_provider_usage(created_at);
CREATE INDEX ai_provider_usage_source_time ON ai_provider_usage(source_id, created_at);
CREATE INDEX ai_provider_audit_time ON ai_provider_audit(id DESC);
REVOKE ALL ON ai_provider_management, ai_provider_labels, ai_provider_audit, ai_provider_usage FROM PUBLIC;
REVOKE ALL ON SEQUENCE ai_provider_audit_id_seq, ai_provider_usage_id_seq FROM PUBLIC;
