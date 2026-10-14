package com.dictalab.service;

import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ListeningLearning {
    private final JdbcTemplate db;
    private final TranscriptTokenizer tokenizer;
    private final ListeningErrorClassifier classifier;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final String publicationLock;
    private static final int[] INTERVAL_DAYS = {1,3,7,14,30};

    public ListeningLearning(JdbcTemplate db, TranscriptTokenizer tokenizer, ListeningErrorClassifier classifier,
                             ObjectMapper json, PlatformTransactionManager manager,
                             @Value("${dictalab.learning.enabled:false}") boolean enabled) {
        this.db=db; this.tokenizer=tokenizer; this.classifier=classifier; this.json=json;
        this.transaction=new TransactionTemplate(manager); this.transaction.setTimeout(20); this.enabled=enabled;
        this.publicationLock=db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName()) ? " FOR SHARE" : " FOR UPDATE");
    }

    public record Challenge(String id, String prompt, String audioUrl, Double audioStartSeconds,
                            Double audioEndSeconds, boolean newContext) {}
    public record ReviewResult(boolean correct, boolean assisted, String expected, int stage, Instant dueAt,
                               boolean newContext) {}

    private void requireEnabled() {
        if (!enabled) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Ôn nghe đang được chuẩn bị.");
    }

    /** Caller owns user -> practice session -> job locks; all first evidence is immutable after completion. */
    public void collect(long attempt) {
        var rows = db.queryForList("""
                SELECT w.id,w.word_index,w.first_answer,w.first_attempt_correct,w.first_attempt_assisted,w.learner_reported_error_type,
                    a.completed,s.user_id,s.first_exposure,s.room_id,t.transcript,t.lesson_id
                FROM sentence_attempts a JOIN practice_sessions s ON s.id=a.practice_session_id
                JOIN lesson_sentences t ON t.id=a.sentence_id JOIN word_attempts w ON w.sentence_attempt_id=a.id
                WHERE a.id=? ORDER BY w.word_index
                """, attempt);
        if (rows.isEmpty()) return;
        if (!Boolean.TRUE.equals(rows.get(0).get("completed"))) throw new IllegalStateException("Incomplete source");
        var words = tokenizer.words((String) rows.get(0).get("transcript"));
        var properNames = new HashSet<>(db.queryForList("SELECT word FROM lesson_preview_words WHERE lesson_id=? AND proper_name=TRUE",
                String.class, rows.get(0).get("lesson_id")).stream().map(tokenizer::normalize).toList());
        var existingEvidence=new HashSet<>(db.queryForList("SELECT e.word_attempt_id FROM listening_error_evidence e JOIN word_attempts w ON w.id=e.word_attempt_id WHERE w.sentence_attempt_id=?",Long.class,attempt));
        var existingTargets=new HashSet<>(db.queryForList("SELECT target FROM listening_review_cards WHERE user_id=?",String.class,rows.get(0).get("user_id")));
        var normalizedWords=words.stream().map(tokenizer::normalize).toList();
        List<Object[]> evidence=new ArrayList<>(),cards=new ArrayList<>();
        for (var word : rows) {
            long wordId = ((Number) word.get("id")).longValue();
            int index = ((Number) word.get("word_index")).intValue();
            String expected = words.get(index), target = tokenizer.normalize(expected);
            if (properNames.contains(target) || target.length()>200) continue;
            if (existingEvidence.contains(wordId)) continue;
            String answer=(String) word.get("first_answer");
            boolean correct=Boolean.TRUE.equals(word.get("first_attempt_correct"));
            boolean comparable=answer!=null && Boolean.TRUE.equals(word.get("first_exposure"))
                    && word.get("room_id")==null && !Boolean.TRUE.equals(word.get("first_attempt_assisted"));
            String observation=answer==null ? "NO_ANSWER" : correct ? "CORRECT" : classifier.classify(target,
                    tokenizer.normalize(answer), normalizedWords, index).name();
            // Text distance and function-word/contraction membership do not establish an acoustic cause.
            String pattern=correct || answer==null ? null : target.contains("'") ? "CONTRACTION_POSSIBLE"
                    : tokenizer.isFunctionWord(target) ? "WEAK_FORM_POSSIBLE" : null;
            evidence.add(new Object[]{wordId, word.get("user_id"), target, expected, answer, observation, pattern, word.get("learner_reported_error_type"), comparable, correct});
            if (!correct && existingTargets.add(target)) cards.add(new Object[]{UUID.randomUUID().toString(),word.get("user_id"),target,wordId});
        }
        if (!evidence.isEmpty()) db.batchUpdate("INSERT INTO listening_error_evidence(word_attempt_id,user_id,target,expected,heard,observation,possible_pattern,learner_reported_error_type,comparable,first_correct) VALUES (?,?,?,?,?,?,?,?,?,?)",evidence);
        if (!cards.isEmpty()) db.batchUpdate("INSERT INTO listening_review_cards(id,user_id,target,source_word_id) VALUES (?,?,?,?)",cards);
    }

    @Scheduled(initialDelayString="${dictalab.learning.index-delay-ms:10000}", fixedDelayString="${dictalab.learning.index-delay-ms:10000}")
    public void indexSources() {
        if (!enabled) return;
        for (Long sentence : db.queryForList("""
                SELECT s.id FROM lesson_sentences s JOIN lessons l ON l.id=s.lesson_id
                LEFT JOIN listening_review_sources x ON x.sentence_id=s.id
                WHERE l.active=TRUE AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL' AND s.audio_url IS NOT NULL
                    AND (x.sentence_id IS NULL OR x.transcript<>s.transcript)
                ORDER BY s.id LIMIT 20
                """, Long.class)) {
            transaction.executeWithoutResult(status -> {
                var rows=db.queryForList("SELECT transcript FROM lesson_sentences WHERE id=? FOR UPDATE", sentence);
                if (rows.isEmpty()) return;
                String transcript=(String) rows.get(0).get("transcript");
                var existing=db.queryForList("SELECT transcript FROM listening_review_sources WHERE sentence_id=?", String.class, sentence);
                if (!existing.isEmpty() && existing.get(0).equals(transcript)) return;
                if (!existing.isEmpty()) db.update("DELETE FROM listening_review_sources WHERE sentence_id=?", sentence);
                db.update("INSERT INTO listening_review_sources(sentence_id,transcript,transcript_hash) VALUES (?,?,?)", sentence, transcript, AiBackgroundJobs.hash(transcript));
                var words=tokenizer.words(transcript);
                List<Object[]> tokens=new ArrayList<>();
                for (int i=0;i<words.size();i++) {
                    String target=tokenizer.normalize(words.get(i));
                    if (target.length()<=200) tokens.add(new Object[]{sentence,i,target});
                }
                if (!tokens.isEmpty()) db.batchUpdate("INSERT INTO listening_review_tokens(sentence_id,word_index,target) VALUES (?,?,?)",tokens);
            });
        }
    }

    @Transactional(readOnly=true)
    public Map<String,Object> bank(long user) {
        requireEnabled();
        var items=db.queryForList("""
                SELECT e.word_attempt_id AS id,e.target,e.expected,e.heard,e.observation,e.possible_pattern,e.learner_reported_error_type,
                    e.comparable,e.created_at,w.word_index,a.practice_session_id AS session_id,
                    t.id AS sentence_id,t.transcript,t.audio_url,t.audio_start_seconds,t.audio_end_seconds
                FROM listening_error_evidence e JOIN word_attempts w ON w.id=e.word_attempt_id
                JOIN sentence_attempts a ON a.id=w.sentence_attempt_id JOIN lesson_sentences t ON t.id=a.sentence_id
                WHERE e.user_id=? AND e.first_correct=FALSE ORDER BY e.created_at DESC,e.word_attempt_id DESC LIMIT 100
                """, user);
        var targets=db.queryForList("""
                SELECT e.target,COUNT(*) AS observations,
                    SUM(CASE WHEN e.first_correct=FALSE THEN 1 ELSE 0 END) AS errors,
                    SUM(CASE WHEN e.comparable THEN 1 ELSE 0 END) AS comparable_opportunities,
                    SUM(CASE WHEN e.comparable AND e.first_correct=FALSE THEN 1 ELSE 0 END) AS comparable_errors
                FROM listening_error_evidence e WHERE e.user_id=? GROUP BY e.target
                HAVING SUM(CASE WHEN e.first_correct=FALSE THEN 1 ELSE 0 END)>0
                ORDER BY errors DESC,e.target LIMIT 100
                """,user);
        return Map.of("items",items,"targets",targets,"due",db.queryForObject(
                "SELECT COUNT(*) FROM listening_review_cards WHERE user_id=? AND due_at<=CURRENT_TIMESTAMP",Integer.class,user),
                "pending",db.queryForObject("SELECT COUNT(*) FROM learning_jobs j JOIN sentence_attempts a ON a.id=j.attempt_id JOIN practice_sessions s ON s.id=a.practice_session_id WHERE s.user_id=? AND j.status IN ('PENDING','RUNNING')",Integer.class,user),
                "failed",db.queryForObject("SELECT COUNT(*) FROM learning_jobs j JOIN sentence_attempts a ON a.id=j.attempt_id JOIN practice_sessions s ON s.id=a.practice_session_id WHERE s.user_id=? AND j.status='FAILED'",Integer.class,user),
                "progress",db.queryForList("SELECT c.target,c.stage,c.due_at,COUNT(r.completed_at) AS completed_reviews FROM listening_review_cards c LEFT JOIN listening_review_attempts r ON r.card_id=c.id WHERE c.user_id=? GROUP BY c.target,c.stage,c.due_at ORDER BY c.due_at,c.target LIMIT 100",user));
    }

    @Transactional(timeout=20)
    public List<Challenge> startReview(long user) {
        requireEnabled(); lockUser(user);
        var cards=db.queryForList("SELECT * FROM listening_review_cards WHERE user_id=? AND due_at<=CURRENT_TIMESTAMP ORDER BY due_at,id LIMIT 5 FOR UPDATE",user);
        List<Challenge> results=new ArrayList<>();
        for (var card:cards) {
            var existing=db.queryForList("SELECT * FROM listening_review_attempts WHERE card_id=? AND card_revision=? AND completed_at IS NULL",card.get("id"),card.get("revision"));
            if (!existing.isEmpty()) { results.add(toChallenge(existing.get(0))); continue; }
            var source=db.queryForMap("SELECT s.id,s.audio_url,l.level FROM word_attempts w JOIN sentence_attempts a ON a.id=w.sentence_attempt_id JOIN lesson_sentences s ON s.id=a.sentence_id JOIN lessons l ON l.id=s.lesson_id WHERE w.id=?",card.get("source_word_id"));
            var candidates=db.queryForList("""
                    SELECT s.*,t.word_index FROM listening_review_tokens t JOIN lesson_sentences s ON s.id=t.sentence_id
                    JOIN listening_review_sources x ON x.sentence_id=s.id JOIN lessons l ON l.id=s.lesson_id
                    WHERE t.target=? AND x.transcript=s.transcript AND l.active=TRUE AND l.content_origin<>'BRITISH_COUNCIL_PERSONAL'
                        AND l.level=? AND s.audio_url IS NOT NULL
                    ORDER BY CASE WHEN s.id<>? AND s.audio_url<>?
                        AND NOT EXISTS (SELECT 1 FROM audio_exposure_events e WHERE e.user_id=? AND e.audio_url=s.audio_url)
                        AND NOT EXISTS (SELECT 1 FROM listening_review_attempts r WHERE r.card_id=? AND r.sentence_id=s.id)
                        THEN 0 ELSE 1 END,
                        CASE WHEN s.id<>? OR CAST(? AS BIGINT) IS NULL THEN 0 ELSE 1 END,s.id,t.word_index LIMIT 1
                    """,card.get("target"),source.get("level"),source.get("id"),source.get("audio_url"),user,card.get("id"),card.get("last_sentence_id"),card.get("last_sentence_id"));
            if (candidates.isEmpty()) continue;
            var candidate=candidates.get(0);
            // Readers can coexist across lessons; publication/content writers must still wait until commit.
            var active=db.queryForList("SELECT id FROM lessons WHERE id=? AND active=TRUE AND content_origin<>'BRITISH_COUNCIL_PERSONAL'"+publicationLock,Long.class,candidate.get("lesson_id"));
            if (active.isEmpty()) continue;
            var current=db.queryForMap("SELECT transcript,audio_url FROM lesson_sentences WHERE id=?",candidate.get("id"));
            if (!Objects.equals(current.get("transcript"),candidate.get("transcript")) || !Objects.equals(current.get("audio_url"),candidate.get("audio_url"))) continue;
            boolean newContext=!Objects.equals(candidate.get("id"),source.get("id")) && !Objects.equals(candidate.get("audio_url"),source.get("audio_url"));
            // A previously served context tests retention, not new-context transfer.
            if (db.queryForObject("SELECT COUNT(*) FROM listening_review_attempts WHERE card_id=? AND sentence_id=?",Integer.class,card.get("id"),candidate.get("id"))>0) newContext=false;
            if (db.queryForObject("SELECT COUNT(*) FROM audio_exposure_events WHERE user_id=? AND audio_url=?",Integer.class,user,candidate.get("audio_url"))>0) newContext=false;
            String id=UUID.randomUUID().toString();
            int index=((Number)candidate.get("word_index")).intValue();
            String expected=tokenizer.words((String)candidate.get("transcript")).get(index);
            db.update("INSERT INTO listening_review_attempts(id,card_id,sentence_id,card_revision,word_index,expected,transcript,audio_url,audio_start_seconds,audio_end_seconds,new_context) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id,card.get("id"),candidate.get("id"),card.get("revision"),index,expected,candidate.get("transcript"),candidate.get("audio_url"),candidate.get("audio_start_seconds"),candidate.get("audio_end_seconds"),newContext);
            // Serving audio is conservatively exposure, including abandoned reviews.
            db.update("INSERT INTO audio_exposure_events(user_id,audio_url,source) VALUES (?,?,'SPACED_REVIEW')",user,candidate.get("audio_url"));
            results.add(toChallenge(db.queryForMap("SELECT * FROM listening_review_attempts WHERE id=?",id)));
        }
        return results;
    }

    private Challenge toChallenge(Map<String,Object> row) {
        var tokens=tokenizer.tokens((String)row.get("transcript"));
        int target=((Number)row.get("word_index")).intValue();
        StringJoiner prompt=new StringJoiner(" ");
        for (int i=0;i<tokens.size();i++) {
            var token=tokens.get(i);
            prompt.add(token.punctuationBefore() + (i==target ? "_____" : token.word()) + token.punctuationAfter());
        }
        return new Challenge((String)row.get("id"),prompt.toString(),(String)row.get("audio_url"),
                decimal(row.get("audio_start_seconds")),decimal(row.get("audio_end_seconds")),Boolean.TRUE.equals(row.get("new_context")));
    }

    public record ReviewState(Challenge challenge, ReviewResult result) {}

    @Transactional(readOnly=true)
    public ReviewState review(long user, String id) {
        requireEnabled();
        var rows=db.queryForList("SELECT r.* FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE r.id=? AND c.user_id=?",id,user);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy bài ôn.");
        var row=rows.get(0);
        return new ReviewState(toChallenge(row), row.get("result_json")==null ? null
                : json.readValue((String)row.get("result_json"),ReviewResult.class));
    }

    @Transactional(timeout=20)
    public void played(long user,String id) {
        requireEnabled(); lockUser(user);
        if (db.update("UPDATE listening_review_attempts SET played=TRUE WHERE id=? AND completed_at IS NULL AND card_id IN (SELECT id FROM listening_review_cards WHERE user_id=?)",id,user)==0)
            throw new ResourceNotFoundException("Không tìm thấy bài ôn đang làm.");
    }

    @Transactional(timeout=20)
    public ReviewResult answer(long user,String id,String answer,boolean reveal) {
        requireEnabled(); lockUser(user);
        var attempts=db.queryForList("SELECT r.* FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE r.id=? AND c.user_id=? FOR UPDATE",id,user);
        if (attempts.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy bài ôn.");
        var attempt=attempts.get(0);
        String hash=AiBackgroundJobs.hash(json.writeValueAsString(List.of(answer,reveal)));
        if (attempt.get("result_json")!=null) {
            if (!hash.equals(attempt.get("request_hash"))) throw new InvalidPracticeStateException("Lượt ôn đã được chấm. Hãy xem kết quả đã lưu.");
            return json.readValue((String)attempt.get("result_json"),ReviewResult.class);
        }
        if (!Boolean.TRUE.equals(attempt.get("played"))) throw new InvalidPracticeStateException("Hãy nghe audio trước khi nộp.");
        var card=db.queryForMap("SELECT * FROM listening_review_cards WHERE id=? FOR UPDATE",attempt.get("card_id"));
        if (!Objects.equals(card.get("revision"),attempt.get("card_revision"))) throw new InvalidPracticeStateException("Lịch ôn đã thay đổi. Hãy tải lại.");
        boolean correct=!reveal && tokenizer.normalize((String)attempt.get("expected")).equals(tokenizer.normalize(answer));
        int stage=((Number)card.get("stage")).intValue();
        Instant now=Instant.now();
        Instant due;
        if (!correct || reveal) { stage=0; due=now.plusSeconds(600); }
        else {
            due=now.plusSeconds(86400L * INTERVAL_DAYS[stage]);
            if (Boolean.TRUE.equals(attempt.get("new_context"))) stage=Math.min(4,stage+1);
        }
        var result=new ReviewResult(correct,reveal,(String)attempt.get("expected"),stage,due,Boolean.TRUE.equals(attempt.get("new_context")));
        db.update("UPDATE listening_review_cards SET stage=?,due_at=?,revision=revision+1,last_sentence_id=? WHERE id=? AND revision=?",
                stage,Timestamp.from(due),attempt.get("sentence_id"),card.get("id"),card.get("revision"));
        db.update("UPDATE listening_review_attempts SET request_hash=?,result_json=?,completed_at=? WHERE id=?",hash,json.writeValueAsString(result),Timestamp.from(now),id);
        return result;
    }

    private void lockUser(long user) {
        if (db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",Long.class,user).isEmpty())
            throw new ResourceNotFoundException("Không tìm thấy tài khoản.");
    }
    private static Double decimal(Object value) { return value==null ? null : ((Number)value).doubleValue(); }
}
