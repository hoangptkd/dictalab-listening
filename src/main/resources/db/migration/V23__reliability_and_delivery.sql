-- Additive migration; constraint validation intentionally fails on invalid legacy data.
-- Preflight must run before production activation. No legacy row is deleted or repaired here.
CREATE TABLE business_locks (id VARCHAR(40) PRIMARY KEY);
INSERT INTO business_locks(id) VALUES ('admin-policy');

ALTER TABLE listening_passes ADD COLUMN grading_lease VARCHAR(36);
ALTER TABLE listening_passes ADD COLUMN grading_last_error VARCHAR(80);

ALTER TABLE app_users ADD CONSTRAINT ck_user_email_normalized CHECK (email=LOWER(TRIM(email)));
ALTER TABLE app_users ADD CONSTRAINT ck_user_versions CHECK (version>=0 AND auth_version>=0);
ALTER TABLE practice_sessions ADD CONSTRAINT ck_practice_nonnegative CHECK (
    current_sentence_position>0 AND total_replays>=0 AND hint_count>=0 AND reveal_count>=0
    AND known_missed_count>=0 AND unknown_count>=0 AND total_practice_seconds>=0 AND version>=0);
ALTER TABLE practice_sessions ADD CONSTRAINT ck_practice_scores CHECK (
    first_attempt_accuracy BETWEEN 0 AND 100 AND final_accuracy BETWEEN 0 AND 100 AND overall_score BETWEEN 0 AND 100);
ALTER TABLE sentence_attempts ADD CONSTRAINT ck_sentence_counters CHECK (
    time_spent_seconds>=0 AND replay_count>=0 AND first_letter_hint_count>=0 AND word_reveal_count>=0
    AND playback_event_count>=0 AND slow_playback_count>=0 AND playback_speed_sum>=0);
ALTER TABLE word_attempts ADD CONSTRAINT ck_word_counters CHECK (word_index>=0 AND attempt_count>=0);
ALTER TABLE account_tokens ADD CONSTRAINT ck_token_expiry CHECK (expires_at>created_at);
ALTER TABLE listening_sessions ADD CONSTRAINT ck_listening_stage CHECK (
    stage IN ('READY','PLAYING','RECALL','QUESTIONS','PASS1_RESULT','COMPLETE','ABANDONED'));
ALTER TABLE listening_passes ADD CONSTRAINT ck_grading_state CHECK (
    grading_status IN ('NOT_REQUESTED','PENDING','RUNNING','COMPLETED','FAILED'));
ALTER TABLE listening_passes ADD CONSTRAINT ck_grading_counters CHECK (
    grading_attempts>=0 AND grading_retry_count BETWEEN 0 AND 2);
ALTER TABLE listening_passes ADD CONSTRAINT ck_listening_scores CHECK (
    (comprehension_score IS NULL OR comprehension_score BETWEEN 0 AND 100)
    AND (recall_score IS NULL OR recall_score BETWEEN 0 AND 100));
ALTER TABLE ai_conversation_sessions ADD CONSTRAINT ck_conversation_shape CHECK (
    scenario IN ('cafe','directions','plans','tickets','delivery') AND difficulty IN ('A1','A2','B1') AND version>=0);
ALTER TABLE ai_conversation_audio ADD CONSTRAINT ck_conversation_audio_key CHECK (
    turn_number BETWEEN 1 AND 4 AND variant IN ('original','clarify'));

CREATE INDEX idx_listening_history_page ON listening_sessions(user_id,started_at DESC,id DESC);

CREATE TABLE email_outbox (
    id VARCHAR(36) PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE REFERENCES account_tokens(token_hash) ON DELETE CASCADE,
    recipient VARCHAR(160) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_id VARCHAR(36),
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(80),
    CONSTRAINT ck_email_job_state CHECK (status IN ('PENDING','RUNNING','DELIVERED','FAILED')),
    CONSTRAINT ck_email_job_attempts CHECK (attempts BETWEEN 0 AND 5)
);
CREATE INDEX idx_email_pending ON email_outbox(status,available_at);
