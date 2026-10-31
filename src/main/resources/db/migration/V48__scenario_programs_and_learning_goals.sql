-- Additive foundation only. No legacy Conversation data is changed or inferred.
CREATE TABLE scenario_program_versions (
    program_code VARCHAR(64) NOT NULL,
    program_version INTEGER NOT NULL CHECK (program_version > 0),
    definition_json TEXT NOT NULL,
    definition_hash VARCHAR(64) NOT NULL CHECK (LENGTH(definition_hash) = 64),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','APPROVED','RETIRED')),
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (program_code, program_version)
);

CREATE TABLE scenario_learning_goals (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    program_code VARCHAR(64) NOT NULL,
    program_version INTEGER NOT NULL,
    component VARCHAR(32) NOT NULL,
    scale_id VARCHAR(64) NOT NULL,
    target_value VARCHAR(32) NOT NULL,
    provenance VARCHAR(32) NOT NULL CHECK (provenance = 'USER_DECLARED_GOAL'),
    version BIGINT NOT NULL CHECK (version > 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, program_code, component),
    FOREIGN KEY (program_code, program_version) REFERENCES scenario_program_versions(program_code, program_version)
);

-- Stores the original response, so a retry remains idempotent even after later goal edits.
CREATE TABLE scenario_goal_requests (
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(36) NOT NULL,
    input_hash VARCHAR(64) NOT NULL CHECK (LENGTH(input_hash) = 64),
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, idempotency_key)
);
