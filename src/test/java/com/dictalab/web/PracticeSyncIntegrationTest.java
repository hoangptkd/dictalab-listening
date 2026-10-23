package com.dictalab.web;

import com.dictalab.domain.*;
import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.PracticeService;
import com.dictalab.service.PracticeSubmissions;
import com.dictalab.service.TranscriptTokenizer;
import com.dictalab.support.JdbcQueryCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(JdbcQueryCounter.class)
class PracticeSyncIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PracticeService practice;
    @Autowired PracticeSubmissions submissions;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    AppUser owner, other;
    ApiDtos.PracticeStartResponse session;
    ApiDtos.PracticeSyncState opened;

    @BeforeEach void setup() throws Exception {
        Long lesson = new TransactionTemplate(transactions).execute(status -> {
            Instant now = Instant.now();
            owner = new AppUser("sync-" + UUID.randomUUID() + "@test.local", "Owner", "unused", now);
            other = new AppUser("sync-other-" + UUID.randomUUID() + "@test.local", "Other", "unused", now);
            em.persist(owner); em.persist(other);
            var content = new Lesson("sync-" + UUID.randomUUID(), "Sync", "Test", CefrLevel.A1, "Test", 1, 10000, now);
            content.setPublished(true, now); em.persist(content);
            var sentence = new LessonSentence(content, 1, "Anna buys fresh apples.", 1.0);
            sentence.attachAudio("/audio/test-sync.mp3"); em.persist(sentence);
            em.persist(new LessonPreviewWord(content, 1, "Anna", null, null, true));
            em.flush(); em.clear();
            return content.getId();
        });
        session = practice.start(owner.getId(), new ApiDtos.StartSessionRequest(lesson));
        practice.replay(owner.getId(), session.sessionId(), session.sentence().sentenceId(), new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
        opened = json.readValue(mvc.perform(post(path("/open")).with(user(DictaLabPrincipal.from(owner))).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), ApiDtos.PracticeSyncState.class);
    }

    String path(String suffix) {return "/api/v1/practice-sessions/" + session.sessionId() + "/sync" + suffix;}
    ApiDtos.PracticeSyncEvent check(long sequence, int index, String answer) {
        return new ApiDtos.PracticeSyncEvent(sequence, index, new ApiDtos.CheckWordRequest(answer, 10L), null, null, null, null);
    }
    ApiDtos.PracticeSyncRequest batch(long base, ApiDtos.PracticeSyncEvent... events) {
        return new ApiDtos.PracticeSyncRequest(session.sentence().sentenceId(), base, opened.grading().contentVersion(), List.of(events));
    }
    MockHttpServletRequestBuilder request(String key, ApiDtos.PracticeSyncRequest body) {
        return post(path("/batches")).with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .header("Idempotency-Key", key).header("X-Practice-Events", body.events().size())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    int events() {
        return db.queryForObject("SELECT COUNT(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id "
                + "JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=?", Integer.class, session.sessionId());
    }

    @Test void manifestHasSaltedHashesWithoutHiddenTranscript() {
        assertThat(opened.sentence().speechText()).isNull();
        assertThat(opened.grading().normalization()).isEqualTo("ascii-nfkc-v1");
        assertThat(opened.grading().hashes()).hasSize(4).allMatch(hash -> hash.matches("[a-f0-9]{64}"));
        assertThat(opened.grading().hashes().get(1)).isEqualTo(com.dictalab.service.AiBackgroundJobs.hash(
                opened.grading().salt() + "\n1\nbuys"));
        assertThat(json.writeValueAsString(opened.grading())).doesNotContain("buys", "fresh", "apples");
    }

    @Test void orderedBatchPreservesFirstWrongAndCountsIntentionalRepeatedAnswers() throws Exception {
        mvc.perform(request("ordered-batch", batch(0, check(1, 1, "bye"), check(2, 1, "bye"), check(3, 1, "buys"), check(4, 2, "fresh"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.sentence.words[1].correct").value(true))
                .andExpect(jsonPath("$.sentence.words[1].classificationAvailable").value(true));
        assertThat(events()).isEqualTo(4);
        var row = db.queryForMap("SELECT first_answer,first_attempt_correct,attempt_count FROM word_attempts w "
                + "JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=? AND w.word_index=1", session.sessionId());
        assertThat(row.get("first_answer")).isEqualTo("bye");
        assertThat(row.get("first_attempt_correct")).isEqualTo(false);
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(3);
    }

    @Test void retryReadsReceiptBeforeRevisionAndNeverRegressesPersistedState() throws Exception {
        var first = batch(0, check(1, 1, "bye"));
        String receipt = mvc.perform(request("retry-immutable", first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(request("later-batch", batch(1, check(2, 2, "fresh")))).andExpect(status().isOk());
        mvc.perform(request("retry-immutable", first)).andExpect(status().isOk()).andExpect(content().string(receipt));
        mvc.perform(request("retry-immutable", batch(0, check(1, 1, "buys")))).andExpect(status().isConflict());
        mvc.perform(request("stale-new-batch", first)).andExpect(status().isConflict());
        assertThat(events()).isEqualTo(2);
        assertThat(practice.current(owner.getId(), session.sessionId()).sentence().words().get(2).correct()).isTrue();
    }

    @Test void invalidEventRollsBackEveryAnswerRevisionAndReceipt() throws Exception {
        mvc.perform(request("rollback-batch", batch(0, check(1, 1, "bye"), check(3, 2, "fresh"))))
                .andExpect(status().isConflict());
        assertThat(events()).isZero();
        assertThat(db.queryForObject("SELECT sync_revision FROM practice_sessions WHERE id=?", Long.class, session.sessionId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_submission_receipts WHERE session_id=?", Integer.class, session.sessionId())).isZero();
    }

    @Test void batchReadsWordsMetadataAndSessionOnceRegardlessOfEventCount() throws Exception {
        JdbcQueryCounter.start();
        try {
            mvc.perform(request("query-budget-batch", batch(0, check(1, 1, "bye"), check(2, 1, "buy"), check(3, 1, "buys"), check(4, 2, "fresh"))))
                    .andExpect(status().isOk());
        } finally {
            List<String> sql = JdbcQueryCounter.stop();
            assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("from word_attempts "))).hasSize(1);
            assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("from practice_sessions "))).hasSize(1);
            assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("from lesson_preview_words "))).hasSize(1);
            assertThat(JdbcQueryCounter.selects(sql)).isLessThanOrEqualTo(7);
        }
    }

    @Test void commandsAreBarriersAndServerStillControlsRevealReplayAndCompletion() throws Exception {
        var hint = new ApiDtos.PracticeSyncEvent(1, null, null, null, new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null), null, null);
        mvc.perform(request("mixed-command", batch(0, check(1, 1, "buys"), hint))).andExpect(status().isConflict());
        mvc.perform(request("reveal-command", batch(0, hint))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sentence.requiresReplayAfterReveal").value(true))
                .andExpect(jsonPath("$.sentence.canComplete").value(false));
        var complete = new ApiDtos.PracticeSyncEvent(2, null, null, null, null, null, new ApiDtos.CompleteSentenceRequest(0));
        mvc.perform(request("too-early-complete", batch(1, complete))).andExpect(status().isConflict());
        var started = new ApiDtos.PracticeSyncEvent(2, null, null, new ApiDtos.ReplayRequest(ReplayEventType.STARTED), null, null, null);
        mvc.perform(request("replay-start-command", batch(1, started))).andExpect(status().isOk());
        var played = new ApiDtos.PracticeSyncEvent(3, null, null, new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED), null, null, null);
        mvc.perform(request("replay-end-command", batch(2, played))).andExpect(status().isOk());
        complete = new ApiDtos.PracticeSyncEvent(4, null, null, null, null, null, new ApiDtos.CompleteSentenceRequest(0));
        var finalBody = batch(3, complete);
        String receipt = mvc.perform(request("complete-command", finalBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.completion.lessonCompleted").value(true)).andReturn().getResponse().getContentAsString();
        mvc.perform(request("complete-command", finalBody)).andExpect(status().isOk()).andExpect(content().string(receipt));
    }

    @Test void protocolKeepsOwnershipCsrfValidationAndPreventsLegacyTabWrites() throws Exception {
        var body = batch(0, check(1, 1, "buys"));
        mvc.perform(request("wrong-owner-batch", body).with(user(DictaLabPrincipal.from(other)))).andExpect(status().isNotFound());
        mvc.perform(post(path("/batches")).with(csrf()).header("Idempotency-Key", "no-auth-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isUnauthorized());
        mvc.perform(post(path("/batches")).with(user(DictaLabPrincipal.from(owner))).header("Idempotency-Key", "no-csrf-key")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        mvc.perform(request("invalid-answer", batch(0, check(1, 1, " ")))).andExpect(status().isBadRequest());
        mvc.perform(post(path("/batches")).with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .header("Idempotency-Key", "underreported-work").header("X-Practice-Events", "1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(batch(0, check(1, 1, "buys"), check(2, 2, "fresh")))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/practice-sessions/" + session.sessionId() + "/sentences/" + session.sentence().sentenceId() + "/words/1/checks")
                .with(user(DictaLabPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"buys\"}")).andExpect(status().isConflict());
        assertThat(events()).isZero();
    }

    @Test void staleContentAndOversizedBatchesCannotWrite() throws Exception {
        var body = batch(0, check(1, 1, "buys"));
        mvc.perform(request("content-mismatch", new ApiDtos.PracticeSyncRequest(body.sentenceId(), 0, "outdated", body.events())))
                .andExpect(status().isConflict());
        var oversized = new ApiDtos.PracticeSyncRequest(body.sentenceId(), 0, body.contentVersion(),
                java.util.stream.LongStream.rangeClosed(1, 33).mapToObj(i -> check(i, 1, "bye")).toList());
        mvc.perform(request("too-many-events", oversized)).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }

    @Test void concurrentWritersWithSameRevisionOnlyCommitOneBatch() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> {start.await(5, TimeUnit.SECONDS); return mvc.perform(request("writer-first", batch(0, check(1, 1, "bye"))))
                    .andReturn().getResponse().getStatus();});
            var b = pool.submit(() -> {start.await(5, TimeUnit.SECONDS); return mvc.perform(request("writer-second", batch(0, check(1, 2, "fresh"))))
                    .andReturn().getResponse().getStatus();});
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {pool.shutdownNow();}
        assertThat(events()).isEqualTo(1);
    }

    @Test void sharedNormalizationFixturesMatchServerAndManifestCapability() throws Exception {
        var fixtures = json.readTree(getClass().getResourceAsStream("/practice-normalization.json"));
        var tokenizer = new TranscriptTokenizer();
        for (var fixture : fixtures) {
            String normalized = tokenizer.normalize(fixture.get("answer").asString());
            assertThat(normalized).isEqualTo(fixture.get("normalized").asString());
            assertThat(normalized.chars().allMatch(c -> c >= 32 && c <= 126)).isEqualTo(fixture.get("supported").asBoolean());
        }
    }
}
