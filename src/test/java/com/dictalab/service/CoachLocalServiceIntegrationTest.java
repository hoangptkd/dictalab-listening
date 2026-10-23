package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest(properties = {"dictalab.coach.enabled=true", "dictalab.coach.provider=ollama",
        "dictalab.ai.budget.enabled=true"})
class CoachLocalServiceIntegrationTest {
    @Autowired CoachService coach;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean CoachLocalClient local;
    @MockitoBean GeminiClient cloud;

    @Test void localIsIndependentOfCloudKeepsCacheAndChargesNoCloudBudget() {
        long user = user(); String session = session(user);
        var baseline = coach.report(user,session,false);
        assertTrue(baseline.aiEnabled());
        var item = baseline.reviewItems().get(0);
        given(local.completeJson(anyString())).willReturn(json.readTree("{\"focus\":{\"pattern\":\"" + item.pattern()
                + "\",\"evidenceIds\":[\"" + item.id() + "\"],\"insight\":\"Bạn đã bỏ lỡ một từ quen trong câu.\"},\"reviewIds\":[]}"));
        var report = coach.report(user,session,true);
        assertEquals("AI",report.source());
        assertEquals("qwen3.5:4b",report.generation().model());
        assertEquals(CoachLocalClient.PROMPT_VERSION,report.generation().promptVersion());
        assertEquals(report,coach.report(user,session,true));
        then(local).should(times(1)).completeJson(argThat(input -> !input.contains("firstAttemptAccuracy") && !input.contains("progress") && !input.contains("@")));
        then(cloud).shouldHaveNoInteractions();
        assertEquals(0L,db.queryForObject("SELECT reserved_microusd FROM ai_daily_budget WHERE scope=?",Long.class,"LOCAL_USER:"+user));
        assertThrows(com.dictalab.exception.ResourceNotFoundException.class, () -> coach.report(user+1000000,session,true));
    }

    @Test void badOutputAndOfflineModelPreserveBaselineScoresAndLegacyCache() {
        long user = user(); String session = session(user);
        given(local.completeJson(anyString())).willReturn(json.readTree("{\"focus\":{\"pattern\":\"INVENTED\",\"evidenceIds\":[\"999:0\"],\"insight\":\"Đổi điểm\"},\"reviewIds\":[]}"));
        assertEquals("UNAVAILABLE",coach.report(user,session,true).source());
        given(local.completeJson(anyString())).willThrow(new IllegalStateException("Offline"));
        assertEquals("UNAVAILABLE",coach.report(user,session,true).source());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM dictation_coach_reports WHERE session_id=?",Integer.class,session));
        assertEquals(50.0,db.queryForObject("SELECT first_attempt_accuracy FROM practice_sessions WHERE id=?",Double.class,session));
        String old = "{\"source\":\"AI\",\"aiEnabled\":true,\"weaknesses\":[],\"progressInsight\":{\"status\":\"UP\",\"text\":\"Old\"},\"nextActions\":[],\"reviewItems\":[]}";
        db.update("INSERT INTO dictation_coach_reports(session_id,report_json) VALUES (?,?)",session,old);
        assertEquals("Old",coach.report(user,session,true).progressInsight().text());
        then(local).should(times(2)).completeJson(anyString());
        then(cloud).shouldHaveNoInteractions();
    }

    @Test void noMistakesNeverCallsModel() {
        long user = user(); String id = session(user);
        db.update("DELETE FROM word_attempts WHERE sentence_attempt_id IN (SELECT id FROM sentence_attempts WHERE practice_session_id=?)",id);
        assertTrue(coach.report(user,id,true).reviewItems().isEmpty());
        then(local).shouldHaveNoInteractions();
        then(cloud).shouldHaveNoInteractions();
    }

    private long user() {
        String email = UUID.randomUUID()+"@local-coach.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        return db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
    }
    private String session(long user) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,first_attempt_accuracy,first_exposure) VALUES (?,'test',?,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,50,TRUE)",id,user);
        long sentence=db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=1 ORDER BY position_index LIMIT 1",Long.class);
        db.update("INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed) VALUES (?,?,CURRENT_TIMESTAMP,TRUE)",id,sentence);
        long attempt=db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,id);
        db.update("INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,final_correct,first_answer,last_answer,error_type,inferred_error_category) VALUES (?,1,2,FALSE,TRUE,'wrong','buys','KNOWN_BUT_MISSED','SUBSTITUTION')",attempt);
        return id;
    }
}
