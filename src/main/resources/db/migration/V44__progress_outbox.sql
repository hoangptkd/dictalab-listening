-- Additive, empty outbox. Historical data is exported in bounded application batches.
-- No learner table rewrite or backfill runs inside the schema migration.
CREATE TABLE progress_outbox (
    id VARCHAR(36) PRIMARY KEY,
    dedup_key VARCHAR(160) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_id VARCHAR(36),
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX progress_outbox_dispatch ON progress_outbox(status,available_at);
CREATE INDEX progress_outbox_user ON progress_outbox(user_id);

-- Projection revisions include metadata changes and do not depend on JPA flush timing.
CREATE TABLE progress_projection_versions (
    session_id VARCHAR(36) PRIMARY KEY REFERENCES practice_sessions(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0,
    snapshot_hash VARCHAR(64) NOT NULL
);
CREATE TABLE progress_lesson_refresh (
    lesson_id BIGINT PRIMARY KEY REFERENCES lessons(id) ON DELETE CASCADE,
    cursor_id VARCHAR(36) NOT NULL DEFAULT ''
);
