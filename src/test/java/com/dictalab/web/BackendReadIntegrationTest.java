package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.DashboardService;
import com.dictalab.service.RecommendationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.dictalab.web.BackendReadIntegrationTest$WordLookupInspector",
        "logging.level.org.hibernate.orm=ERROR"})
@AutoConfigureMockMvc
class BackendReadIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PracticeSessionRepository sessions;
    @Autowired DashboardService dashboard;
    @Autowired RecommendationService recommendations;
    @Autowired com.dictalab.repository.SentenceAttemptRepository sentenceAttempts;
    @Autowired com.dictalab.repository.WordAttemptRepository words;
    @Autowired com.dictalab.service.PracticeService practice;

    public static class WordLookupInspector implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<Integer> selects = ThreadLocal.withInitial(() -> 0);
        @Override public String inspect(String sql) {
            String normalized = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("select") && normalized.contains("from word_attempts "))
                selects.set(selects.get() + 1);
            return sql;
        }
    }

    AppUser owner;
    AppUser other;
    Lesson a1;
    Lesson a2;
    final Instant time = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            owner = new AppUser("read-" + UUID.randomUUID() + "@test.local", "Owner", "unused", time);
            other = new AppUser("read-" + UUID.randomUUID() + "@test.local", "Other", "unused", time);
            em.persist(owner);
            em.persist(other);
            a1 = lesson(CefrLevel.A1);
            a2 = lesson(CefrLevel.A2);
            em.flush();
            em.clear();
        });
    }

    @Test
    void historyPaginatesAndFiltersBeforePagingWithStableTiesAndOwnership() throws Exception {
        List<String> ids = new ArrayList<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 25; i++) ids.add(completed(owner, a1, time, 60, 80, 90, 85, false, null).getId());
            completed(owner, a2, time.plusSeconds(1), 60, 80, 90, 85, false, "shared-room");
            completed(other, a1, time.plusSeconds(2), 60, 80, 90, 85, false, null);
            em.persist(new PracticeSession(owner, a1, time.plusSeconds(3), false));
            em.flush();
            em.clear();
        });
        ids.sort(Comparator.reverseOrder());
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/api/v1/history").param("level", "A1").param("page", "1").param("size", "10")
                        .with(user(DictaLabPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items", hasSize(10))).andExpect(jsonPath("$.items[0].sessionId").value(ids.get(10)))
                .andExpect(jsonPath("$.items[9].sessionId").value(ids.get(19)));
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(12);
        mvc.perform(get("/api/v1/history").param("level", "A2").with(user(DictaLabPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/history").param("page", "100").with(user(DictaLabPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(26));
    }

    @Test
    void invalidHistoryParametersReturn400() throws Exception {
        for (String query : List.of("page=-1", "page=100001", "page=abc", "size=0", "size=101", "level=NOPE")) {
            mvc.perform(get("/api/v1/history?" + query).with(user(DictaLabPrincipal.from(owner))))
                    .andExpect(status().isBadRequest());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {4, 32})
    void fullRevealUsesBoundedWordLookupsAndPreservesExistingEvidence(int wordCount) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new com.dictalab.domain.LessonSentence(a1, 1, "Anna " + "word ".repeat(wordCount), 1));
            em.persist(new com.dictalab.domain.LessonPreviewWord(a1, 1, "Anna", null, null, true));
            em.flush(); em.clear();
        });
        var started = practice.start(owner.getId(), new com.dictalab.dto.ApiDtos.StartSessionRequest(a1.getId()));
        long sentence = started.sentence().sentenceId();
        practice.replay(owner.getId(), started.sessionId(), sentence,
                new com.dictalab.dto.ApiDtos.ReplayRequest(com.dictalab.domain.ReplayEventType.STARTED));
        practice.checkWord(owner.getId(), started.sessionId(), sentence, 1,
                new com.dictalab.dto.ApiDtos.CheckWordRequest("wrong"));
        practice.useHint(owner.getId(), started.sessionId(), sentence,
                new com.dictalab.dto.ApiDtos.HintRequest(com.dictalab.domain.HintType.FIRST_LETTER, 1));
        practice.useHint(owner.getId(), started.sessionId(), sentence,
                new com.dictalab.dto.ApiDtos.HintRequest(com.dictalab.domain.HintType.REVEAL_WORD, 2));

        var beforeRollback = practice.current(owner.getId(), started.sessionId()).sentence();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            practice.useHint(owner.getId(), started.sessionId(), sentence,
                    new com.dictalab.dto.ApiDtos.HintRequest(com.dictalab.domain.HintType.REVEAL_SENTENCE, null));
            em.flush(); em.clear();
            throw new IllegalStateException("Injected full-reveal rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Injected full-reveal rollback");
        assertThat(practice.current(owner.getId(), started.sessionId()).sentence()).isEqualTo(beforeRollback);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.clear();
            var attempt = sentenceAttempts.findByPracticeSessionIdAndSentenceId(started.sessionId(), sentence).orElseThrow();
            assertThat(words.findBySentenceAttemptIdOrderByWordIndex(attempt.getId())).hasSize(2);
            assertThat(sessions.findById(started.sessionId()).orElseThrow().getRevealCount()).isZero();
            em.flush(); em.clear();
        });

        WordLookupInspector.selects.set(0);
        var revealed = practice.useHint(owner.getId(), started.sessionId(), sentence,
                new com.dictalab.dto.ApiDtos.HintRequest(com.dictalab.domain.HintType.REVEAL_SENTENCE, null));
        int firstLookups = WordLookupInspector.selects.get();
        assertThat(revealed.words()).hasSize(wordCount + 1);
        assertThat(revealed.words().get(0).prefilled()).isTrue();
        assertThat(revealed.words().subList(1, wordCount + 1)).allSatisfy(word -> assertThat(word.revealed()).isTrue());
        assertThat(revealed.requiresReplayAfterReveal()).isTrue();
        assertThat(revealed.canComplete()).isFalse();
        WordLookupInspector.selects.set(0);
        var repeated = practice.useHint(owner.getId(), started.sessionId(), sentence,
                new com.dictalab.dto.ApiDtos.HintRequest(com.dictalab.domain.HintType.REVEAL_SENTENCE, null));
        int repeatLookups = WordLookupInspector.selects.get();
        assertThat(repeated).isEqualTo(revealed);

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.clear();
            var attempt = sentenceAttempts.findByPracticeSessionIdAndSentenceId(started.sessionId(), sentence).orElseThrow();
            var saved = words.findBySentenceAttemptIdOrderByWordIndex(attempt.getId());
            assertThat(saved).hasSize(wordCount).noneMatch(word -> word.getWordIndex() == 0);
            assertThat(saved.get(0).getAttemptCount()).isEqualTo(1);
            assertThat(saved.get(0).getFirstAnswer()).isEqualTo("wrong");
            assertThat(saved.get(0).getFirstAttemptCorrect()).isFalse();
            assertThat(saved.get(0).hasFirstLetterHint()).isTrue();
            assertThat(saved.get(1).getRevealType()).isEqualTo(com.dictalab.domain.HintType.REVEAL_WORD);
            var session = sessions.findById(started.sessionId()).orElseThrow();
            assertThat(session.getRevealCount()).isEqualTo(1);
            assertThat(session.getHintCount()).isEqualTo(2);
            assertThat(session.getTotalReplays()).isEqualTo(1);
            em.flush(); em.clear();
        });
        System.out.println("HINT_WORD_LOOKUP_SELECTS words=" + wordCount + " first=" + firstLookups + " repeat=" + repeatLookups);
        assertThat(firstLookups).as("Word lookup SELECTs, excluding INSERT/UPDATE").isEqualTo(2);
        assertThat(repeatLookups).isEqualTo(2);
    }

    @Test
    void resultSentencesLoadTheirTranscriptInOneQueryRegardlessOfSentenceCount() {
        String id=new TransactionTemplate(transactions).execute(status -> {
            var session=completed(owner,a1,time,60,80,90,85,false,null);
            for(int i=1;i<=20;i++) {
                var sentence=new com.dictalab.domain.LessonSentence(a1,i,"Sentence "+i,1);
                em.persist(sentence);
                em.persist(new com.dictalab.domain.SentenceAttempt(session,sentence,time));
            }
            em.flush();em.clear();return session.getId();
        });
        var statistics=emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var transcripts=new TransactionTemplate(transactions).execute(status -> {
            em.clear();
            return sentenceAttempts.findByPracticeSessionIdOrderBySentencePositionAsc(id).stream()
                    .map(attempt->attempt.getSentence().getTranscript()).toList();
        });
        assertThat(transcripts).hasSize(20).startsWith("Sentence 1","Sentence 2").endsWith("Sentence 20");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void dashboardUsesLifetimeWeightedTotalsButOnlyEightRecentSoloPoints() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 9; i++) completed(owner, a1, time.plusSeconds(i), 61, 70, 90, 75, false, null);
            completed(owner, a2, time.plusSeconds(10), 90, 100, 100, 100, false, null);
            completed(owner, a2, time.plusSeconds(20), 1000, 0, 0, 0, false, "shared-room");
            completed(other, a1, time.plusSeconds(30), 1000, 0, 0, 0, false, null);
            em.persist(new PracticeSession(owner, a2, time.plusSeconds(40), false));
            em.flush();
            em.clear();
        });
        var result = dashboard.dashboard(owner.getId());
        assertThat(result.summary().completedLessons()).isEqualTo(10);
        assertThat(result.summary().learningMinutes()).isEqualTo(11);
        assertThat(result.summary().averageFirstAttemptAccuracy()).isEqualTo(73);
        assertThat(result.summary().averageFinalAccuracy()).isEqualTo(91);
        assertThat(result.summary().averageScore()).isEqualTo(77.5);
        assertThat(result.errors().knownButMissed()).isEqualTo(20);
        assertThat(result.errors().unknownWords()).isEqualTo(10);
        assertThat(result.trend()).hasSize(8);
        assertThat(result.trend()).allSatisfy(point -> assertThat(point.sessionId()).isNotBlank());
        String latestSessionId = result.trend().get(7).sessionId();
        assertThat(sessions.findById(latestSessionId).orElseThrow().getUser().getId()).isEqualTo(owner.getId());
        assertThat(result.trend().get(7).level()).isEqualTo(CefrLevel.A2);
        assertThat(result.byLevel()).hasSize(4);
        assertThat(result.byLevel().get(0).completedLessons()).isEqualTo(9);
        assertThat(result.byLevel().get(2).completedLessons()).isZero();
        assertThat(dashboard.dashboard(other.getId()).summary().completedLessons()).isEqualTo(1);
    }

    @Test
    void emptyDashboardAndRecommendationStillWork() {
        var result = dashboard.dashboard(owner.getId());
        assertThat(result.summary().completedLessons()).isZero();
        assertThat(result.summary().averageScore()).isZero();
        assertThat(result.trend()).isEmpty();
        assertThat(result.byLevel()).allSatisfy(level -> assertThat(level.completedLessons()).isZero());
        assertThat(result.recommendation()).isNotNull();
    }

    @Test
    void recommendationUsesOnlyLatestThreeSoloScoresAtTheLatestLevel() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            completed(owner, a2, time, 60, 0, 0, 0, false, null);
            for (int i = 1; i <= 3; i++) completed(owner, a1, time.plusSeconds(i), 60, 85, 90, 85, false, null);
            completed(owner, a2, time.plusSeconds(10), 60, 0, 0, 0, false, "shared-room");
            em.flush();
            em.clear();
        });
        assertThat(recommendations.recommend(owner.getId()).level()).isEqualTo(CefrLevel.A2);
        assertThat(recommendations.recommend(owner.getId()).reason()).contains("85.0%");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 20; i < 23; i++) completed(owner, a2, time.plusSeconds(i), 60, 54, 60, 55, false, null);
            em.flush();
            em.clear();
        });
        assertThat(recommendations.recommend(owner.getId()).level()).isEqualTo(CefrLevel.A1);
    }

    @Test
    void evidenceSelectsLatestUniqueFirstExposuresBeforeLimitAndIgnoresRedoSharedAndOtherUsers() {
        List<String> expected = new ArrayList<>();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 12; i++) {
                Lesson lesson = lesson(CefrLevel.A1);
                completed(owner, lesson, time.plusSeconds(i), 60, 10, 20, 15, true, null);
                expected.add(completed(owner, lesson, time.plusSeconds(i + 100), 60, 80, 90, 85, true, null).getId());
                completed(owner, lesson, time.plusSeconds(i + 200), 60, 100, 100, 100, false, null);
                completed(owner, lesson, time.plusSeconds(i + 300), 60, 100, 100, 100, true, "shared-room-" + i);
                completed(other, lesson, time.plusSeconds(i + 400), 60, 100, 100, 100, true, null);
            }
            em.flush();
            em.clear();
        });
        java.util.Collections.reverse(expected);
        assertThat(sessions.assessmentEvidence(owner.getId(), SessionStatus.COMPLETED, PageRequest.of(0, 10)))
                .extracting(PracticeSession::getId).containsExactlyElementsOf(expected.subList(0, 10));
    }

    private Lesson lesson(CefrLevel level) {
        var lesson = new Lesson("read-" + UUID.randomUUID(), "Read " + level, "Test", level, "Test", 1, 10000, time);
        lesson.setPublished(true, time);
        em.persist(lesson);
        return lesson;
    }

    private PracticeSession completed(AppUser account, Lesson lesson, Instant at, int seconds,
                                       double first, double last, double score, boolean exposure, String room) {
        var session = new PracticeSession(account, lesson, at, exposure);
        session.setRoomId(room);
        session.complete(at, seconds, first, last, 2, 1, score);
        em.persist(session);
        return session;
    }
}
