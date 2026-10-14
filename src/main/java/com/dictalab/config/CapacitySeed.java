package com.dictalab.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;

/** Explicit, isolated PostgreSQL load-test fixture. Never activates on the live DictaLab database. */
@Component
@Profile("capacity")
public class CapacitySeed implements CommandLineRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final String password;
    private final int accounts,sessions;
    public CapacitySeed(JdbcTemplate db,PasswordEncoder passwords,
            @Value("${CAPACITY_PASSWORD:}") String password,@Value("${CAPACITY_ACCOUNTS:1000}") int accounts,
            @Value("${CAPACITY_SESSIONS_PER_USER:24}") int sessions){
        this.db=db;this.passwords=passwords;this.password=password;this.accounts=accounts;this.sessions=sessions;
    }
    @Override public void run(String... args){
        String name=db.queryForObject("SELECT current_database()",String.class);
        if(name==null || !name.matches("dictalab_capacity(?:_[a-z0-9]+)?") || password.length()<12
                || accounts<1 || accounts>20000 || sessions<0 || sessions>1000)
            throw new IllegalStateException("Capacity fixture requires an isolated dictalab_capacity database and explicit test password/budgets");
        String hash=passwords.encode(password);
        db.update("""
                INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at)
                SELECT 'capacity-'||n||'@loadtest.local','Capacity learner',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM generate_series(1,?) n ON CONFLICT(email) DO NOTHING
                """,hash,accounts);
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at)
                SELECT md5('capacity-fixture:'||u.id||':'||n)::uuid::text,'capacity-fixture-user-'||u.id,u.id,l.id,'COMPLETED',
                    CURRENT_TIMESTAMP-n*INTERVAL '1 minute',CURRENT_TIMESTAMP-n*INTERVAL '1 minute'
                FROM app_users u CROSS JOIN generate_series(1,?) n
                CROSS JOIN LATERAL (SELECT id FROM lessons WHERE active AND content_origin<>'BRITISH_COUNCIL_PERSONAL'
                    ORDER BY id OFFSET ((u.id+n) % (SELECT COUNT(*) FROM lessons WHERE active AND
                    content_origin<>'BRITISH_COUNCIL_PERSONAL')) LIMIT 1) l
                WHERE u.email LIKE 'capacity-%@loadtest.local'
                ON CONFLICT(id) DO NOTHING
                """,sessions);
        db.update("""
                INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed,
                    full_reveal_used,replayed_after_reveal,replay_count,time_spent_seconds)
                SELECT p.id,s.id,p.started_at,TRUE,TRUE,TRUE,2,6 FROM practice_sessions p JOIN app_users u ON u.id=p.user_id
                JOIN lesson_sentences s ON s.lesson_id=p.lesson_id
                WHERE u.email LIKE 'capacity-%@loadtest.local' AND p.learner_id LIKE 'capacity-fixture-user-%'
                ON CONFLICT(practice_session_id,sentence_id) DO NOTHING
                """);
        var tokenizer=new com.dictalab.service.TranscriptTokenizer();
        var wordCounts=db.queryForList("SELECT id,transcript FROM lesson_sentences").stream()
                .map(row->new Object[]{tokenizer.words((String)row.get("transcript")).size(),row.get("id")}).toList();
        db.batchUpdate("""
                INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,
                    final_correct,last_answer,revealed,reveal_type)
                SELECT a.id,n,1,FALSE,FALSE,'fixture',TRUE,'REVEAL_SENTENCE'
                FROM sentence_attempts a JOIN practice_sessions p ON p.id=a.practice_session_id
                JOIN app_users u ON u.id=p.user_id CROSS JOIN generate_series(0,?-1) n
                WHERE u.email LIKE 'capacity-%@loadtest.local'
                AND p.learner_id LIKE 'capacity-fixture-user-%' AND a.sentence_id=?
                ON CONFLICT(sentence_attempt_id,word_index) DO NOTHING
                """,wordCounts);
        db.update("""
                INSERT INTO word_attempt_events(word_attempt_id,attempt_number,raw_answer,normalized_answer,correct,response_time_ms,created_at)
                SELECT w.id,1,'fixture','fixture',FALSE,1000,a.started_at FROM word_attempts w
                JOIN sentence_attempts a ON a.id=w.sentence_attempt_id JOIN practice_sessions p ON p.id=a.practice_session_id
                JOIN app_users u ON u.id=p.user_id WHERE u.email LIKE 'capacity-%@loadtest.local'
                AND p.learner_id LIKE 'capacity-fixture-user-%'
                ON CONFLICT(word_attempt_id,attempt_number) DO NOTHING
                """);
        db.update("""
                UPDATE practice_sessions p SET total_practice_seconds=a.seconds,total_replays=a.replays,
                    reveal_count=a.reveals,current_sentence_position=a.position
                FROM (SELECT a.practice_session_id,SUM(a.time_spent_seconds) seconds,SUM(a.replay_count) replays,
                    COUNT(*) reveals,MAX(s.position_index) position FROM sentence_attempts a
                    JOIN lesson_sentences s ON s.id=a.sentence_id GROUP BY a.practice_session_id) a
                WHERE p.id=a.practice_session_id AND p.learner_id LIKE 'capacity-fixture-user-%'
                """);
        db.execute("ANALYZE");
    }
}
