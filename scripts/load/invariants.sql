-- Read-only checks. Never point this at production, even for a read-only inspection.
DO $$ BEGIN
    IF current_database() !~ '^dictalab_capacity_[a-z0-9]+$' THEN
        RAISE EXCEPTION 'An isolated suffixed capacity database is required';
    END IF;
END $$;

SELECT 'duplicate_active_solo',count(*) FROM (
    SELECT user_id,lesson_id FROM practice_sessions WHERE status='IN_PROGRESS' AND room_id IS NULL
    GROUP BY user_id,lesson_id HAVING count(*)>1
) duplicates
UNION ALL
SELECT 'duplicate_active_room',count(*) FROM (
    SELECT user_id,room_id FROM practice_sessions WHERE status='IN_PROGRESS' AND room_id IS NOT NULL
    GROUP BY user_id,room_id HAVING count(*)>1
) duplicates
UNION ALL
SELECT 'word_event_count_mismatch',count(*) FROM word_attempts w
WHERE w.attempt_count<>(SELECT count(*) FROM word_attempt_events e WHERE e.word_attempt_id=w.id)
UNION ALL
SELECT 'session_replay_counter_mismatch',count(*) FROM practice_sessions p
WHERE p.total_replays<>COALESCE((SELECT sum(a.replay_count) FROM sentence_attempts a WHERE a.practice_session_id=p.id),0)
UNION ALL
SELECT 'session_reveal_counter_mismatch',count(*) FROM practice_sessions p
WHERE p.reveal_count<>(SELECT count(*) FROM sentence_attempts a WHERE a.practice_session_id=p.id AND a.full_reveal_used)
UNION ALL
SELECT 'completed_duration_mismatch',count(*) FROM practice_sessions p WHERE p.status='COMPLETED'
AND p.total_practice_seconds<>COALESCE((SELECT sum(a.time_spent_seconds) FROM sentence_attempts a WHERE a.practice_session_id=p.id),0)
UNION ALL
SELECT 'incomplete_completed_lesson',count(*) FROM practice_sessions p WHERE p.status='COMPLETED' AND
    (p.completed_at IS NULL OR
     (SELECT count(*) FROM sentence_attempts a WHERE a.practice_session_id=p.id AND a.completed)<>
     (SELECT count(*) FROM lesson_sentences s WHERE s.lesson_id=p.lesson_id))
UNION ALL
SELECT 'completed_grade_missing_result',count(*) FROM listening_passes
WHERE grading_status='COMPLETED' AND (recall_score IS NULL OR recall_result_json IS NULL)
UNION ALL
SELECT 'running_grade_missing_lease',count(*) FROM listening_passes
WHERE grading_status='RUNNING' AND (grading_lease IS NULL OR grading_started_at IS NULL)
ORDER BY 1;

SELECT 'accounts',count(*) FROM app_users
UNION ALL SELECT 'history_sessions',count(*) FROM practice_sessions
UNION ALL SELECT 'sentence_attempts',count(*) FROM sentence_attempts
UNION ALL SELECT 'word_attempts',count(*) FROM word_attempts
UNION ALL SELECT 'word_events',count(*) FROM word_attempt_events
UNION ALL SELECT 'listening_sessions',count(*) FROM listening_sessions
ORDER BY 1;
