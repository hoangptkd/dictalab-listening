-- Stable legacy routes preserve access rules and learning-history references.
CREATE TABLE audio_s3_aliases (
    audio_url VARCHAR(500) PRIMARY KEY,
    object_name VARCHAR(80) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_count BIGINT NOT NULL CHECK (byte_count > 0)
);
ALTER TABLE ai_conversation_audio ADD COLUMN s3_audio_url VARCHAR(120);
ALTER TABLE ai_conversation_audio ALTER COLUMN wav DROP NOT NULL;
ALTER TABLE ai_conversation_audio ADD CONSTRAINT ck_conversation_audio_storage
    CHECK (wav IS NOT NULL OR s3_audio_url IS NOT NULL);
