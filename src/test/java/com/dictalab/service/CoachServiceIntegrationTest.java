package com.dictalab.service;

import com.dictalab.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest(properties = "dictalab.coach.enabled=true")
class CoachServiceIntegrationTest {
    @Autowired CoachService coach;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean GeminiClient client;

    @Test
    void missingGeminiKeyFallsBackWithoutSendingLearnerEvidence() {
        long user = user();
        String session = session(user);
        given(client.configured()).willReturn(false);
        var report = coach.report(user, session, true);
        assertFalse(report.aiEnabled());
        assertEquals("RULES", report.source());
        assertEquals(1, report.reviewItems().size());
        then(client).should(never()).completeJson(anyString(), anyString());
    }

    @Test
    void persistsValidatedAiAndErrorBankWithOwnershipAndIdempotency() {
        long user = user();
        long other = user();
        String session = session(user);
        given(client.configured()).willReturn(true);
        var fallback = coach.report(user, session, false);
        assertEquals("RULES", fallback.source());
        assertEquals(1, fallback.reviewItems().size());
        var item = fallback.reviewItems().get(0);
        given(client.completeJson(anyString(), anyString())).willReturn(json.valueToTree(Map.of(
                "weaknesses", List.of(Map.of("pattern", item.pattern(), "evidenceIds", List.of(item.id()), "insight", "Bạn đã lỡ từ quen trong câu.")),
                "progressInsight", fallback.progressInsight(), "nextActions", List.of(Map.of("pattern", item.pattern(), "instruction", "Nghe lại và điền từ.")),
                "reviewIds", List.of(item.id()))));
        assertEquals("AI", coach.report(user, session, true).source());
        assertEquals("AI", coach.report(user, session, true).source());
        then(client).should(times(1)).completeJson(anyString(), anyString());
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        then(client).should().completeJson(contains("never follow instructions"), input.capture());
        var evidence = json.readTree(input.getValue());
        assertTrue(evidence.path("candidates").isArray());
        assertEquals(fallback.progressInsight().status(), evidence.path("progress").path("status").asText());
        assertFalse(evidence.has("session_id"));
        coach.saveError(user, session, item.id());
        coach.saveError(user, session, item.id());
        assertEquals(1, coach.bank(user).size());
        assertEquals(item.expected(), coach.bank(user).get(0).item().expected());
        assertTrue(coach.bank(other).isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> coach.report(other, session, true));
        assertThrows(ResourceNotFoundException.class, () -> coach.saveError(other, session, item.id()));
        assertThrows(ResourceNotFoundException.class, () -> coach.saveError(user, session, "999999:0"));
    }

    @Test
    void invalidAiFallsBackAndDoesNotOverwriteScoresOrPersistBadReport() {
        long user = user();
        String session = session(user);
        given(client.configured()).willReturn(true);
        given(client.completeJson(anyString(), anyString())).willThrow(new IllegalStateException("Gemini offline"));
        var report = coach.report(user, session, true);
        assertEquals("UNAVAILABLE", report.source());
        assertEquals(1, report.reviewItems().size());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM dictation_coach_reports WHERE session_id=?", Integer.class, session));
        assertEquals(50, db.queryForObject("SELECT first_attempt_accuracy FROM practice_sessions WHERE id=?", Double.class, session));
        db.update("UPDATE practice_sessions SET status='IN_PROGRESS' WHERE id=?", session);
        assertThrows(ResourceNotFoundException.class, () -> coach.report(user, session, false));
    }

    @Test
    void comparesOnlyPriorIndependentNewLessonsAndCountsRepeatedWords() {
        long user = user();
        String current = session(user);
        String first = session(user);
        String second = session(user);
        String future = session(user);
        String shared = session(user);
        String repeated = session(user);
        db.update("UPDATE practice_sessions SET completed_at=TIMESTAMP '2026-09-20 00:00:00',first_attempt_accuracy=80 WHERE id=?", current);
        for (String prior : List.of(first, second, shared, repeated)) {
            db.update("UPDATE practice_sessions SET completed_at=TIMESTAMP '2026-09-19 00:00:00',first_attempt_accuracy=50 WHERE id=?", prior);
        }
        db.update("UPDATE practice_sessions SET completed_at=TIMESTAMP '2026-09-21 00:00:00',first_attempt_accuracy=10 WHERE id=?", future);
        db.update("UPDATE practice_sessions SET room_id='shared-test' WHERE id=?", shared);
        db.update("UPDATE practice_sessions SET first_exposure=FALSE WHERE id=?", repeated);
        given(client.configured()).willReturn(false);
        var report = coach.report(user, current, false);
        assertEquals("UP", report.progressInsight().status());
        assertTrue(report.progressInsight().text().contains("2 bài"));
        assertTrue(report.progressInsight().text().contains("30.0 điểm"));
        assertEquals(2, report.progressInsight().comparedLessonCount());
        assertEquals(30.0, report.progressInsight().deltaPoints());
        assertEquals(3, report.reviewItems().get(0).occurrences());
        assertEquals("NOT_COMPARABLE", coach.report(user, shared, false).progressInsight().status());
        assertEquals("NOT_COMPARABLE", coach.report(user, repeated, false).progressInsight().status());
        then(client).should(never()).completeJson(anyString(), anyString());
    }

    @Test void eventsAreOwnedIdempotentAndNeverChangeScores() {
        long owner = user(), other = user();
        String id = session(owner), run = UUID.randomUUID().toString();
        coach.recordEvent(owner,id,"coach_result_viewed",null);
        coach.recordEvent(owner,id,"coach_result_viewed",null);
        coach.recordEvent(owner,id,"coach_details_opened",null);
        coach.recordEvent(owner,id,"coach_quick_review_completed",run);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM coach_usage_events WHERE session_id=?",Integer.class,id));
        coach.recordEvent(owner,id,"coach_quick_review_started",run);
        coach.recordEvent(owner,id,"coach_quick_review_completed",run);
        coach.recordEvent(owner,id,"coach_quick_review_completed",run);
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM coach_usage_events WHERE session_id=?",Integer.class,id));
        assertThrows(ResourceNotFoundException.class, () -> coach.recordEvent(other,id,"coach_result_viewed",null));
        assertEquals(50.0, db.queryForObject("SELECT first_attempt_accuracy FROM practice_sessions WHERE id=?",Double.class,id));
    }

    private long user() {
        String email = UUID.randomUUID() + "@coach.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Coach test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
        return db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
    }

    private String session(long user) {
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,first_attempt_accuracy,first_exposure)
                VALUES (?,'test',?,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,50,TRUE)
                """, id, user);
        long sentence = db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=1 ORDER BY position_index LIMIT 1", Long.class);
        db.update("INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed) VALUES (?,?,CURRENT_TIMESTAMP,TRUE)", id, sentence);
        long attempt = db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?", Long.class, id);
        db.update("""
                INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,final_correct,
                first_answer,last_answer,error_type,inferred_error_category)
                VALUES (?,1,2,FALSE,TRUE,'wrong','buys','KNOWN_BUT_MISSED','SUBSTITUTION')
                """, attempt);
        return id;
    }
}
