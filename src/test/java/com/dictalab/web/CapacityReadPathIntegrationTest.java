package com.dictalab.web;

import com.dictalab.domain.*;
import com.dictalab.security.JwtTokenService;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.security.RedisTokenStore;
import com.dictalab.service.CatalogService;
import com.dictalab.service.PreviewVocabularyService;
import com.dictalab.service.ListeningAssessmentService;
import com.dictalab.service.DashboardService;
import com.dictalab.service.RecommendationService;
import com.dictalab.service.PracticeService;
import com.dictalab.service.HistoryService;
import com.dictalab.dto.ApiDtos;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Import;
import com.dictalab.support.JdbcQueryCounter;
import com.dictalab.repository.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Counts SQL on the request thread, excluding fixture writes and background workers. */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.dictalab.web.CapacityReadPathIntegrationTest$SqlCounter"
})
@AutoConfigureMockMvc
@Import(JdbcQueryCounter.class)
class CapacityReadPathIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenService tokens;
    @Autowired CatalogService catalog;
    @Autowired ListeningAssessmentService assessment;
    @Autowired DashboardService dashboard;
    @Autowired RecommendationService recommendations;
    @Autowired PracticeService practice;
    @Autowired HistoryService history;
    @Autowired AppUserRepository users;
    @Autowired LessonRepository lessons;
    @Autowired LessonSentenceRepository sentences;
    @Autowired PracticeSessionRepository sessions;
    @Autowired PreviewVocabularyService preview;
    @MockitoBean RedisTokenStore store;
    AppUser owner;
    AppUser other;
    final Instant now = Instant.parse("2026-10-11T00:00:00Z");

    public static class SqlCounter implements StatementInspector {
        static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) {
            if (sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) statements.get().add(sql);
            return sql;
        }
    }

    @BeforeEach void setup() {
        transaction(() -> {
            owner = new AppUser("capacity-read-" + UUID.randomUUID() + "@test.local", "Owner", "unused", now);
            other = new AppUser("capacity-read-" + UUID.randomUUID() + "@test.local", "Other", "unused", now);
            em.persist(owner); em.persist(other);
        });
        given(store.active(anyString(), anyLong(), anyLong())).willReturn(true);
    }

    @AfterEach void cleanupCounter() { SqlCounter.statements.remove(); }

    @Test void signedJwtReadsAccountOnlyOncePerRequestAndNeverCachesAcrossRequests() throws Exception {
        Cookie access = access();
        for (int i = 0; i < 2; i++) {
            SqlCounter.statements.get().clear();
            var result = mvc.perform(get("/api/v1/catalog/levels").cookie(access)).andExpect(status().isOk()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
            assertQueries("jwt-account", 1);
            assertThat(SqlCounter.statements.get().get(0)).doesNotContain("password_hash", "created_at", "updated_at");
        }
        then(store).should(times(2)).active(anyString(), eq(owner.getId()), eq(owner.getAuthVersion()));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void authenticationProjectionAvoidsCrudTransactionControlButStillReadsCurrentState() {
        JdbcQueryCounter.start();
        assertThat(users.findById(owner.getId())).isPresent();
        var before = JdbcQueryCounter.connectionControls();
        JdbcQueryCounter.stop();
        JdbcQueryCounter.start();
        var state = users.authenticationState(owner.getId()).orElseThrow();
        var controlsAfter = JdbcQueryCounter.connectionControls();
        var after = JdbcQueryCounter.stop();
        assertThat(state.getEmail()).isEqualTo(owner.getEmail());
        assertThat(state.getDisplayName()).isEqualTo(owner.getDisplayName());
        assertThat(state.isEnabled()).isTrue();
        assertThat(before).contains("setAutoCommit[false]", "commit");
        assertThat(controlsAfter).isEmpty();
        assertThat(JdbcQueryCounter.selects(after)).isEqualTo(1);
        System.out.println("AUTH_JDBC before=" + before + " after=" + controlsAfter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "password", "email", "role", "logout-all", "deleted"})
    void nextRequestRejectsRevokedAccountState(String change) throws Exception {
        Cookie access = access();
        mvc.perform(get("/api/v1/catalog/levels").cookie(access)).andExpect(status().isOk());
        transaction(() -> {
            AppUser current = em.find(AppUser.class, owner.getId());
            switch (change) {
                case "disabled" -> current.setEnabled(false, now);
                case "password" -> current.changePassword("changed", now);
                case "email" -> current.changeEmail("changed-" + UUID.randomUUID() + "@test.local", now);
                case "role" -> current.setRole("ADMIN", now);
                case "logout-all" -> current.revokeSessions(now);
                case "deleted" -> em.remove(current);
                default -> throw new IllegalArgumentException(change);
            }
        });
        mvc.perform(get("/api/v1/catalog/levels").cookie(access)).andExpect(status().isUnauthorized());
    }

    @Test void currentRoleIsLoadedFromDatabaseNotAnEarlierRequest() throws Exception {
        Cookie access = access();
        db.update("update app_users set role='ADMIN' where id=?", owner.getId());
        mvc.perform(get("/admin/lessons").cookie(access)).andExpect(status().isOk());
        db.update("update app_users set role='USER' where id=?", owner.getId());
        mvc.perform(get("/admin/lessons").cookie(access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/practice-sessions").cookie(access)
                .contentType("application/json").content("{\"lessonId\":1}"))
                .andExpect(status().isForbidden()).andExpect(header().string("X-CSRF-Error", "1"));
    }

    @Test void redisRevocationAndOutageStillFailClosed() throws Exception {
        Cookie access = access();
        given(store.active(anyString(), anyLong(), anyLong())).willReturn(false);
        mvc.perform(get("/api/v1/catalog/levels").cookie(access)).andExpect(status().isUnauthorized());
        given(store.active(anyString(), anyLong(), anyLong())).willThrow(new RedisTokenStore.Unavailable());
        mvc.perform(get("/api/v1/catalog/levels").cookie(access)).andExpect(status().isServiceUnavailable());
    }

    @Test void otherAuthenticationPathsStillRejectAStalePrincipal() throws Exception {
        var principal = DictaLabPrincipal.from(owner);
        db.update("update app_users set role='ADMIN' where id=?", owner.getId());
        mvc.perform(get("/api/auth/me").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(ints = {2, 10})
    void assessmentPreviewQueryBudgetDoesNotGrowWithLessonCount(int count) {
        transaction(() -> {
            for (int i = 0; i < count; i++) {
                Lesson lesson = lesson("Assessment", CefrLevel.B1);
                var sentence = new LessonSentence(lesson, 1, "special common", 1);
                sentence.attachAudio("/audio/capacity-" + UUID.randomUUID() + ".mp3");
                em.persist(sentence);
                em.persist(new LessonPreviewWord(lesson, 1, "special", null, null));
                var session = completed(owner, lesson, i);
                em.persist(new SentenceAttempt(session, sentence, now));
            }
        });
        SqlCounter.statements.get().clear();
        var result = assessment.assess(owner.getId());
        assertThat(result.evidenceLessons()).isEqualTo(count);
        assertThat(result.metrics().contentWordsObserved()).isEqualTo(count);
        assertThat(result.metrics().firstAttemptAccuracy()).isZero();
        assertQueries("assessment-" + count, 5);
    }

    @Test void catalogKeepsLatestActiveProgressCompletedFlagsAndOwnershipWithTwoQueries() {
        List<Long> ids = new ArrayList<>();
        transaction(() -> {
            Lesson active = lesson("Active", CefrLevel.A1);
            Lesson done = lesson("Done", CefrLevel.A1);
            Lesson untouched = lesson("Untouched", CefrLevel.A1);
            ids.addAll(List.of(active.getId(), done.getId(), untouched.getId()));
            for (Lesson lesson : List.of(active, done, untouched)) {
                for (int i = 1; i <= 4; i++) em.persist(new LessonSentence(lesson, i, "word", 1));
            }
            completed(owner, active, 0);
            completed(owner, done, 1);
            completed(other, untouched, 2);
            // Room and solo progress are both included by the existing catalog contract.
            var old = new PracticeSession(owner, active, now.plusSeconds(3), false);
            old.setRoomId("capacity-room-old"); old.moveToSentence(4); em.persist(old);
            var latest = new PracticeSession(owner, active, now.plusSeconds(4), false);
            latest.moveToSentence(2); em.persist(latest);
        });
        SqlCounter.statements.get().clear();
        var result = catalog.lessons(owner.getId(), CefrLevel.A1).stream().filter(l -> ids.contains(l.id())).toList();
        assertThat(result).hasSize(3);
        var active = result.stream().filter(l -> l.id().equals(ids.get(0))).findFirst().orElseThrow();
        assertThat(active.inProgress()).isTrue(); assertThat(active.completed()).isTrue();
        assertThat(active.progressPercent()).isEqualTo(25);
        var done = result.stream().filter(l -> l.id().equals(ids.get(1))).findFirst().orElseThrow();
        assertThat(done.completed()).isTrue(); assertThat(done.inProgress()).isFalse();
        assertThat(done.progressPercent()).isEqualTo(100);
        var untouched = result.stream().filter(l -> l.id().equals(ids.get(2))).findFirst().orElseThrow();
        assertThat(untouched.completed()).isFalse(); assertThat(untouched.progressPercent()).isZero();
        assertQueries("catalog-service", 2);
    }

    @Test void catalogMatchesPreviousResultsIncludingPreviewVisibilityEmptyLessonsAndTies() {
        transaction(() -> {
            Lesson publicLesson = lesson("Catalog comparison", CefrLevel.A2);
            Lesson empty = lesson("Empty", CefrLevel.A2);
            var personal = new Lesson("personal-" + UUID.randomUUID(), "Personal", "Private", CefrLevel.A2,
                    "Test", 1, 10001, Lesson.PERSONAL_CONTENT_ORIGIN, now);
            personal.setPublished(true, now); em.persist(personal);
            Lesson draft = lesson("Draft", CefrLevel.A2); draft.setPublished(false, now);
            for (int position = 1; position <= 3; position++) {
                var sentence = new LessonSentence(publicLesson, position, "word", 1);
                sentence.attachAudio("/media/" + position + ".wav"); em.persist(sentence);
                em.persist(new LessonPreviewWord(publicLesson, position, "word" + position, null, null));
            }
            completed(owner, publicLesson, 0);
            completed(other, empty, 0);
            var first = new PracticeSession(owner, publicLesson, now.plusSeconds(3), false);
            first.moveToSentence(2); first.setRoomId("compare-first"); em.persist(first);
            var second = new PracticeSession(owner, publicLesson, now.plusSeconds(3), false);
            second.moveToSentence(3); second.setRoomId("compare-second"); em.persist(second);
        });
        for (Long userId : new Long[]{owner.getId(), other.getId(), null}) {
            for (boolean administrator : new boolean[]{false, true}) {
                var expected = originalCatalog(userId, CefrLevel.A2, administrator);
                SqlCounter.statements.get().clear();
                var actual = catalog.lessons(userId, CefrLevel.A2, administrator);
                assertThat(actual).containsExactlyElementsOf(expected);
                assertQueries("catalog-equivalence", 2);
            }
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("update lessons set active=false where level='B2'");
            SqlCounter.statements.get().clear();
            assertThat(catalog.lessons(owner.getId(), CefrLevel.B2)).isEmpty();
            assertQueries("empty-catalog", 1);
            status.setRollbackOnly();
        });
    }

    @Test void catalogReadsCommittedEditsAndIgnoresRolledBackChanges() {
        var ids = new ArrayList<Long>();
        transaction(() -> ids.add(lesson("Before edit", CefrLevel.A1).getId()));
        assertThat(catalog.lessons(owner.getId(), CefrLevel.A1)).anyMatch(l -> l.id().equals(ids.get(0)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("update lessons set active=false where id=?", ids.get(0));
            status.setRollbackOnly();
        });
        assertThat(catalog.lessons(owner.getId(), CefrLevel.A1)).anyMatch(l -> l.id().equals(ids.get(0)));
        db.update("update lessons set title='After edit' where id=?", ids.get(0));
        assertThat(catalog.lessons(owner.getId(), CefrLevel.A1).stream().filter(l -> l.id().equals(ids.get(0))).findFirst().orElseThrow().title())
                .isEqualTo("After edit");
        db.update("update lessons set active=false where id=?", ids.get(0));
        assertThat(catalog.lessons(owner.getId(), CefrLevel.A1)).noneMatch(l -> l.id().equals(ids.get(0)));
    }

    private List<ApiDtos.LessonSummary> originalCatalog(Long userId, CefrLevel level, boolean administrator) {
        return new TransactionTemplate(transactions).execute(status -> {
            var progress = userId == null ? List.<PracticeSessionRepository.CatalogProgress>of()
                    : sessions.catalogProgress(userId, level, SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED);
            var visible = lessons.findByLevelAndActiveTrueOrderByDisplayOrder(level).stream()
                    .filter(l -> administrator || !l.isPersonalContent()).sorted(java.util.Comparator
                            .comparingInt(Lesson::getDisplayOrder).thenComparing(Lesson::getId)).toList();
            var ids = visible.stream().map(Lesson::getId).toList();
            var summaries = ids.isEmpty() ? List.<LessonSentenceRepository.Summary>of() : sentences.summaries(ids);
            var words = preview.wordsForLessons(ids);
            return visible.stream().map(l -> {
                var p = progress.stream().filter(v -> v.getLessonId().equals(l.getId())).findFirst().orElse(null);
                var s = summaries.stream().filter(v -> v.getLessonId().equals(l.getId())).findFirst().orElse(null);
                int count = s == null ? 0 : Math.toIntExact(s.getSentenceCount());
                boolean done = p != null && p.getCompleted() == 1;
                Integer position = p == null ? null : p.getActivePosition();
                int completedCount = position == null ? (done ? count : 0) : Math.max(0, Math.min(count, position - 1));
                return new ApiDtos.LessonSummary(l.getId(), l.getSlug(), l.getTitle(), l.getDescription(), l.getLevel(),
                        l.getTopic(), l.getEstimatedMinutes(), count, count == 0 ? 0 : (int)Math.round(completedCount * 100.0 / count),
                        position != null, done, s == null ? null : s.getAudioUrl(), words.getOrDefault(l.getId(), List.of()));
            }).toList();
        });
    }

    @ParameterizedTest @ValueSource(ints = {0, 10})
    void dashboardReusesItsLatestSoloSessionWithoutChangingRecommendation(int count) {
        transaction(() -> {
            for (int i = 0; i < count; i++) completed(owner, lesson("Dashboard", CefrLevel.B1), i);
        });
        var expected = recommendations.recommend(owner.getId());
        SqlCounter.statements.get().clear();
        var result = dashboard.dashboard(owner.getId());
        assertThat(result.summary().completedLessons()).isEqualTo(count);
        assertThat(result.trend()).hasSize(Math.min(8, count));
        assertThat(result.recommendation()).isEqualTo(expected);
        assertQueries("dashboard-" + count, count == 0 ? 4 : 10);
    }

    @ParameterizedTest @ValueSource(ints = {2, 20})
    void finishingLessonUsesBoundedWordAndPreviewLookupsAndPreservesScore(int count) {
        List<String> sessionIds = new ArrayList<>();
        List<Long> sentenceIds = new ArrayList<>();
        transaction(() -> {
            Lesson lesson = lesson("Finish", CefrLevel.A1);
            em.persist(new LessonPreviewWord(lesson, 1, "Anna", null, null, true));
            var session = new PracticeSession(owner, lesson, now, true);
            session.moveToSentence(count); em.persist(session); sessionIds.add(session.getId());
            for (int i = 1; i <= count; i++) {
                var sentence = new LessonSentence(lesson, i, "Anna common", 1);
                sentence.attachAudio("/audio/capacity-" + UUID.randomUUID() + ".mp3");
                em.persist(sentence); sentenceIds.add(sentence.getId());
                var attempt = new SentenceAttempt(session, sentence, now);
                attempt.incrementReplayCount(); attempt.setTimeSpentSeconds(2);
                if (i < count) attempt.markCompleted();
                em.persist(attempt);
                var word = new WordAttempt(attempt, 1); word.recordAnswer("common", true, false); em.persist(word);
            }
        });
        SqlCounter.statements.get().clear();
        var finished = practice.completeSentence(owner.getId(), sessionIds.get(0), sentenceIds.get(count - 1),
                new ApiDtos.CompleteSentenceRequest(2));
        assertThat(finished.lessonCompleted()).isTrue();
        assertThat(finished.result().firstAttemptAccuracy()).isEqualTo(100);
        assertThat(finished.result().finalAccuracy()).isEqualTo(100);
        assertThat(finished.result().review().sentences()).hasSize(count);
        long wordLookups = SqlCounter.statements.get().stream().filter(s -> s.contains("from word_attempts ")).count();
        long previewLookups = SqlCounter.statements.get().stream().filter(s -> s.contains("from lesson_preview_words ")).count();
        System.out.println("CAPACITY_FINISH sentences=" + count + " wordSelects=" + wordLookups + " previewSelects=" + previewLookups);
        assertThat(wordLookups).isEqualTo(3); // Current state, final score, assessment.
        assertThat(previewLookups).isEqualTo(3); // Current state, final score, assessment.
        var saved = history.detail(owner.getId(), sessionIds.get(0));
        // Database timestamps have microsecond precision; compare the remaining API fields exactly.
        assertThat(saved).usingRecursiveComparison().ignoringFields("completedAt").isEqualTo(finished.result());
        assertThat(java.time.Duration.between(saved.completedAt(), finished.result().completedAt()).abs().toNanos())
                .isLessThan(1000);
    }

    @Test void dictationReusesUnchangedPreviewAndSentenceCountsWithinEachAction() {
        List<String> sessions = new ArrayList<>();
        List<Long> sentences = new ArrayList<>();
        transaction(() -> {
            Lesson lesson = lesson("Actions", CefrLevel.A1);
            em.persist(new LessonPreviewWord(lesson, 1, "Anna", null, null, true));
            var session = new PracticeSession(owner, lesson, now, true); em.persist(session); sessions.add(session.getId());
            for (int i = 1; i <= 2; i++) {
                var sentence = new LessonSentence(lesson, i, "Anna one two", 1);
                em.persist(sentence); sentences.add(sentence.getId());
                if (i == 1) em.persist(new SentenceAttempt(session, sentence, now));
            }
        });
        String session = sessions.get(0); Long sentence = sentences.get(0);
        SqlCounter.statements.get().clear();
        var played = practice.replay(owner.getId(), session, sentence, new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
        assertThat(played.hasListened()).isTrue(); assertThat(played.replayCount()).isEqualTo(1);
        long playWords = selectsContaining("from word_attempts ");
        long playPreview = selectsContaining("from lesson_preview_words ");
        long playCounts = sentenceCounts();
        SqlCounter.statements.get().clear();
        var checked = practice.checkWord(owner.getId(), session, sentence, 1, new ApiDtos.CheckWordRequest("one"));
        assertThat(checked.words().get(1).correct()).isTrue();
        long checkPreview = selectsContaining("from lesson_preview_words ");
        SqlCounter.statements.get().clear();
        var hinted = practice.useHint(owner.getId(), session, sentence, new ApiDtos.HintRequest(HintType.FIRST_LETTER, 2));
        assertThat(hinted.words().get(2).firstLetter()).isEqualTo("t");
        long hintPreview = selectsContaining("from lesson_preview_words ");
        SqlCounter.statements.get().clear();
        var revealed = practice.useHint(owner.getId(), session, sentence, new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null));
        assertThat(revealed.requiresReplayAfterReveal()).isTrue(); assertThat(revealed.canComplete()).isFalse();
        long revealPreview = selectsContaining("from lesson_preview_words ");
        practice.replay(owner.getId(), session, sentence, new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
        practice.replay(owner.getId(), session, sentence, new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED));
        SqlCounter.statements.get().clear();
        var completed = practice.completeSentence(owner.getId(), session, sentence, new ApiDtos.CompleteSentenceRequest(2));
        assertThat(completed.lessonCompleted()).isFalse(); assertThat(completed.nextSentence().position()).isEqualTo(2);
        long completeCounts = sentenceCounts();
        System.out.println("CAPACITY_ACTIONS playWords=" + playWords + " playPreview=" + playPreview
                + " playCounts=" + playCounts + " checkPreview=" + checkPreview + " hintPreview=" + hintPreview
                + " revealPreview=" + revealPreview + " completeCounts=" + completeCounts);
        assertThat(playWords).isEqualTo(1); assertThat(playPreview).isEqualTo(1); assertThat(playCounts).isEqualTo(1);
        assertThat(checkPreview).isEqualTo(1); assertThat(hintPreview).isEqualTo(1); assertThat(revealPreview).isEqualTo(1);
        assertThat(completeCounts).isEqualTo(2); // Current and next sentence states each load their own count.
    }

    private long selectsContaining(String table) {
        return SqlCounter.statements.get().stream().filter(s -> s.contains(table)).count();
    }
    private long sentenceCounts() {
        return SqlCounter.statements.get().stream().filter(s -> s.startsWith("select count(")
                && s.contains("from lesson_sentences ")).count();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "dictalab.testcontainers", matches = "true")
    void catalogProgressRemainsOneRowPerLessonWithLongHistory() {
        List<Long> lessonIds = new ArrayList<>(); List<String> sessionIds = new ArrayList<>();
        transaction(() -> {
            Lesson lesson = lesson("Long history", CefrLevel.A1); lessonIds.add(lesson.getId());
            for (int i = 1; i <= 4; i++) em.persist(new LessonSentence(lesson, i, "word", 1));
            var session = new PracticeSession(owner, lesson, now, false); em.persist(session); sessionIds.add(session.getId());
        });
        // Disposable PostgreSQL only: generate history without retaining 10,000 JPA entities in the fixture.
        String columns = "id,learner_id,user_id,lesson_id,status,current_sentence_position,started_at,completed_at,"
                + "total_replays,hint_count,reveal_count,known_missed_count,unknown_count,total_practice_seconds,"
                + "first_attempt_accuracy,final_accuracy,overall_score,first_exposure,room_id,version";
        db.update("insert into practice_sessions (" + columns + ") " + """
                select gen_random_uuid()::text, learner_id, user_id, lesson_id, 'COMPLETED', 1,
                       started_at, started_at, 0,0,0,0,0,0,0,0,0,false,null,0
                from practice_sessions cross join generate_series(1,10000) where id=?
                """, sessionIds.get(0));
        db.update("insert into practice_sessions (" + columns + ") " + """
                select gen_random_uuid()::text, learner_id, user_id, lesson_id, 'IN_PROGRESS', 2,
                       started_at + n * interval '1 second', null, 0,0,0,0,0,0,0,0,0,false,'scale-room-' || n,0
                from practice_sessions cross join generate_series(1,100) as n where id=?
                """, sessionIds.get(0));
        db.execute("analyze practice_sessions");
        db.execute("analyze lessons");
        SqlCounter.statements.get().clear();
        var progress = catalog.lessons(owner.getId(), CefrLevel.A1).stream()
                .filter(item -> item.id().equals(lessonIds.get(0))).findFirst().orElseThrow();
        assertThat(progress.progressPercent()).isEqualTo(25); assertThat(progress.completed()).isTrue();
        assertQueries("catalog-long-history", 2);
        String sql = SqlCounter.statements.get().get(0);
        String plan = db.queryForObject("explain (analyze, buffers, format json) " + sql, String.class,
                owner.getId(), owner.getId(), "A1", false);
        var result = new tools.jackson.databind.ObjectMapper().readTree(plan).get(0);
        assertThat(result.path("Plan").path("Actual Rows").asInt()).isEqualTo(
                db.queryForObject("select count(*) from lessons where level='A1' and active=true and content_origin <> 'BRITISH_COUNCIL_PERSONAL'", Integer.class));
        System.out.println("CAPACITY_CATALOG_PLAN history=10000 active=101 rows=1 executionMs="
                + result.path("Execution Time").asDouble());
        System.out.println("CAPACITY_CATALOG_EXPLAIN " + plan);
    }

    private Cookie access() {
        var response = new MockHttpServletResponse(); tokens.issue(owner, response);
        return response.getCookie(JwtTokenService.ACCESS_COOKIE);
    }
    private void transaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            action.run(); em.flush(); em.clear();
        });
    }
    private Lesson lesson(String name, CefrLevel level) {
        var lesson = new Lesson("capacity-" + UUID.randomUUID(), name, "Test", level, "Test", 1, 10000, now);
        lesson.setPublished(true, now); em.persist(lesson); return lesson;
    }
    private PracticeSession completed(AppUser user, Lesson lesson, int offset) {
        var session = new PracticeSession(user, lesson, now.plusSeconds(offset), true);
        session.complete(now.plusSeconds(offset + 1), 1, 0, 0, 0, 0, 0);
        em.persist(session); return session;
    }
    private void assertQueries(String operation, int expected) {
        int actual = SqlCounter.statements.get().size();
        System.out.println("CAPACITY_SQL operation=" + operation + " selects=" + actual);
        assertThat(actual).as(operation + " SQL SELECT budget").isEqualTo(expected);
    }
}
