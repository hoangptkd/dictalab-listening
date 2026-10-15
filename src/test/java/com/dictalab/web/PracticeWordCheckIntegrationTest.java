package com.dictalab.web;

import com.dictalab.domain.*;
import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.PracticeService;
import com.dictalab.service.PracticeSubmissions;
import com.dictalab.support.JdbcQueryCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(JdbcQueryCounter.class)
class PracticeWordCheckIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PracticeService practice;
    @Autowired PracticeSubmissions submissions;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    AppUser owner;
    AppUser other;
    ApiDtos.PracticeStartResponse session;

    @BeforeEach
    void setup() {
        Long lesson = new TransactionTemplate(transactions).execute(status -> {
            Instant now = Instant.now();
            owner = new AppUser("word-owner-" + UUID.randomUUID() + "@test.local", "Owner", "unused", now);
            other = new AppUser("word-other-" + UUID.randomUUID() + "@test.local", "Other", "unused", now);
            em.persist(owner); em.persist(other);
            var content = new Lesson("word-check-" + UUID.randomUUID(), "Word check", "Test",
                    CefrLevel.A1, "Test", 1, 10000, now);
            content.setPublished(true, now); em.persist(content);
            var sentence = new LessonSentence(content, 1, "Anna buys fresh apples at the market.", 1.0);
            sentence.attachAudio("/audio/test-word-check.mp3"); em.persist(sentence);
            em.persist(new LessonPreviewWord(content, 1, "Anna", null, null, true));
            em.flush(); em.clear();
            return content.getId();
        });
        session = practice.start(owner.getId(), new ApiDtos.StartSessionRequest(lesson));
    }

    private void listen(ReplayEventType event) {
        practice.replay(owner.getId(), session.sessionId(), session.sentence().sentenceId(), new ApiDtos.ReplayRequest(event));
    }

    private MockHttpServletRequestBuilder check(int index, String answer, String key, boolean compact) {
        var request = post("/api/v1/practice-sessions/" + session.sessionId() + "/sentences/"
                + session.sentence().sentenceId() + "/words/" + index + "/checks")
                .with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ApiDtos.CheckWordRequest(answer, 20L)));
        if (key != null) request.header("Idempotency-Key", key);
        if (compact) request.param("compact", "true");
        return request;
    }

    @Test
    void compactReturnsOneWordWithoutRebuildingSentenceOrCountingLessonSize() throws Exception {
        listen(ReplayEventType.STARTED);
        JdbcQueryCounter.start();
        var compact = mvc.perform(check(1, "buys", "compact-size", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.word.index").value(1))
                .andExpect(jsonPath("$.word.correct").value(true)).andExpect(jsonPath("$.resolvedWords").value(2))
                .andExpect(jsonPath("$.canComplete").value(false)).andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.words").doesNotExist()).andExpect(jsonPath("$.speechText").doesNotExist())
                .andExpect(jsonPath("$.audioUrl").doesNotExist()).andExpect(jsonPath("$.totalSentences").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> sql = JdbcQueryCounter.stop();
        // Includes ORM and JDBC: ownership locks and durable retry receipts remain in the budget.
        assertThat(JdbcQueryCounter.selects(sql)).isEqualTo(6);
        assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("from practice_sessions "))).hasSize(1);
        assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("practice_submission_receipts")))
                .singleElement().asString().contains("count(*)", "left join");
        assertThat(sql.stream().filter(s -> s.startsWith("select") && s.contains("from word_attempts "))).hasSize(1);
        assertThat(sql).noneMatch(s -> s.contains("count(") && s.contains("lesson_sentences"));
        assertThat(sql).hasSizeLessThanOrEqualTo(10);
        var legacy = mvc.perform(check(1, "buys", "legacy-size", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.words.length()").value(7))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(compact.length).isLessThan(legacy.length / 3);
        System.out.printf("Word check: compact=%d bytes, legacy=%d bytes, SQL selects=%d, SQL statements=%d%n",
                compact.length, legacy.length, JdbcQueryCounter.selects(sql), sql.size());
    }

    @Test
    void checksWithoutRetryKeysRetainOwnershipAndBothResponseContracts() throws Exception {
        listen(ReplayEventType.STARTED);
        mvc.perform(check(1, "bye", null, true).with(user(DictaLabPrincipal.from(other))))
                .andExpect(status().isNotFound());
        mvc.perform(check(1, "bye", null, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.correct").value(false));
        mvc.perform(check(1, "buys", null, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.words[1].correct").value(true));
        assertThat(events()).isEqualTo(2);
        assertThat(receipts()).isZero();
    }

    @Test
    void retryAtReceiptLimitStillReplaysButNewKeysAndChangedPayloadsAreRejected() throws Exception {
        listen(ReplayEventType.STARTED);
        String receipt = mvc.perform(check(1, "bye", "limit-retry", true)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String action = session.sentence().sentenceId() + "/word-checks/1";
        db.batchUpdate("""
                INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json)
                VALUES (?,?,?,?,?)
                """, java.util.stream.IntStream.range(1, 500)
                .mapToObj(i -> new Object[]{session.sessionId(), "limit-filler-" + i, action, "fixture", "{}"}).toList());
        mvc.perform(check(1, "bye", "limit-retry", true)).andExpect(status().isOk()).andExpect(content().string(receipt));
        mvc.perform(check(1, "buys", "limit-retry", true)).andExpect(status().isConflict());
        mvc.perform(check(1, "buys", "limit-new-key", true)).andExpect(status().isConflict());
        // The bound is per action, not per session.
        mvc.perform(check(2, "fresh", "other-word-limit", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.correct").value(true));
        assertThat(events()).isEqualTo(2);
        assertThat(receipts()).isEqualTo(501);
    }

    @Test
    void reusedSessionLockKeepsGradeAndReceiptInOneRollbackBoundary() throws Exception {
        listen(ReplayEventType.STARTED);
        var request = new ApiDtos.CheckWordRequest("bye", 20L);
        assertThatThrownBy(() -> submissions.execute(owner.getId(), session.sessionId(), "rollback-check",
                session.sentence().sentenceId() + "/word-checks/1", request, ApiDtos.WordCheckResult.class, locked -> {
                    practice.checkLockedWordResult(locked, session.sentence().sentenceId(), 1, request);
                    em.flush();
                    throw new IllegalStateException("Simulated failure after grading");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isZero();
        assertThat(receipts()).isZero();
        mvc.perform(check(1, "bye", "rollback-check", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.correct").value(false));
        assertThat(events()).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
    }

    @Test
    void retriesKeepExactReceiptAndRejectChangedPayloadOrResponseContract() throws Exception {
        listen(ReplayEventType.STARTED);
        String receipt = mvc.perform(check(1, "bye", "compact-retry", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.classificationAvailable").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(check(2, "fresh", "compact-next", true)).andExpect(status().isOk());
        mvc.perform(check(1, "bye", "compact-retry", true)).andExpect(status().isOk()).andExpect(content().string(receipt));
        mvc.perform(check(1, "buys", "compact-retry", true)).andExpect(status().isConflict());
        mvc.perform(check(1, "bye", "compact-retry", false)).andExpect(status().isConflict());
        assertThat(events()).isEqualTo(2);
        mvc.perform(check(1, "bye", "legacy-retry", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.words[1].classificationAvailable").value(true));
        assertThat(events()).isEqualTo(2);
    }

    @Test
    void properNamesHintsClassificationAndReplayEligibilityKeepTheirMeaning() throws Exception {
        mvc.perform(check(1, "buys", "not-listened", true)).andExpect(status().isConflict());
        listen(ReplayEventType.STARTED);
        mvc.perform(check(0, "wrong", "proper-name", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.prefilled").value(true)).andExpect(jsonPath("$.word.userInput").value("Anna"));
        assertThat(events()).isZero();
        mvc.perform(check(1, "bye", "wrong-answer", true)).andExpect(status().isOk());
        practice.classifyWord(owner.getId(), session.sessionId(), session.sentence().sentenceId(),
                new ApiDtos.ClassifyWordRequest(1, LearnerErrorType.UNKNOWN));
        practice.useHint(owner.getId(), session.sessionId(), session.sentence().sentenceId(),
                new ApiDtos.HintRequest(HintType.FIRST_LETTER, 1));
        mvc.perform(check(1, "buys", "corrected-word", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.firstLetter").value("b"))
                .andExpect(jsonPath("$.word.errorType").value("UNKNOWN"))
                .andExpect(jsonPath("$.hintCount").value(1));
        practice.useHint(owner.getId(), session.sessionId(), session.sentence().sentenceId(),
                new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null));
        mvc.perform(check(2, "fresh", "revealed-word", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.word.revealedText").value("fresh"))
                .andExpect(jsonPath("$.resolvedWords").value(7))
                .andExpect(jsonPath("$.requiresReplayAfterReveal").value(true)).andExpect(jsonPath("$.canComplete").value(false));
        listen(ReplayEventType.STARTED); listen(ReplayEventType.COMPLETED);
        mvc.perform(check(2, "fresh", "replayed-word", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresReplayAfterReveal").value(false)).andExpect(jsonPath("$.canComplete").value(true))
                .andExpect(jsonPath("$.completed").value(false));
        assertThat(events()).isEqualTo(2);
        assertThat(practice.completeSentence(owner.getId(), session.sessionId(), session.sentence().sentenceId(),
                new ApiDtos.CompleteSentenceRequest(0)).lessonCompleted()).isTrue();
    }

    @Test
    void allCorrectCompactChecksEnableCompletionWithoutCompletingTheSentence() throws Exception {
        listen(ReplayEventType.STARTED);
        List<String> answers = List.of("Anna", "buys", "fresh", "apples", "at", "the", "market");
        for (int index = 1; index < answers.size(); index++) {
            mvc.perform(check(index, answers.get(index), "correct-word-" + index, true)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.resolvedWords").value(index + 1))
                    .andExpect(jsonPath("$.canComplete").value(index == answers.size() - 1))
                    .andExpect(jsonPath("$.completed").value(false));
        }
        assertThat(events()).isEqualTo(6);
        assertThat(practice.current(owner.getId(), session.sessionId()).sentence().canComplete()).isTrue();
        assertThat(practice.completeSentence(owner.getId(), session.sessionId(), session.sentence().sentenceId(),
                new ApiDtos.CompleteSentenceRequest(0)).lessonCompleted()).isTrue();
        mvc.perform(check(1, "buys", "after-completion", true)).andExpect(status().isConflict());
        assertThat(events()).isEqualTo(6);
    }

    @Test
    void compactEndpointRetainsAuthenticationCsrfOwnershipAndValidation() throws Exception {
        listen(ReplayEventType.STARTED);
        mvc.perform(check(1, "buys", "wrong-owner", true).with(user(DictaLabPrincipal.from(other))))
                .andExpect(status().isNotFound());
        mvc.perform(check(-1, "buys", "invalid-word", true)).andExpect(status().isBadRequest());
        mvc.perform(check(99, "buys", "missing-word", true)).andExpect(status().isConflict());
        mvc.perform(check(1, " ", "blank-answer", true)).andExpect(status().isBadRequest());
        String path = "/api/v1/practice-sessions/" + session.sessionId() + "/sentences/"
                + session.sentence().sentenceId() + "/words/1/checks?compact=true";
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"buys\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user(DictaLabPrincipal.from(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"buys\"}")).andExpect(status().isForbidden());
        assertThat(events()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentChecksCommitOneGradeWithSameOrDistinctRetryKeys(boolean sameKey) throws Exception {
        listen(ReplayEventType.STARTED);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        try {
            java.util.concurrent.Callable<String> submit = () -> {
                start.await(5, TimeUnit.SECONDS);
                String key = sameKey ? "concurrent-compact" : "concurrent-compact-" + sequence.incrementAndGet();
                return mvc.perform(check(1, "bye", key, true)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            };
            var a = pool.submit(submit); var b = pool.submit(submit); start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertThat(events()).isEqualTo(1);
        assertThat(receipts()).isEqualTo(sameKey ? 1 : 2);
    }

    private int events() {
        return db.queryForObject("""
                SELECT COUNT(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id
                JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=?
                """, Integer.class, session.sessionId());
    }

    private int receipts() {
        return db.queryForObject("SELECT COUNT(*) FROM practice_submission_receipts WHERE session_id=?",
                Integer.class, session.sessionId());
    }
}
