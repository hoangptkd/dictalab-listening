-- Additive: historical drafts and immutable program versions retain their original meaning.
ALTER TABLE scenario_drafts ADD COLUMN learning_context_json TEXT;
CREATE TABLE scenario_learning_preferences (
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    program_code VARCHAR(64) NOT NULL,
    program_version INTEGER NOT NULL,
    preference_json TEXT NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id,program_code),
    FOREIGN KEY (program_code,program_version) REFERENCES scenario_program_versions(program_code,program_version)
);
