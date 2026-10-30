package com.dictalab.service;

import com.dictalab.config.AiQueueProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@SpringBootTest(properties = {"dictalab.ai.queue.enabled=true", "dictalab.ai.queue.dispatch-delay-ms=600000",
        "dictalab.conversation.enabled=true", "dictalab.coach.enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class AiBackgroundJobsIntegrationTest {
    @Autowired AiBackgroundJobs jobs;
    @Autowired ConversationService conversation;
    @Autowired CoachService coach;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;
    @MockitoBean CartesiaClient tts;
    @MockitoBean GeminiClient ai;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean AiPlatformClient platform;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AiUsageBudget budget;
    private final List<Long> users = new ArrayList<>();
    private long owner;

    @BeforeEach void setup() {
        owner = user();
        given(ai.configured()).willReturn(true); given(tts.enabled()).willReturn(true);
        given(tts.synthesize(anyString())).willReturn(new byte[]{1,2,3});
    }
    @AfterEach void cleanup() {
        users.forEach(id -> { db.update("DELETE FROM practice_sessions WHERE user_id=?", id); db.update("DELETE FROM app_users WHERE id=?", id); });
    }

    @Test void deniedPlaybackNeverEnqueuesAPaidAudioJob() {
        String id = audioSession();
        willThrow(new AiUsageBudget.Rejected(org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "Premium required"))
                .given(budget).requireAccess(owner);
        assertThatThrownBy(() -> conversation.play(owner, id, 1, "original", false))
                .isInstanceOfSatisfying(AiUsageBudget.Rejected.class,
                        failure -> assertThat(failure.status().value()).isEqualTo(402));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE conversation_id=?", Integer.class, id)).isZero();
        then(tts).should(never()).synthesize(anyString());
    }

    @Test void playbackAndStatusAreNonblockingAndDuplicateDeliveryNeverRepeatsTts() {
        String id = audioSession();
        var first = conversation.play(owner, id, 1, "original", false);
        assertThat(first.path("audioStatus").asText()).isEqualTo("PENDING");
        assertThat(first.has("audioUrl")).isFalse();
        assertThat(conversation.audioStatus(owner, id, 1, "original").get("status")).isEqualTo("PENDING");
        conversation.play(owner, id, 1, "original", false);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE conversation_id=?", Integer.class, id)).isEqualTo(1);
        then(tts).should(never()).synthesize(anyString());
        assertThatThrownBy(() -> conversation.audioStatus(user(), id, 1, "original"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> conversation.audioStatus(owner, id, 2, "original"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        String job = job(id);
        jobs.consumeAudio(job); jobs.consumeAudio(job);
        assertThat(state(job)).isEqualTo("DONE");
        assertThat(conversation.audioStatus(owner, id, 1, "original").get("status")).isEqualTo("READY");
        var ready = conversation.play(owner, id, 1, "original", true);
        assertThat(ready.path("audioUrl").asText()).contains(id);
        assertThat(ready.path("turns").path(0).path("plays").asInt()).isEqualTo(1);
        assertThat(ready.path("turns").path(0).path("slow").asBoolean()).isTrue();
        then(tts).should(times(1)).synthesize(anyString());
    }

    @Test void coachQueuesOncePersistsValidatedAiAndKeepsOwnershipChecks() {
        String id = practice();
        var fallback = coach.report(owner, id, false);
        var item = fallback.reviewItems().get(0);
        given(ai.completeJson(anyString(), anyString())).willReturn(json.valueToTree(Map.of(
                "weaknesses", List.of(Map.of("pattern", item.pattern(), "evidenceIds", List.of(item.id()), "insight", "Test insight")),
                "progressInsight", fallback.progressInsight(), "nextActions", List.of(Map.of("pattern", item.pattern(), "instruction", "Test action")),
                "reviewIds", List.of(item.id()))));
        assertThat(coach.report(owner, id, true).source()).isEqualTo("QUEUED");
        assertThat(coach.report(owner, id, true).source()).isEqualTo("QUEUED");
        assertThat(coach.report(owner, id, false).source()).isEqualTo("QUEUED");
        then(ai).should(never()).completeJson(anyString(), anyString());
        assertThatThrownBy(() -> coach.report(user(), id, true)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
        String job = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        jobs.consumeCoach(job); jobs.consumeCoach(job);
        assertThat(coach.report(owner, id, false).source()).isEqualTo("AI");
        assertThat(state(job)).isEqualTo("DONE");
        then(ai).should(times(1)).completeJson(anyString(), anyString());
    }

    @Test void failedTtsRequiresExplicitBoundedRetryAndOldMessagesAreIgnored() {
        given(tts.synthesize(anyString())).willThrow(new IllegalStateException("Provider unavailable"));
        String id = audioSession();
        conversation.play(owner, id, 1, "original", false);
        String old = job(id);
        jobs.consumeAudio(old); jobs.consumeAudio(old);
        assertThat(state(old)).isEqualTo("FAILED");
        assertThat(conversation.audioStatus(owner, id, 1, "original").get("status")).isEqualTo("FAILED");
        assertThat(jobs.audio(id, 1, "original", "Test English utterance.", false)).isEqualTo("FAILED");
        for (int attempt = 0; attempt < 2; attempt++) {
            conversation.play(owner, id, 1, "original", false);
            jobs.consumeAudio(old); // A previous attempt cannot consume a newer explicit retry.
            jobs.consumeAudio(job(id));
        }
        assertThat(conversation.play(owner, id, 1, "original", false).path("audioStatus").asText()).isEqualTo("EXHAUSTED");
        then(tts).should(times(3)).synthesize(anyString());
    }

    @Test void pythonAudioUsesExistingFenceAndExplicitRetryHasANewIdempotencyKey() {
        given(platform.enabled("tts", owner)).willReturn(true);
        given(platform.complete(eq("tts"), eq(owner), anyString(), any())).willThrow(new IllegalStateException("uncertain"));
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String first = job(id); jobs.consumeAudio(first); jobs.consumeAudio(first);
        assertThat(state(first)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
        given(platform.complete(eq("tts"), eq(owner), anyString(), any())).willReturn(json.createObjectNode());
        given(platform.audio(any())).willReturn(new byte[]{1,2,3});
        conversation.play(owner, id, 1, "original", false);
        jobs.consumeAudio(job(id)); jobs.consumeAudio(job(id));
        assertThat(state(job(id))).isEqualTo("DONE");
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        then(platform).should(times(2)).complete(eq("tts"), eq(owner), keys.capture(), any());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
        then(tts).should(never()).synthesize(anyString());
    }

    @Test void pythonCoachRejectsInventedEvidenceThenPersistsValidReportWithoutChangingProgress() {
        given(platform.enabled("coach", owner)).willReturn(true);
        String id = practice();
        var baseline = coach.report(owner, id, false);
        var item = baseline.reviewItems().get(0);
        var output = json.createObjectNode().put("provider", "gemini").put("model", "test-model")
                .put("promptVersion", "COACH_PLATFORM_FOCUS_V1");
        output.set("task", json.valueToTree(Map.of("focus", Map.of("pattern", item.pattern(),
                "evidenceIds", List.of("invented"), "insight", "Bạn đã bỏ lỡ từ quen trong câu."), "reviewIds", List.of())));
        given(platform.complete(eq("coach"), eq(owner), anyString(), any())).willReturn(output);
        coach.report(owner, id, true);
        String first = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        jobs.consumeCoach(first);
        assertThat(state(first)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM dictation_coach_reports WHERE session_id=?", Integer.class, id)).isZero();
        output.set("task", json.valueToTree(Map.of("focus", Map.of("pattern", item.pattern(),
                "evidenceIds", List.of(item.id()), "insight", "Bạn đã bỏ lỡ từ quen trong câu."), "reviewIds", List.of(item.id()))));
        coach.report(owner, id, true);
        String second = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        jobs.consumeCoach(second); jobs.consumeCoach(second);
        assertThat(coach.report(owner, id, false).source()).isEqualTo("AI");
        assertThat(coach.report(owner, id, false).progressInsight()).isEqualTo(baseline.progressInsight());
        then(ai).should(never()).completeJson(anyString(), anyString());
    }

    @Test void accountDeletedDuringPythonCallCannotReceiveAudio() {
        given(platform.enabled("tts", owner)).willReturn(true);
        given(platform.complete(eq("tts"), eq(owner), anyString(), any())).willAnswer(call -> {
            db.update("DELETE FROM app_users WHERE id=?", owner);
            return json.createObjectNode();
        });
        given(platform.audio(any())).willReturn(new byte[]{1,2,3});
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        jobs.consumeAudio(job(id));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE conversation_id=?", Integer.class, id)).isZero();
    }

    @Test void crashedWorkerIsUnknownAndNeverBlindlyRedeliveredToProvider() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String job = job(id);
        db.update("""
                UPDATE ai_background_jobs SET status='RUNNING',attempts=1,lease_id=?,
                locked_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id=?
                """, UUID.randomUUID().toString(), job);
        jobs.dispatch(); jobs.consumeAudio(job);
        assertThat(state(job)).isEqualTo("UNKNOWN");
        then(tts).should(never()).synthesize(anyString());
    }

    @Test void staleAudioAndDeletedAccountsNeverSendTextToTts() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String job = job(id);
        var state = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        ((tools.jackson.databind.node.ObjectNode)state.path("turns").path(0).path("prompt")).put("utteranceEn", "Different text.");
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", state.toString(), id);
        jobs.consumeAudio(job);
        assertThat(state(job)).isEqualTo("FAILED");
        db.update("DELETE FROM app_users WHERE id=?", owner);
        jobs.consumeAudio(job);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_background_jobs WHERE id=?", Integer.class, job)).isZero();
        then(tts).should(never()).synthesize(anyString());
        assertThatThrownBy(() -> jobs.consumeAudio("not-a-uuid")).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test void brokerOutageLeavesPendingDurableWorkWithoutCallingProvider() {
        willThrow(new IllegalStateException("Broker offline")).given(rabbit).convertAndSend(anyString(), anyString(),
                any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        jobs.dispatch();
        assertThat(state(job(id))).isEqualTo("PENDING");
        then(tts).should(never()).synthesize(anyString());
    }

    @Test void providerAdmissionBackpressureDefersWithoutSpendingAPaidAttempt() {
        given(tts.synthesize(anyString())).willThrow(new ProviderGuard.Unavailable("Busy before HTTP", 5, true));
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String previous = job(id); jobs.consumeAudio(previous);
        assertThat(job(id)).isNotEqualTo(previous);
        assertThat(state(job(id))).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?", Integer.class, job(id))).isZero();
        jobs.consumeAudio(previous);
        then(tts).should(times(1)).synthesize(anyString());
    }

    @Test void coachAdmissionBackpressurePreservesTheQueuedBaseline() {
        String id = practice();
        given(ai.completeJson(anyString(), anyString())).willThrow(new ProviderGuard.Unavailable("Busy before HTTP", 5, true));
        coach.report(owner, id, true);
        String previous = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        jobs.consumeCoach(previous);
        assertThat(coach.report(owner, id, false).source()).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE practice_id=?", Integer.class, id)).isZero();
        jobs.consumeCoach(previous);
        then(ai).should(times(1)).completeJson(anyString(), anyString());
    }

    @Test void sharedDatabaseBudgetBoundsWorkersAcrossServiceInstances() throws Exception {
        var other = new AiBackgroundJobs(db, context.getBean(PlatformTransactionManager.class), rabbit,
                context.getBean(AiQueueProperties.class), context.getBeanProvider(ConversationService.class),
                context.getBeanProvider(CoachService.class), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        given(tts.synthesize(anyString())).willAnswer(call -> {
            entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
            return new byte[]{1,2,3};
        });
        String first = audioSession(), second = audioSession(), third = audioSession();
        for (String id : List.of(first, second, third)) conversation.play(owner, id, 1, "original", false);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> jobs.consumeAudio(job(first)));
            var b = pool.submit(() -> other.consumeAudio(job(second)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            other.consumeAudio(job(third));
            assertThat(state(job(third))).isEqualTo("PENDING");
            release.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            other.consumeAudio(job(third));
            assertThat(state(job(third))).isEqualTo("DONE");
        } finally { release.countDown(); pool.shutdownNow(); }
        then(tts).should(times(3)).synthesize(anyString());
    }

    @Test void purgedSuccessfulAudioCanBeRegeneratedWithANewBudget() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String previous = job(id); jobs.consumeAudio(previous);
        db.update("DELETE FROM ai_conversation_audio WHERE session_id=?", id);
        db.update("UPDATE ai_background_jobs SET attempts=3 WHERE id=?", previous);
        assertThat(conversation.play(owner, id, 1, "original", false).path("audioStatus").asText()).isEqualTo("PENDING");
        jobs.consumeAudio(previous); jobs.consumeAudio(job(id));
        assertThat(state(job(id))).isEqualTo("DONE");
        then(tts).should(times(2)).synthesize(anyString());
    }

    @Test void expiredAudioWorkerCannotPersistItsResult() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String job = job(id);
        given(tts.synthesize(anyString())).willAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            expire(job); return new byte[]{1,2,3};
        });
        jobs.consumeAudio(job);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
        jobs.dispatch(); jobs.consumeAudio(job);
        assertThat(state(job)).isEqualTo("UNKNOWN");
        then(tts).should(times(1)).synthesize(anyString());
    }

    @Test void replacedCoachLeaseCannotPersistItsResult() {
        String id = practice();
        var fallback = coach.report(owner, id, false);
        var item = fallback.reviewItems().get(0);
        coach.report(owner, id, true);
        String job = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        given(ai.completeJson(anyString(), anyString())).willAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            db.update("UPDATE ai_background_jobs SET lease_id=? WHERE id=?", UUID.randomUUID().toString(), job);
            return json.valueToTree(Map.of("weaknesses", List.of(Map.of("pattern", item.pattern(),
                    "evidenceIds", List.of(item.id()), "insight", "Test insight")), "progressInsight", fallback.progressInsight(),
                    "nextActions", List.of(Map.of("pattern", item.pattern(), "instruction", "Test action")), "reviewIds", List.of(item.id())));
        });
        jobs.consumeCoach(job);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM dictation_coach_reports WHERE session_id=?", Integer.class, id)).isZero();
        assertThat(state(job)).isEqualTo("RUNNING");
    }

    @Test void invalidatedInputCannotLeaveDoneWithoutAudio() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String job = job(id);
        given(tts.synthesize(anyString())).willAnswer(call -> {
            var state = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
            ((tools.jackson.databind.node.ObjectNode) state.path("turns").path(0).path("prompt")).put("utteranceEn", "Changed input.");
            db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", state.toString(), id);
            return new byte[]{1,2,3};
        });
        // A result-write failure must not commit the terminal state.
        assertThatThrownBy(() -> jobs.consumeAudio(job)).isInstanceOf(IllegalStateException.class).hasMessage("Audio task changed");
        assertThat(state(job)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
    }

    private void expire(String job) {
        db.update("UPDATE ai_background_jobs SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id=?", job);
    }

    @Test void replacedAudioLeaseCannotPersistItsResult() {
        String id = audioSession(); conversation.play(owner, id, 1, "original", false);
        String job = job(id);
        given(tts.synthesize(anyString())).willAnswer(call -> {
            db.update("UPDATE ai_background_jobs SET lease_id=? WHERE id=?", UUID.randomUUID().toString(), job);
            return new byte[]{1,2,3};
        });
        jobs.consumeAudio(job);
        assertThat(state(job)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
    }

    @Test void failedCoachPersistenceRollsBackDone() {
        String id = practice();
        var fallback = coach.report(owner, id, false); var item = fallback.reviewItems().get(0);
        given(ai.completeJson(anyString(), anyString())).willReturn(json.valueToTree(Map.of("weaknesses", List.of(Map.of(
                "pattern", item.pattern(), "evidenceIds", List.of(item.id()), "insight", "Test insight")),
                "progressInsight", fallback.progressInsight(), "nextActions", List.of(Map.of("pattern", item.pattern(), "instruction", "Test action")),
                "reviewIds", List.of(item.id()))));
        coach.report(owner, id, true);
        String job = db.queryForObject("SELECT id FROM ai_background_jobs WHERE practice_id=?", String.class, id);
        String constraint = "test_coach_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("ALTER TABLE dictation_coach_reports ADD CONSTRAINT " + constraint + " CHECK (session_id<>'" + id + "')");
        try {
            assertThatThrownBy(() -> jobs.consumeCoach(job)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(state(job)).isEqualTo("RUNNING");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM dictation_coach_reports WHERE session_id=?", Integer.class, id)).isZero();
            expire(job); jobs.dispatch(); jobs.consumeCoach(job);
            assertThat(state(job)).isEqualTo("UNKNOWN");
            then(ai).should(times(1)).completeJson(anyString(), anyString());
        } finally { db.execute("ALTER TABLE dictation_coach_reports DROP CONSTRAINT " + constraint); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
    void leaseExpiryWhileCompletionWaitsForParentLockRejectsResult() throws Exception {
        String id=audioSession(); conversation.play(owner,id,1,"original",false); String job=job(id);
        var providerEntered=new CountDownLatch(1); var providerReturn=new CountDownLatch(1);
        given(tts.synthesize(anyString())).willAnswer(call->{
            providerEntered.countDown(); assertThat(providerReturn.await(10,TimeUnit.SECONDS)).isTrue(); return new byte[]{1,2,3};
        });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var worker=pool.submit(()->jobs.consumeAudio(job)); assertThat(providerEntered.await(5,TimeUnit.SECONDS)).isTrue();
            new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
                db.queryForObject("SELECT id FROM ai_conversation_sessions WHERE id=? FOR UPDATE",String.class,id);
                db.update("UPDATE ai_background_jobs SET locked_until=clock_timestamp() + INTERVAL '1' SECOND WHERE id=?",job);
                providerReturn.countDown();
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->db.queryForObject(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='transactionid' AND query LIKE 'SELECT id FROM ai_conversation_sessions%FOR UPDATE'",Integer.class)>0);
                db.execute("SELECT pg_sleep(2)"); // Real DB deadline crosses while completion transaction is waiting.
            });
            worker.get(5,TimeUnit.SECONDS);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,id)).isZero();
            jobs.dispatch(); assertThat(state(job)).isEqualTo("UNKNOWN");
        } finally {providerReturn.countDown();pool.shutdownNow();}
    }

    private String job(String id) { return db.queryForObject("SELECT id FROM ai_background_jobs WHERE conversation_id=?", String.class, id); }
    private String state(String id) { return db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id); }
    private long user() {
        String email = UUID.randomUUID() + "@ai-queue.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Queue test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
        long id = db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email); users.add(id); return id;
    }
    private String audioSession() {
        String id = conversation.create(owner, "cafe", "A1").path("id").asText();
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", json.readTree("""
                {"completed":false,"turns":[{"prompt":{"utteranceEn":"Test English utterance."},
                "done":false,"attempts":[],"plays":0}]}
                """).toString(), id);
        return id;
    }
    private String practice() {
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,first_attempt_accuracy,first_exposure)
                VALUES (?,'test',?,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,50,TRUE)
                """, id, owner);
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
