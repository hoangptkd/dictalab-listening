package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest(properties = {"dictalab.coach.enabled=true", "dictalab.coach.provider=ollama",
        "dictalab.ai.queue.enabled=true", "dictalab.ai.queue.dispatch-delay-ms=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class CoachLocalQueueIntegrationTest {
    @Autowired CoachService coach;
    @Autowired AiBackgroundJobs jobs;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean CoachLocalClient local;
    @MockitoBean GeminiClient cloud;
    @MockitoBean RabbitTemplate rabbit;

    @Test void localQueuePersistsOnceAndOfflineFailureDoesNotSwitchToCloud() {
        String email=UUID.randomUUID()+"@queued-coach.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        long user=db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
        for (boolean fail : new boolean[]{false,true}) {
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,first_attempt_accuracy,first_exposure) VALUES (?,'test',?,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,50,TRUE)",id,user);
            long sentence=db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=1 ORDER BY position_index LIMIT 1",Long.class);
            db.update("INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed) VALUES (?,?,CURRENT_TIMESTAMP,TRUE)",id,sentence);
            long attempt=db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,id);
            db.update("INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,final_correct,first_answer,last_answer,error_type,inferred_error_category) VALUES (?,1,2,FALSE,TRUE,'wrong','buys','KNOWN_BUT_MISSED','SUBSTITUTION')",attempt);
            if (fail) given(local.completeJson(anyString())).willThrow(new IllegalStateException("Offline"));
            else given(local.completeJson(anyString())).willReturn(json.readTree("{\"focus\":null,\"reviewIds\":[]}"));
            assertEquals("QUEUED",coach.report(user,id,true).source());
            String job=db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?",String.class,id);
            jobs.consumeCoach(job); jobs.consumeCoach(job);
            assertEquals(fail ? "FAILED" : "DONE",db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,job));
            assertEquals(fail ? "RETRY_REQUIRED" : "AI",coach.report(user,id,false).source());
        }
        then(local).should(times(2)).completeJson(anyString());
        then(cloud).shouldHaveNoInteractions();
    }
}
