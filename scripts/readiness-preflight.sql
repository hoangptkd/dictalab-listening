-- Read-only PostgreSQL V22 preflight. Every violations value must be zero.
-- Never repair/delete a learner record merely to make migration pass.
BEGIN READ ONLY;
SET LOCAL statement_timeout='15s';
SELECT 'user_email' AS invariant,count(*) AS violations FROM app_users WHERE email<>lower(trim(email))
UNION ALL SELECT 'user_versions',count(*) FROM app_users WHERE version<0 OR auth_version<0
UNION ALL SELECT 'practice_counters',count(*) FROM practice_sessions WHERE current_sentence_position<=0 OR total_replays<0 OR hint_count<0 OR reveal_count<0 OR known_missed_count<0 OR unknown_count<0 OR total_practice_seconds<0 OR version<0
UNION ALL SELECT 'practice_scores',count(*) FROM practice_sessions WHERE NOT(first_attempt_accuracy BETWEEN 0 AND 100 AND final_accuracy BETWEEN 0 AND 100 AND overall_score BETWEEN 0 AND 100)
UNION ALL SELECT 'sentence_counters',count(*) FROM sentence_attempts WHERE time_spent_seconds<0 OR replay_count<0 OR first_letter_hint_count<0 OR word_reveal_count<0 OR playback_event_count<0 OR slow_playback_count<0 OR playback_speed_sum<0
UNION ALL SELECT 'word_counters',count(*) FROM word_attempts WHERE word_index<0 OR attempt_count<0
UNION ALL SELECT 'token_expiry',count(*) FROM account_tokens WHERE expires_at<=created_at
UNION ALL SELECT 'listening_stage',count(*) FROM listening_sessions WHERE stage NOT IN ('READY','PLAYING','RECALL','QUESTIONS','PASS1_RESULT','COMPLETE','ABANDONED')
UNION ALL SELECT 'grading_state',count(*) FROM listening_passes WHERE grading_status NOT IN ('NOT_REQUESTED','PENDING','RUNNING','COMPLETED','FAILED') OR grading_attempts<0 OR grading_retry_count NOT BETWEEN 0 AND 2
UNION ALL SELECT 'listening_scores',count(*) FROM listening_passes WHERE (comprehension_score IS NOT NULL AND NOT(comprehension_score BETWEEN 0 AND 100)) OR (recall_score IS NOT NULL AND NOT(recall_score BETWEEN 0 AND 100))
UNION ALL SELECT 'conversation_shape',count(*) FROM ai_conversation_sessions WHERE scenario NOT IN ('cafe','directions','plans','tickets','delivery') OR difficulty NOT IN ('A1','A2','B1') OR version<0
UNION ALL SELECT 'conversation_audio',count(*) FROM ai_conversation_audio WHERE turn_number NOT BETWEEN 1 AND 4 OR variant NOT IN ('original','clarify')
UNION ALL SELECT 'duplicate_active_solo',count(*) FROM (SELECT 1 FROM practice_sessions WHERE status='IN_PROGRESS' AND room_id IS NULL GROUP BY user_id,lesson_id HAVING count(*)>1) duplicates
UNION ALL SELECT 'duplicate_shared_room',count(*) FROM (SELECT 1 FROM practice_sessions WHERE room_id IS NOT NULL GROUP BY user_id,room_id HAVING count(*)>1) duplicates
UNION ALL SELECT 'duplicate_listening_draft',count(*) FROM (SELECT 1 FROM listening_units WHERE NOT published AND NOT retired GROUP BY lesson_id,audio_url HAVING count(*)>1) duplicates
UNION ALL SELECT 'duplicate_listening_published',count(*) FROM (SELECT 1 FROM listening_units WHERE published AND NOT retired GROUP BY lesson_id,audio_url HAVING count(*)>1) duplicates;
SELECT 'enabled_admins' AS informational,count(*) FROM app_users WHERE enabled AND role='ADMIN';
SELECT current_database() AS database,current_setting('server_version') AS server_version,pg_database_size(current_database()) AS bytes;
COMMIT;
