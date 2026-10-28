CREATE TABLE progress_users (
    user_id BIGINT PRIMARY KEY CHECK (user_id > 0),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_event_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE progress_sessions (
    session_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES progress_users(user_id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    payload_hash VARCHAR(64) NOT NULL,
    lesson_id BIGINT NOT NULL,
    lesson_title VARCHAR(500) NOT NULL,
    level VARCHAR(2) NOT NULL CHECK (level IN ('A1','A2','B1','B2')),
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_date DATE NOT NULL,
    practice_seconds INTEGER NOT NULL CHECK (practice_seconds >= 0),
    first_accuracy DOUBLE PRECISION NOT NULL CHECK (first_accuracy BETWEEN 0 AND 100),
    final_accuracy DOUBLE PRECISION NOT NULL CHECK (final_accuracy BETWEEN 0 AND 100),
    score DOUBLE PRECISION NOT NULL CHECK (score BETWEEN 0 AND 100),
    known_missed INTEGER NOT NULL CHECK (known_missed >= 0),
    unknown_words INTEGER NOT NULL CHECK (unknown_words >= 0)
);
CREATE INDEX progress_sessions_user_completed ON progress_sessions(user_id,completed_at,session_id);
