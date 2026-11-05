CREATE TABLE scenario_drafts (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    input_vi VARCHAR(2000) NOT NULL,
    program_code VARCHAR(64) NOT NULL,
    program_version INTEGER NOT NULL,
    program_hash VARCHAR(64) NOT NULL,
    profile_json TEXT NOT NULL,
    target_json TEXT NOT NULL,
    specification_json TEXT,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING','READY','FAILED','EXPIRED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    parent_session_id VARCHAR(36),
    family_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (program_code,program_version) REFERENCES scenario_program_versions(program_code,program_version)
);
CREATE INDEX idx_scenario_drafts_owner ON scenario_drafts(user_id,created_at,id);
CREATE TABLE scenario_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    draft_id VARCHAR(36) NOT NULL REFERENCES scenario_drafts(id) ON DELETE CASCADE,
    title_vi VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PREPARING','ACTIVE','COMPLETED','ABANDONED','PREPARATION_FAILED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    parent_session_id VARCHAR(36) REFERENCES scenario_sessions(id) ON DELETE SET NULL,
    family_id VARCHAR(36) NOT NULL,
    provenance_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);
ALTER TABLE scenario_drafts ADD CONSTRAINT fk_scenario_draft_parent FOREIGN KEY (parent_session_id) REFERENCES scenario_sessions(id) ON DELETE SET NULL;
CREATE INDEX idx_scenario_sessions_owner ON scenario_sessions(user_id,created_at,id);
CREATE INDEX idx_scenario_sessions_status ON scenario_sessions(user_id,status,created_at,id);
CREATE TABLE scenario_activities (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES scenario_sessions(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 4),
    private_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','COMPLETED')),
    transcript_opened BOOLEAN NOT NULL DEFAULT FALSE,
    hint_opened BOOLEAN NOT NULL DEFAULT FALSE,
    plays INTEGER NOT NULL DEFAULT 0 CHECK (plays >= 0),
    UNIQUE (session_id,ordinal)
);
CREATE TABLE scenario_attempts (
    id VARCHAR(36) PRIMARY KEY,
    activity_id VARCHAR(36) NOT NULL REFERENCES scenario_activities(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 3),
    answers_json TEXT NOT NULL,
    support_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','GRADED','FAILED','UNKNOWN')),
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (activity_id,ordinal)
);
CREATE TABLE scenario_operations (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    request_key VARCHAR(36) NOT NULL,
    scope VARCHAR(100) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('EXTRACT','GENERATE','GRADE','TTS')),
    resource_id VARCHAR(36) NOT NULL,
    payload_json TEXT NOT NULL,
    result_metadata_json TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','CANCELLED','EXPIRED')),
    submitted BOOLEAN NOT NULL DEFAULT FALSE,
    lease_id VARCHAR(36),
    lease_until TIMESTAMP WITH TIME ZONE,
    poll_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    error_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id,scope,request_key)
);
CREATE INDEX idx_scenario_operation_dispatch ON scenario_operations(status,poll_at,lease_until);
CREATE TABLE scenario_operation_budget (
    operation_id VARCHAR(36) PRIMARY KEY REFERENCES scenario_operations(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    calls_reserved INTEGER NOT NULL CHECK (calls_reserved BETWEEN 1 AND 7),
    local_provider BOOLEAN NOT NULL,
    tts BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE scenario_media (
    id VARCHAR(36) PRIMARY KEY,
    activity_id VARCHAR(36) NOT NULL REFERENCES scenario_activities(id) ON DELETE CASCADE,
    segment_id VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','READY','FAILED','UNKNOWN')),
    s3_audio_url VARCHAR(300),
    wav BYTEA,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (activity_id,segment_id,fingerprint)
);
CREATE TABLE scenario_receipts (
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    scope VARCHAR(100) NOT NULL,
    request_key VARCHAR(36) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    PRIMARY KEY (user_id,scope,request_key)
);
