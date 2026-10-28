package com.dictalab.progress;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class ProgressStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Validator validator;

    public ProgressStore(JdbcTemplate db, ObjectMapper json, Validator validator) {
        this.db = db; this.json = json; this.validator = validator;
    }

    @Transactional(timeout=10)
    public String apply(ProgressEvent event) {
        if (event == null || !validator.validate(event).isEmpty())
            throw new IllegalArgumentException("Invalid progress event");
        db.update("INSERT INTO progress_users(user_id) VALUES (?) ON CONFLICT DO NOTHING", event.userId());
        boolean deleted = db.queryForObject("SELECT deleted FROM progress_users WHERE user_id=? FOR UPDATE",
                Boolean.class, event.userId());
        if (deleted) return "deleted";
        if (event.type().equals("UserDeleted.v1")) {
            db.update("DELETE FROM progress_sessions WHERE user_id=?", event.userId());
            db.update("UPDATE progress_users SET deleted=TRUE,updated_at=CURRENT_TIMESTAMP,last_event_at=? WHERE user_id=?",
                    Timestamp.from(event.occurredAt()), event.userId());
            return "deleted";
        }
        var s = event.session();
        String id = s.sessionId().toString();
        String hash = sha256(json.writeValueAsString(s));
        var existing = db.queryForList("SELECT user_id,revision,payload_hash FROM progress_sessions WHERE session_id=?", id);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (((Number) row.get("user_id")).longValue() != event.userId())
                throw new IllegalArgumentException("Progress session owner conflict");
            long revision = ((Number) row.get("revision")).longValue();
            if (revision > s.revision()) return "stale";
            if (revision == s.revision()) {
                if (!hash.equals(row.get("payload_hash")))
                    throw new IllegalArgumentException("Progress revision conflict");
                return "duplicate";
            }
            db.update("DELETE FROM progress_sessions WHERE session_id=?", id);
        }
        db.update("""
                INSERT INTO progress_sessions(session_id,user_id,revision,payload_hash,lesson_id,lesson_title,level,
                    completed_at,completed_date,practice_seconds,first_accuracy,final_accuracy,score,known_missed,unknown_words)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, event.userId(), s.revision(), hash, s.lessonId(), s.lessonTitle(), s.level(),
                Timestamp.from(s.completedAt()), java.sql.Date.valueOf(s.completedDate()), s.practiceSeconds(),
                s.firstAccuracy(), s.finalAccuracy(), s.score(), s.knownMissed(), s.unknownWords());
        db.update("""
                UPDATE progress_users SET updated_at=CURRENT_TIMESTAMP,
                    last_event_at=CASE WHEN last_event_at IS NULL OR last_event_at<? THEN ? ELSE last_event_at END
                WHERE user_id=?
                """, Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()), event.userId());
        return "applied";
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=5)
    public Projection dashboard(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid progress user");
        var rows = db.queryForList("""
                SELECT level,COUNT(*) AS lessons,COALESCE(SUM(practice_seconds),0) AS seconds,
                    SUM(first_accuracy) AS first_sum,SUM(final_accuracy) AS final_sum,SUM(score) AS score_sum,
                    SUM(known_missed) AS known_sum,SUM(unknown_words) AS unknown_sum
                FROM progress_sessions WHERE user_id=? GROUP BY level
                """, userId);
        long count = 0, seconds = 0, known = 0, unknown = 0;
        double first = 0, last = 0, score = 0;
        var levels = new HashMap<String, LevelProgress>();
        for (var row : rows) {
            long n = number(row, "lessons").longValue();
            count += n; seconds += number(row, "seconds").longValue();
            first += number(row, "first_sum").doubleValue(); last += number(row, "final_sum").doubleValue();
            score += number(row, "score_sum").doubleValue(); known += number(row, "known_sum").longValue();
            unknown += number(row, "unknown_sum").longValue();
            String level = (String) row.get("level");
            levels.put(level, new LevelProgress(level, Math.toIntExact(n),
                    average(number(row, "first_sum").doubleValue(), n), average(number(row, "score_sum").doubleValue(), n)));
        }
        var summary = new Summary(Math.toIntExact(count), Math.toIntExact(Math.round(seconds / 60.0)),
                average(first, count), average(last, count), average(score, count));
        var trend = new ArrayList<>(db.query("""
                SELECT session_id,completed_date,level,lesson_title,first_accuracy,final_accuracy,score
                FROM progress_sessions WHERE user_id=? ORDER BY completed_at DESC,session_id DESC LIMIT 8
                """, (r, index) -> new Trend(r.getString(1), r.getDate(2).toLocalDate(), r.getString(3),
                r.getString(4), r.getDouble(5), r.getDouble(6), r.getDouble(7)), userId));
        Collections.reverse(trend);
        var byLevel = List.of("A1", "A2", "B1", "B2").stream()
                .map(level -> levels.getOrDefault(level, new LevelProgress(level, 0, 0, 0))).toList();
        var metadata = db.queryForList("SELECT updated_at,last_event_at FROM progress_users WHERE user_id=?", userId);
        Instant updated = metadata.isEmpty() ? null : instant(metadata.get(0).get("updated_at"));
        Instant lastEvent = metadata.isEmpty() ? null : instant(metadata.get(0).get("last_event_at"));
        return new Projection(summary, trend, byLevel, new Errors(Math.toIntExact(known), Math.toIntExact(unknown)), updated, lastEvent);
    }

    private Number number(Map<String,Object> row, String key) { return (Number) row.get(key); }
    private double average(double sum, long count) { return count == 0 ? 0 : Math.round(sum / count * 10.0) / 10.0; }
    private Instant instant(Object value) {
        if (value == null) return null;
        return value instanceof Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) value).toInstant();
    }
    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Summary(int completedLessons, int learningMinutes, double averageFirstAttemptAccuracy,
            double averageFinalAccuracy, double averageScore) {}
    public record Trend(String sessionId, LocalDate date, String level, String lessonTitle,
            double firstAttemptAccuracy, double finalAccuracy, double overallScore) {}
    public record LevelProgress(String level, int completedLessons, double averageFirstAttemptAccuracy, double averageScore) {}
    public record Errors(int knownButMissed, int unknownWords) {}
    public record Projection(Summary summary, List<Trend> trend, List<LevelProgress> byLevel, Errors errors,
            Instant updatedAt, Instant lastEventAt) {}
}
