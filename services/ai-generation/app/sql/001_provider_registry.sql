-- AI-owned database only. Core's V40 remains in place for rollback.
CREATE TABLE ai_provider_pools (
    platform VARCHAR(32) PRIMARY KEY CHECK (platform IN ('gemini', 'deepseek', 'openai_compatible')),
    default_model VARCHAR(100) NOT NULL,
    global_rpm_limit INTEGER NOT NULL CHECK (global_rpm_limit >= 0),
    global_rpd_limit INTEGER NOT NULL CHECK (global_rpd_limit >= 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE ai_provider_accounts (
    id VARCHAR(64) PRIMARY KEY,
    platform VARCHAR(32) NOT NULL REFERENCES ai_provider_pools(platform),
    account_id VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    api_key_ciphertext VARCHAR(2000) NOT NULL,
    rpm_limit INTEGER NOT NULL CHECK (rpm_limit > 0),
    operating_rpm INTEGER NOT NULL CHECK (operating_rpm >= 0 AND operating_rpm <= rpm_limit),
    tpm_limit INTEGER NOT NULL CHECK (tpm_limit >= 0),
    rpd_limit INTEGER NOT NULL CHECK (rpd_limit >= 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (platform, account_id, model)
);

REVOKE ALL ON ai_provider_pools, ai_provider_accounts FROM PUBLIC;
