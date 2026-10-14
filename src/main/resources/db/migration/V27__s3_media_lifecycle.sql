-- Additive catalog/outbox; no rewrite, backfill or FK locks on existing content tables.
-- Legacy URLs are registered lazily by reference writers. Keep tombstones to fence late attachment.
CREATE TABLE s3_media_lifecycle (
    audio_url VARCHAR(120) PRIMARY KEY,
    storage_identity VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('STAGED','LIVE','DELETING','DELETED')),
    upload_until TIMESTAMP WITH TIME ZONE,
    cleanup_after TIMESTAMP WITH TIME ZONE,
    lease_id VARCHAR(36),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_s3_media_cleanup ON s3_media_lifecycle(cleanup_after);
