package com.dictalab.service;

import com.dictalab.domain.PracticeSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Domain data and its event commit together. No broker call runs in a practice transaction. */
@Service
public class ProgressOutbox {
    public static final String QUEUE = "dictalab.progress.v1";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final TransactionTemplate tx;
    private final jakarta.persistence.EntityManager entities;

    public ProgressOutbox(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager,
            jakarta.persistence.EntityManager entities,
            @Value("${dictalab.progress.enabled:false}") boolean enabled) {
        this.db = db; this.json = json; this.enabled = enabled;
        this.entities = entities;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(10);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void completed(PracticeSession session) {
        if (!enabled || session.getRoomId() != null) return;
        // Serialize persisted precision, not the JVM nanoseconds rounded by PostgreSQL/H2.
        entities.flush();
        export(session.getId());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void userDeleted(long userId) {
        // Scrub retained event bodies before queuing a metadata-only tombstone.
        db.update("DELETE FROM progress_outbox WHERE user_id=?", userId);
        write("UserDeleted.v1", "user-deleted:" + userId, userId, null);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void refreshLesson(long lessonId) {
        if (!enabled) return;
        db.update("INSERT INTO progress_lesson_refresh(lesson_id) VALUES (?) ON CONFLICT DO NOTHING", lessonId);
        db.update("UPDATE progress_lesson_refresh SET cursor_id='' WHERE lesson_id=?", lessonId);
    }

    @org.springframework.scheduling.annotation.Scheduled(initialDelayString="${dictalab.progress.dispatch-delay-ms:5000}",
            fixedDelayString="${dictalab.progress.dispatch-delay-ms:5000}")
    public void refreshChangedLesson() {
        if (!enabled) return;
        var lessons = db.queryForList("SELECT lesson_id FROM progress_lesson_refresh ORDER BY lesson_id LIMIT 1", Long.class);
        if (lessons.isEmpty()) return;
        long lesson = lessons.get(0);
        tx.executeWithoutResult(status -> {
            var cursors = db.queryForList("SELECT cursor_id FROM progress_lesson_refresh WHERE lesson_id=? FOR UPDATE", String.class, lesson);
            if (cursors.isEmpty()) return;
            var ids = db.queryForList("""
                    SELECT id FROM practice_sessions WHERE lesson_id=? AND id>? AND status='COMPLETED' AND room_id IS NULL
                    ORDER BY id LIMIT 50
                    """, String.class, lesson, cursors.get(0));
            for (String id : ids) export(id);
            if (ids.size() < 50) db.update("DELETE FROM progress_lesson_refresh WHERE lesson_id=?", lesson);
            else db.update("UPDATE progress_lesson_refresh SET cursor_id=? WHERE lesson_id=?", ids.get(ids.size()-1), lesson);
        });
    }

    public Backfill backfill(String after, int limit) {
        if (!enabled) throw new IllegalStateException("Progress publishing is disabled");
        if (after == null || !(after.isEmpty() || UUID.fromString(after).toString().equals(after)) || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid progress backfill cursor or batch size");
        List<String> ids = db.queryForList("""
                SELECT id FROM practice_sessions WHERE id>? AND status='COMPLETED' AND room_id IS NULL
                ORDER BY id LIMIT ?
                """, String.class, after, limit);
        int exported = 0;
        for (String id : ids) {
            Boolean wrote = tx.execute(status -> export(id));
            if (Boolean.TRUE.equals(wrote)) exported++;
        }
        return new Backfill(ids.isEmpty() ? after : ids.get(ids.size() - 1), ids.size(), exported, ids.size() < limit);
    }

    private boolean export(String id) {
        var owners = db.queryForList("SELECT user_id FROM practice_sessions WHERE id=?", Long.class, id);
        if (owners.isEmpty()) return false;
        long user = owners.get(0);
        // Account deletion and practice mutations use the same owner lock.
        if (db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, user).isEmpty()) return false;
        var snapshots = db.query("""
                SELECT s.id,l.id,l.title,l.level,s.completed_at,s.total_practice_seconds,
                    s.first_attempt_accuracy,s.final_accuracy,s.overall_score,s.known_missed_count,s.unknown_count
                FROM practice_sessions s JOIN lessons l ON l.id=s.lesson_id
                WHERE s.id=? AND s.user_id=? AND s.status='COMPLETED' AND s.room_id IS NULL
                """, (r, index) -> {
            Instant completed = r.getTimestamp(5).toInstant();
            return new Session(UUID.fromString(r.getString(1)), 0, r.getLong(2), r.getString(3),
                    r.getString(4), completed, completed.atZone(ZoneId.systemDefault()).toLocalDate(),
                    r.getInt(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getInt(10), r.getInt(11));
        }, id, user);
        if (snapshots.isEmpty()) return false;
        enqueue(user, snapshots.get(0));
        return true;
    }

    private void enqueue(long userId, Session data) {
        String id = data.sessionId().toString();
        String hash = AiBackgroundJobs.hash(json.writeValueAsString(data));
        db.update("INSERT INTO progress_projection_versions(session_id,snapshot_hash) VALUES (?,'') ON CONFLICT DO NOTHING", id);
        var current = db.queryForMap("SELECT revision,snapshot_hash FROM progress_projection_versions WHERE session_id=? FOR UPDATE", id);
        long revision = ((Number) current.get("revision")).longValue();
        if (!hash.equals(current.get("snapshot_hash"))) {
            revision = Math.addExact(revision, 1);
            db.update("UPDATE progress_projection_versions SET revision=?,snapshot_hash=? WHERE session_id=?", revision, hash, id);
        }
        var snapshot = new Session(data.sessionId(), revision, data.lessonId(), data.lessonTitle(), data.level(),
                data.completedAt(), data.completedDate(), data.practiceSeconds(), data.firstAccuracy(), data.finalAccuracy(),
                data.score(), data.knownMissed(), data.unknownWords());
        write("PracticeCompleted.v1", "practice:" + id + ":" + revision, userId, snapshot);
    }

    private void write(String type, String key, long userId, Session data) {
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        String payload = json.writeValueAsString(new Event(1, id, type, userId, Instant.now(), data));
        if (payload.getBytes(StandardCharsets.UTF_8).length > 64000) throw new IllegalArgumentException("Progress event exceeds limit");
        db.update("""
                INSERT INTO progress_outbox(id,dedup_key,user_id,event_type,payload)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, id.toString(), key, userId, type, payload);
    }

    public record Event(int schemaVersion, UUID eventId, String type, long userId, Instant occurredAt, Session session) {}
    public record Session(UUID sessionId, long revision, long lessonId, String lessonTitle, String level,
            Instant completedAt, java.time.LocalDate completedDate, int practiceSeconds,
            double firstAccuracy, double finalAccuracy, double score, int knownMissed, int unknownWords) {}
    public record Backfill(String nextCursor, int scanned, int exported, boolean done) {}
}
