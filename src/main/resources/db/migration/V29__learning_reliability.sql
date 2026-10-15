-- Additive only: existing attempts, scores and the manually saved error bank remain unchanged.
-- Nullable metadata column; do not reinterpret default error_type values in historical attempts.
ALTER TABLE word_attempts ADD COLUMN learner_reported_error_type VARCHAR(30);
CREATE TABLE practice_submission_receipts (
    session_id VARCHAR(36) NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
    submission_key VARCHAR(100) NOT NULL,
    action VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(session_id, submission_key)
);

CREATE TABLE learning_jobs (
    id VARCHAR(36) PRIMARY KEY,
    attempt_id BIGINT NOT NULL UNIQUE REFERENCES sentence_attempts(id) ON DELETE CASCADE,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DONE','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    lease_id VARCHAR(36),
    locked_until TIMESTAMP WITH TIME ZONE,
    publish_after TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_learning_jobs_pending ON learning_jobs(status,publish_after);

CREATE TABLE listening_error_evidence (
    word_attempt_id BIGINT PRIMARY KEY REFERENCES word_attempts(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    target VARCHAR(200) NOT NULL,
    expected VARCHAR(200) NOT NULL,
    heard VARCHAR(200),
    observation VARCHAR(30) NOT NULL,
    possible_pattern VARCHAR(30),
    learner_reported_error_type VARCHAR(30),
    comparable BOOLEAN NOT NULL,
    first_correct BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_error_evidence_user_target ON listening_error_evidence(user_id,target,created_at);

CREATE TABLE listening_review_cards (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    target VARCHAR(200) NOT NULL,
    source_word_id BIGINT NOT NULL REFERENCES word_attempts(id) ON DELETE CASCADE,
    stage INTEGER NOT NULL DEFAULT 0 CHECK(stage BETWEEN 0 AND 4),
    revision BIGINT NOT NULL DEFAULT 0,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_sentence_id BIGINT REFERENCES lesson_sentences(id),
    UNIQUE(user_id,target)
);
CREATE INDEX idx_review_cards_due ON listening_review_cards(user_id,due_at);

CREATE TABLE listening_review_sources (
    sentence_id BIGINT PRIMARY KEY REFERENCES lesson_sentences(id) ON DELETE CASCADE,
    transcript VARCHAR(1000) NOT NULL,
    transcript_hash VARCHAR(64) NOT NULL
);
CREATE TABLE listening_review_tokens (
    sentence_id BIGINT NOT NULL REFERENCES listening_review_sources(sentence_id) ON DELETE CASCADE,
    word_index INTEGER NOT NULL CHECK(word_index >= 0),
    target VARCHAR(200) NOT NULL,
    PRIMARY KEY(sentence_id,word_index)
);
CREATE INDEX idx_review_tokens_target ON listening_review_tokens(target,sentence_id);

CREATE TABLE listening_review_attempts (
    id VARCHAR(36) PRIMARY KEY,
    card_id VARCHAR(36) NOT NULL REFERENCES listening_review_cards(id) ON DELETE CASCADE,
    sentence_id BIGINT NOT NULL REFERENCES lesson_sentences(id),
    card_revision BIGINT NOT NULL,
    word_index INTEGER NOT NULL,
    expected VARCHAR(200) NOT NULL,
    transcript VARCHAR(1000) NOT NULL,
    audio_url VARCHAR(500) NOT NULL,
    audio_start_seconds DOUBLE PRECISION,
    audio_end_seconds DOUBLE PRECISION,
    new_context BOOLEAN NOT NULL,
    played BOOLEAN NOT NULL DEFAULT FALSE,
    request_hash VARCHAR(64),
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(card_id,card_revision)
);
CREATE INDEX idx_review_attempts_sentence ON listening_review_attempts(sentence_id);
