-- Additive vocabulary module. Listening scores and listening review schedules are independent.
CREATE TABLE vocab_decks (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id BIGINT REFERENCES app_users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    level VARCHAR(10) NOT NULL DEFAULT 'A1',
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vocab_decks_owner ON vocab_decks(owner_user_id,status,created_at);
CREATE TABLE vocab_notes (
    id VARCHAR(36) PRIMARY KEY,
    owner_user_id BIGINT REFERENCES app_users(id) ON DELETE CASCADE,
    term VARCHAR(200) NOT NULL,
    meaning_vi VARCHAR(1000) NOT NULL,
    part_of_speech VARCHAR(40) NOT NULL DEFAULT '',
    example_en VARCHAR(1000) NOT NULL DEFAULT '',
    example_vi VARCHAR(1000) NOT NULL DEFAULT '',
    tags VARCHAR(500) NOT NULL DEFAULT '',
    alternatives_json TEXT NOT NULL DEFAULT '[]',
    source_kind VARCHAR(20) NOT NULL CHECK (source_kind IN ('MANUAL','CATALOG','CSV','PRACTICE')),
    source_note_id VARCHAR(36) REFERENCES vocab_notes(id) ON DELETE SET NULL,
    source_revision BIGINT,
    revision BIGINT NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id,owner_user_id),
    UNIQUE (owner_user_id,source_note_id)
);
CREATE INDEX idx_vocab_notes_owner ON vocab_notes(owner_user_id,archived,created_at);
CREATE TABLE vocab_deck_notes (
    deck_id VARCHAR(36) NOT NULL REFERENCES vocab_decks(id) ON DELETE CASCADE,
    note_id VARCHAR(36) NOT NULL REFERENCES vocab_notes(id) ON DELETE CASCADE,
    PRIMARY KEY (deck_id,note_id)
);
CREATE INDEX idx_vocab_membership_note ON vocab_deck_notes(note_id,deck_id);
CREATE TABLE vocab_note_sources (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    note_id VARCHAR(36) NOT NULL,
    word_attempt_id BIGINT REFERENCES word_attempts(id) ON DELETE SET NULL,
    source_key VARCHAR(100) NOT NULL,
    transcript VARCHAR(1000) NOT NULL,
    lesson_title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (note_id,user_id) REFERENCES vocab_notes(id,owner_user_id) ON DELETE CASCADE,
    UNIQUE (user_id,source_key)
);
CREATE TABLE vocab_cards (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    note_id VARCHAR(36) NOT NULL,
    card_type VARCHAR(20) NOT NULL CHECK (card_type IN ('WORD_RECALL','MEANING_RECALL')),
    state VARCHAR(12) NOT NULL DEFAULT 'NEW' CHECK (state IN ('NEW','REVIEW','RELEARNING')),
    scheduler_version VARCHAR(40) NOT NULL DEFAULT 'SM2_PRODUCT_V1',
    due_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_reviewed_at TIMESTAMP WITH TIME ZONE,
    buried_until TIMESTAMP WITH TIME ZONE,
    interval_days INTEGER NOT NULL DEFAULT 0 CHECK (interval_days BETWEEN 0 AND 365),
    ease_factor DOUBLE PRECISION NOT NULL DEFAULT 2.5 CHECK (ease_factor >= 1.3),
    successful_repetitions INTEGER NOT NULL DEFAULT 0 CHECK (successful_repetitions >= 0),
    lapse_count INTEGER NOT NULL DEFAULT 0 CHECK (lapse_count >= 0),
    suspended BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (note_id,user_id) REFERENCES vocab_notes(id,owner_user_id) ON DELETE CASCADE,
    UNIQUE (user_id,note_id,card_type)
);
CREATE INDEX idx_vocab_cards_due ON vocab_cards(user_id,suspended,due_at);
CREATE INDEX idx_vocab_sources_word ON vocab_note_sources(word_attempt_id);
CREATE TABLE vocab_study_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    deck_id VARCHAR(36) REFERENCES vocab_decks(id) ON DELETE SET NULL,
    mode VARCHAR(24) NOT NULL CHECK (mode IN ('SRS','PRACTICE','CONTEXT_TRANSLATION')),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','COMPLETED','ABANDONED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (id,user_id)
);
CREATE INDEX idx_vocab_sessions_user ON vocab_study_sessions(user_id,status,created_at);
CREATE TABLE vocab_session_items (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES vocab_study_sessions(id) ON DELETE CASCADE,
    position_index INTEGER NOT NULL CHECK (position_index >= 0),
    card_id VARCHAR(36) REFERENCES vocab_cards(id) ON DELETE SET NULL,
    card_revision BIGINT,
    exercise_key VARCHAR(80),
    prompt VARCHAR(2000) NOT NULL,
    expected_json TEXT NOT NULL,
    feedback_json TEXT NOT NULL,
    answer VARCHAR(4000),
    evaluation VARCHAR(20) CHECK (evaluation IN ('CORRECT','INCORRECT','UNDETERMINED')),
    assisted BOOLEAN NOT NULL DEFAULT FALSE,
    answered_at TIMESTAMP WITH TIME ZONE,
    graded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (session_id,position_index)
);
CREATE TABLE vocab_review_logs (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    item_id VARCHAR(36) NOT NULL UNIQUE REFERENCES vocab_session_items(id) ON DELETE CASCADE,
    note_id VARCHAR(36) REFERENCES vocab_notes(id) ON DELETE SET NULL,
    card_id VARCHAR(36) REFERENCES vocab_cards(id) ON DELETE SET NULL,
    grade VARCHAR(8) NOT NULL CHECK (grade IN ('AGAIN','HARD','GOOD','EASY')),
    grading_source VARCHAR(12) NOT NULL CHECK (grading_source IN ('RULES','SELF')),
    answer VARCHAR(4000) NOT NULL,
    assisted BOOLEAN NOT NULL,
    schedule_changed BOOLEAN NOT NULL,
    was_new BOOLEAN NOT NULL DEFAULT FALSE,
    learning_day DATE NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    target_results_json TEXT NOT NULL DEFAULT '[]',
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_vocab_logs_user ON vocab_review_logs(user_id,reviewed_at);
CREATE INDEX idx_vocab_logs_card ON vocab_review_logs(card_id,reviewed_at);
CREATE INDEX idx_vocab_logs_day ON vocab_review_logs(user_id,learning_day,was_new);
CREATE TABLE vocab_receipts (
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    submission_key VARCHAR(100) NOT NULL,
    action VARCHAR(120) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id,submission_key)
);
CREATE TABLE vocab_imports (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    deck_id VARCHAR(36) NOT NULL REFERENCES vocab_decks(id) ON DELETE CASCADE,
    payload_json TEXT NOT NULL,
    result_json TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_vocab_imports_user ON vocab_imports(user_id,expires_at);
CREATE TABLE vocab_context_exercises (
    id VARCHAR(36) PRIMARY KEY,
    prompt VARCHAR(1000) NOT NULL,
    translation_vi VARCHAR(1000) NOT NULL,
    level VARCHAR(10) NOT NULL,
    alternatives_json TEXT NOT NULL DEFAULT '[]',
    published BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE vocab_context_targets (
    exercise_id VARCHAR(36) NOT NULL REFERENCES vocab_context_exercises(id) ON DELETE CASCADE,
    note_id VARCHAR(36) NOT NULL REFERENCES vocab_notes(id),
    surface_form VARCHAR(200) NOT NULL,
    PRIMARY KEY (exercise_id,note_id)
);
