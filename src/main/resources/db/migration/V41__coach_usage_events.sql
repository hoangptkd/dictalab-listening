-- Additive, bounded behavioral telemetry. Contains no answers, transcripts, or score changes.
CREATE TABLE coach_usage_events (
    session_id VARCHAR(36) NOT NULL REFERENCES practice_sessions(id) ON DELETE CASCADE,
    event_name VARCHAR(40) NOT NULL CHECK (event_name IN (
        'coach_result_viewed', 'coach_details_opened',
        'coach_quick_review_started', 'coach_quick_review_completed')),
    review_run_id VARCHAR(36) NOT NULL,
    ui_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, event_name, review_run_id)
);
CREATE INDEX idx_coach_usage_created ON coach_usage_events(created_at);
