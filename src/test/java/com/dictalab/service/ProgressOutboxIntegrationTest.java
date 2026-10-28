package com.dictalab.service;

import com.dictalab.domain.*;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"dictalab.progress.enabled=true", "dictalab.progress.dispatch-delay-ms=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class ProgressOutboxIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProgressOutbox outbox;
    @Autowired ProgressPublisher publisher;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppUserRepository users;
    @Autowired LessonRepository lessons;
    @Autowired PracticeSessionRepository sessions;
    @Autowired PracticeService practice;
    @Autowired TranscriptTokenizer tokenizer;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;
    AppUser user;

    @BeforeEach void fixture() {
        user = users.save(new AppUser(UUID.randomUUID()+"@progress.test", "Progress learner", "unused", Instant.now()));
        db.update("UPDATE lessons SET active=TRUE WHERE id=1");
    }
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    int events() { return db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE user_id=?", Integer.class, user.getId()); }
    void backfill() {
        String cursor = "";
        for (int i=0; i<100; i++) {
            var batch = outbox.backfill(cursor, 100);
            if (batch.done()) return;
            cursor = batch.nextCursor();
        }
        throw new AssertionError("Backfill did not terminate");
    }
    PracticeSession completed() {
        return tx().execute(status -> {
            var session = new PracticeSession(users.findById(user.getId()).orElseThrow(), lessons.findById(1L).orElseThrow(), Instant.now(), true);
            session.complete(Instant.parse("2026-10-27T09:00:00Z"), 60, 75, 95, 2, 1, 80);
            return sessions.saveAndFlush(session);
        });
    }

    @Test void eventAndCompletionRollbackTogether() {
        String[] id = new String[1];
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            var session = new PracticeSession(users.findById(user.getId()).orElseThrow(), lessons.findById(1L).orElseThrow(), Instant.now(), true);
            session = sessions.saveAndFlush(session);
            session.complete(Instant.now(), 60, 75, 95, 2, 1, 80);
            outbox.completed(session);
            em.flush(); em.clear();
            id[0] = session.getId();
            assertThat(events()).isEqualTo(1);
            throw new IllegalStateException("injected before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(sessions.findById(id[0])).isEmpty();
        assertThat(events()).isZero();
    }

    @Test void boundedBackfillIsIdempotentAndMetadataRefreshAdvancesRevision() {
        var session = completed();
        backfill(); backfill();
        assertThat(events()).isEqualTo(1);
        var first = json.readTree(db.queryForObject("SELECT payload FROM progress_outbox WHERE user_id=?", String.class, user.getId()));
        assertThat(first.path("session").path("revision").asLong()).isEqualTo(1);
        String title = db.queryForObject("SELECT title FROM lessons WHERE id=1", String.class);
        try {
            tx().executeWithoutResult(status -> {
                db.update("UPDATE lessons SET title='Renamed progress fixture' WHERE id=1");
                outbox.refreshLesson(1L);
            });
            for (int i=0; i<100 && db.queryForObject("SELECT COUNT(*) FROM progress_lesson_refresh", Integer.class)>0; i++)
                outbox.refreshChangedLesson();
            assertThat(events()).isEqualTo(2);
            assertThat(db.queryForObject("SELECT revision FROM progress_projection_versions WHERE session_id=?", Long.class, session.getId()))
                    .isEqualTo(2);
            backfill();
            assertThat(events()).isEqualTo(2);
        } finally { db.update("UPDATE lessons SET title=? WHERE id=1", title); }
    }

    @Test void accountDeletionScrubsPayloadsAndRetainsOnlyTombstone() {
        completed(); backfill();
        tx().executeWithoutResult(status -> {
            outbox.userDeleted(user.getId());
            db.update("DELETE FROM practice_sessions WHERE user_id=?", user.getId());
            db.update("DELETE FROM app_users WHERE id=?", user.getId());
        });
        assertThat(events()).isEqualTo(1);
        var event = json.readTree(db.queryForObject("SELECT payload FROM progress_outbox WHERE user_id=?", String.class, user.getId()));
        assertThat(event.path("type").asString()).isEqualTo("UserDeleted.v1");
        assertThat(event.path("session").isNull()).isTrue();
        assertThat(event.toString()).doesNotContain("lessonTitle", user.getEmail());
    }

    @Test void sharedRoomCompletionDoesNotEnterSoloDashboard() {
        var session = completed();
        db.update("UPDATE practice_sessions SET room_id=? WHERE id=?", UUID.randomUUID().toString(), session.getId());
        backfill();
        assertThat(events()).isZero();
    }

    @Test void exhaustedPublicationCanBeExplicitlyReplayedOnce() {
        completed(); backfill();
        String id = db.queryForObject("SELECT id FROM progress_outbox WHERE user_id=?", String.class, user.getId());
        db.update("UPDATE progress_outbox SET status='FAILED',attempts=20 WHERE id=?", id);
        publisher.retry(id);
        assertThat(db.queryForObject("SELECT status FROM progress_outbox WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM progress_outbox WHERE id=?", Integer.class, id)).isZero();
        assertThatThrownBy(() -> publisher.retry(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void realPracticeCompletionQueuesItsPersistedScoreWithoutBroker() {
        var start = practice.start(user.getId(), new ApiDtos.StartSessionRequest(1L));
        var sentence = start.sentence();
        ApiDtos.CompleteSentenceResponse result = null;
        for (int i=0; i<128; i++) {
            practice.replay(user.getId(), start.sessionId(), sentence.sentenceId(), new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
            practice.replay(user.getId(), start.sessionId(), sentence.sentenceId(), new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED));
            var words = tokenizer.words(db.queryForObject("SELECT transcript FROM lesson_sentences WHERE id=?", String.class, sentence.sentenceId()));
            for (int word=0; word<words.size(); word++)
                practice.checkWord(user.getId(), start.sessionId(), sentence.sentenceId(), word, new ApiDtos.CheckWordRequest(words.get(word)));
            result = practice.completeSentence(user.getId(), start.sessionId(), sentence.sentenceId(), new ApiDtos.CompleteSentenceRequest(0));
            if (result.lessonCompleted()) break;
            sentence = result.nextSentence();
        }
        assertThat(result).isNotNull();
        assertThat(result.lessonCompleted()).isTrue();
        assertThat(events()).isEqualTo(1);
        var payload = json.readValue(db.queryForObject("SELECT payload FROM progress_outbox WHERE user_id=?", String.class, user.getId()), ProgressOutbox.Event.class);
        assertThat(payload.session().firstAccuracy()).isEqualTo(result.result().firstAttemptAccuracy());
        assertThat(payload.session().score()).isEqualTo(result.result().overallScore());
        assertThat(payload.session().sessionId().toString()).isEqualTo(start.sessionId());
        backfill();
        assertThat(events()).isEqualTo(1);
    }
}
