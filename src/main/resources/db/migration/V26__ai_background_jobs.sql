-- Additive tables only. PostgreSQL remains authoritative; broker messages contain job IDs only.
-- Parent FKs need brief locks at migration time. Run with the normal backup/maintenance procedure.
CREATE TABLE ai_job_limits (
    kind VARCHAR(8) PRIMARY KEY,
    worker_limit INTEGER NOT NULL CHECK (worker_limit BETWEEN 1 AND 8)
);
INSERT INTO ai_job_limits(kind,worker_limit) VALUES ('AUDIO',2),('COACH',1);

CREATE TABLE ai_background_jobs (
    id VARCHAR(36) PRIMARY KEY,
    job_key VARCHAR(160) NOT NULL UNIQUE,
    kind VARCHAR(8) NOT NULL REFERENCES ai_job_limits(kind),
    conversation_id VARCHAR(36) REFERENCES ai_conversation_sessions(id) ON DELETE CASCADE,
    practice_id VARCHAR(36) REFERENCES practice_sessions(id) ON DELETE CASCADE,
    turn_number INTEGER,
    variant VARCHAR(12),
    input_hash VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    lease_id VARCHAR(36),
    locked_until TIMESTAMP WITH TIME ZONE,
    publish_after TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','UNKNOWN')),
    CHECK ((kind='AUDIO' AND conversation_id IS NOT NULL AND practice_id IS NULL
        AND turn_number IS NOT NULL AND turn_number BETWEEN 1 AND 4
        AND variant IS NOT NULL AND variant IN ('original','clarify') AND input_hash IS NOT NULL)
        OR (kind='COACH' AND practice_id IS NOT NULL AND conversation_id IS NULL
        AND turn_number IS NULL AND variant IS NULL AND input_hash IS NULL))
);
CREATE INDEX idx_ai_jobs_dispatch ON ai_background_jobs(status,publish_after,created_at);
CREATE INDEX idx_ai_jobs_conversation ON ai_background_jobs(conversation_id);
CREATE INDEX idx_ai_jobs_practice ON ai_background_jobs(practice_id);
