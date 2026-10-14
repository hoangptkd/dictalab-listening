"""Read-only invariants scoped to the exact synthetic campaign, never repair/delete."""
import importlib.util
import json
import re
import subprocess
import sys

probe = json.load(sys.stdin)
prefix = probe['prefix']
if not re.fullmatch(r'prod-k6-[0-9a-f]{32}',prefix):
    raise SystemExit('Invalid synthetic campaign prefix')
spec = importlib.util.spec_from_file_location('backup','/opt/dictalab/tools/cloud-backup.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
env = backup.connection_environment()
env['PGOPTIONS'] = '-c statement_timeout=5000 -c lock_timeout=1000 -c default_transaction_read_only=on'
sql = """WITH probes AS (
 SELECT id FROM app_users WHERE email LIKE 'PREFIX-%@loadtest.local'
 AND display_name='Production k6 probe' AND role='USER'
), p AS (SELECT * FROM practice_sessions WHERE user_id IN (SELECT id FROM probes)),
a AS (SELECT * FROM sentence_attempts WHERE practice_session_id IN (SELECT id FROM p)),
w AS (SELECT * FROM word_attempts WHERE sentence_attempt_id IN (SELECT id FROM a))
SELECT json_build_object(
 'database',current_database(), 'probeAccounts',(SELECT count(*) FROM probes),
 'probeSessions',(SELECT count(*) FROM p),
 'probeSentenceAttempts',(SELECT count(*) FROM a),
 'probeWordAttempts',(SELECT count(*) FROM w),
 'probeWordEvents',(SELECT count(*) FROM word_attempt_events WHERE word_attempt_id IN (SELECT id FROM w)),
 'duplicateActiveSolo',(SELECT count(*) FROM (SELECT user_id,lesson_id FROM p WHERE status='IN_PROGRESS' AND room_id IS NULL GROUP BY user_id,lesson_id HAVING count(*)>1) d),
 'wordEventMismatch',(SELECT count(*) FROM w WHERE attempt_count<>(SELECT count(*) FROM word_attempt_events e WHERE e.word_attempt_id=w.id)),
 'replayCounterMismatch',(SELECT count(*) FROM p WHERE total_replays<>coalesce((SELECT sum(replay_count) FROM a WHERE practice_session_id=p.id),0)),
 'revealCounterMismatch',(SELECT count(*) FROM p WHERE reveal_count<>(SELECT count(*) FROM a WHERE practice_session_id=p.id AND full_reveal_used)),
 'completedDurationMismatch',(SELECT count(*) FROM p WHERE status='COMPLETED' AND total_practice_seconds<>coalesce((SELECT sum(time_spent_seconds) FROM a WHERE practice_session_id=p.id),0)),
 'incompleteCompletedLesson',(SELECT count(*) FROM p WHERE status='COMPLETED' AND
    (completed_at IS NULL OR (SELECT count(*) FROM a WHERE practice_session_id=p.id AND completed)<>
      (SELECT count(*) FROM lesson_sentences WHERE lesson_id=p.lesson_id))),
 'idleInTransactionNow',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state LIKE 'idle in transaction%'),
 'lockWaitersNow',(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'));
""".replace('PREFIX',prefix)
if probe.get('learning'):
    sql = sql.replace(" 'database',current_database(),", """
 'learningJobs',(SELECT count(*) FROM learning_jobs WHERE attempt_id IN (SELECT id FROM a)),
 'learningPending',(SELECT count(*) FROM learning_jobs WHERE attempt_id IN (SELECT id FROM a) AND status IN ('PENDING','RUNNING')),
 'learningFailed',(SELECT count(*) FROM learning_jobs WHERE attempt_id IN (SELECT id FROM a) AND status='FAILED'),
 'evidenceRows',(SELECT count(*) FROM listening_error_evidence WHERE user_id IN (SELECT id FROM probes)),
 'reviewCards',(SELECT count(*) FROM listening_review_cards WHERE user_id IN (SELECT id FROM probes)),
 'completedReviews',(SELECT count(*) FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE c.user_id IN (SELECT id FROM probes) AND r.completed_at IS NOT NULL),
 'jobWithoutCompletedSource',(SELECT count(*) FROM learning_jobs j JOIN a ON a.id=j.attempt_id WHERE NOT a.completed),
 'reviewRevisionMismatch',(SELECT count(*) FROM listening_review_cards c WHERE c.user_id IN (SELECT id FROM probes) AND c.revision<>(SELECT count(*) FROM listening_review_attempts r WHERE r.card_id=c.id AND r.completed_at IS NOT NULL)),
 'unplayedCompletedReview',(SELECT count(*) FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE c.user_id IN (SELECT id FROM probes) AND r.completed_at IS NOT NULL AND NOT r.played),
 'reviewResultMismatch',(SELECT count(*) FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE c.user_id IN (SELECT id FROM probes) AND ((r.completed_at IS NULL)<>(r.result_json IS NULL))),
 'database',current_database(),""")
result = subprocess.run(['/usr/lib/postgresql/18/bin/psql','-X','-qAt','-v','ON_ERROR_STOP=1','-c',sql],
                        env=env,capture_output=True,text=True,timeout=20)
if result.returncode:
    raise SystemExit('Read-only invariant check failed; credentials suppressed')
output = json.loads(result.stdout)
if output['database'] != 'neondb':
    raise SystemExit('Unexpected production database')
print(json.dumps(output))
