package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.support.JdbcQueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(JdbcQueryCounter.class)
class QueryOptimizationIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired ListeningComprehensionService listening;
    @Autowired CoachService coach;
    @Autowired HistoryService history;
    @Autowired AdminContentService admin;
    @Autowired LessonLibrary library;
    @Autowired ConversationService conversation;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

    @Test void libraryLoadsAllLevelsWithOneCatalogAndPreviewBatch() {
        JdbcQueryCounter.start();
        var result=library.snapshot(0,false);
        var sql=JdbcQueryCounter.stop();
        assertThat(result.lessons()).isNotEmpty();
        assertThat(JdbcQueryCounter.selects(sql)).isEqualTo(7);
        assertThat(result.lessons().size()).isEqualTo(db.queryForObject("SELECT COUNT(*) FROM lessons WHERE active=TRUE AND content_origin<>'BRITISH_COUNCIL_PERSONAL'",Integer.class));
    }

    @Test void listeningDraftsAndReviewReuseAlreadyLoadedRows() {
        AppUser owner = users.save(new AppUser("query-" + UUID.randomUUID() + "@test.local", "Owner", "unused", Instant.now()));
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String id = (String) listening.start(owner.getId(), unit, "ONE_PASS", false).get("sessionId");
        db.update("UPDATE listening_sessions SET stage='RECALL' WHERE id=?", id);
        JdbcQueryCounter.start();
        var recall = listening.saveRecall(owner.getId(), id, "draft");
        var recallSql = JdbcQueryCounter.stop();
        assertThat(recall.get("recallDraft")).isEqualTo("draft");
        assertThat(recall).isEqualTo(listening.state(owner.getId(), id));
        db.update("UPDATE listening_sessions SET stage='QUESTIONS' WHERE id=?", id);
        JdbcQueryCounter.start();
        var answers = listening.saveAnswers(owner.getId(), id, Map.of("Q1", 0));
        var answersSql = JdbcQueryCounter.stop();
        assertThat(answers).isEqualTo(listening.state(owner.getId(), id));
        db.update("UPDATE listening_sessions SET stage='COMPLETE' WHERE id=?", id);
        JdbcQueryCounter.start();
        var review = listening.review(owner.getId(), id);
        var reviewSql = JdbcQueryCounter.stop();
        assertThat(review).containsAllEntriesOf(listening.report(owner.getId(), id));
        System.out.println("QUERY_BUDGET recall=" + JdbcQueryCounter.selects(recallSql) + " answers="
                + JdbcQueryCounter.selects(answersSql) + " review=" + JdbcQueryCounter.selects(reviewSql));
        assertThat(JdbcQueryCounter.selects(recallSql)).isLessThanOrEqualTo(3);
        assertThat(JdbcQueryCounter.selects(answersSql)).isLessThanOrEqualTo(3);
        assertThat(recallSql.stream().filter(s -> s.startsWith("update")).count()).isEqualTo(1);
        assertThat(answersSql.stream().filter(s -> s.startsWith("update")).count()).isEqualTo(1);
        assertThat(JdbcQueryCounter.selects(reviewSql)).isLessThanOrEqualTo(6);
        assertThatThrownBy(() -> listening.saveAnswers(owner.getId(), id, Map.of())).isInstanceOf(InvalidPracticeStateException.class);
        assertThatThrownBy(() -> listening.review(-1L, id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void cachedCoachChecksOwnershipWithoutRebuildingHistory() {
        AppUser owner = users.save(new AppUser("query-coach-" + UUID.randomUUID() + "@test.local", "Owner", "unused", Instant.now()));
        Long lesson = db.queryForObject("SELECT id FROM lessons ORDER BY id LIMIT 1", Long.class);
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,current_sentence_position,
                  started_at,completed_at,total_replays,hint_count,reveal_count,known_missed_count,unknown_count,
                  total_practice_seconds,first_attempt_accuracy,final_accuracy,overall_score,first_exposure,version)
                VALUES (?,?,?,?,'COMPLETED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0,0,0,0,0,0,0,0,TRUE,0)
                """, id, owner.getEmail(), owner.getId(), lesson);
        String payload = "{\"source\":\"AI\",\"aiEnabled\":true,\"weaknesses\":[],\"progressInsight\":null,\"nextActions\":[],\"reviewItems\":[]}";
        db.update("INSERT INTO dictation_coach_reports(session_id,report_json) VALUES (?,?)", id, payload);
        JdbcQueryCounter.start();
        var cached = coach.report(owner.getId(), id, false);
        var sql = JdbcQueryCounter.stop();
        assertThat(cached.source()).isEqualTo("AI");
        System.out.println("QUERY_BUDGET coachCache=" + JdbcQueryCounter.selects(sql));
        assertThat(JdbcQueryCounter.selects(sql)).isLessThanOrEqualTo(2);
        assertThatThrownBy(() -> coach.report(-1L, id, false)).isInstanceOf(ResourceNotFoundException.class);
        db.update("DELETE FROM dictation_coach_reports WHERE session_id=?", id);
        var full = history.detail(owner.getId(), id);
        var evidence = history.detailForCoach(owner.getId(), id);
        assertThat(evidence).usingRecursiveComparison().ignoringFields("assessment").isEqualTo(full);
        assertThat(evidence.assessment()).isNull();
        assertThat(coach.report(owner.getId(), id, false).source()).isEqualTo("RULES");
        db.update("UPDATE practice_sessions SET status='IN_PROGRESS' WHERE id=?", id);
        assertThatThrownBy(() -> coach.report(owner.getId(), id, false)).isInstanceOf(ResourceNotFoundException.class);
        db.update("DELETE FROM practice_sessions WHERE id=?", id);
        assertThatThrownBy(() -> coach.report(owner.getId(), id, false)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void legacyAdminListsAllLessonsInOneQueryWithCorrectCountsAndLocks() {
        JdbcQueryCounter.start(); var items = admin.listLessons(); var sql = JdbcQueryCounter.stop();
        assertThat(JdbcQueryCounter.selects(sql)).isEqualTo(1);
        assertThat(items).hasSize(db.queryForObject("SELECT COUNT(*) FROM lessons", Integer.class));
        for (var item : items) {
            assertThat(item.sentenceCount()).isEqualTo(db.queryForObject(
                    "SELECT COUNT(*) FROM lesson_sentences WHERE lesson_id=?", Integer.class, item.id()));
            assertThat(item.contentLocked()).isEqualTo(db.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM practice_sessions WHERE lesson_id=?) OR
                        EXISTS(SELECT 1 FROM listening_units WHERE lesson_id=?)
                    """, Boolean.class, item.id(), item.id()));
        }
    }

    @Test void listeningCatalogCountsSharedAudioExactlyLikeTheOriginalQuery() {
        AppUser owner = users.save(new AppUser("catalog-" + UUID.randomUUID() + "@test.local", "Owner", "unused", Instant.now()));
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        listening.start(owner.getId(), unit, "ONE_PASS", false);
        listening.start(owner.getId(), unit, "TWO_PASS", true);
        var original = db.queryForList("""
                SELECT u.id,
                  (SELECT COUNT(*) FROM practice_sessions p WHERE p.user_id=? AND p.lesson_id IN
                    (SELECT lesson_id FROM lesson_sentences WHERE audio_url=u.audio_url))
                  + (SELECT COUNT(*) FROM listening_sessions s JOIN listening_units previous ON previous.id=s.unit_id
                    WHERE s.user_id=? AND previous.audio_url=u.audio_url)
                  + (SELECT COUNT(*) FROM audio_exposure_events e WHERE e.user_id=? AND e.audio_url=u.audio_url) AS prior_sessions
                FROM listening_units u JOIN lessons l ON l.id=u.lesson_id
                WHERE u.published=TRUE AND l.active=TRUE AND l.content_origin <> 'BRITISH_COUNCIL_PERSONAL' ORDER BY u.level,l.display_order,l.id,u.id
                """, owner.getId(), owner.getId(), owner.getId());
        JdbcQueryCounter.start(); var catalog = listening.units(owner.getId()); var sql = JdbcQueryCounter.stop();
        assertThat(JdbcQueryCounter.selects(sql)).isEqualTo(1);
        for (int i=0; i<original.size(); i++) {
            assertThat(catalog.get(i).get("id")).isEqualTo(original.get(i).get("id"));
            assertThat(((Number) catalog.get(i).get("prior_sessions")).longValue())
                    .isEqualTo(((Number) original.get(i).get("prior_sessions")).longValue());
        }
    }

    @Test void listeningCatalogFollowsEditorialOrderInsteadOfCreationId() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var before = listening.units(-1L);
            var lastA1 = before.stream().filter(row -> "A1".equals(row.get("level"))).reduce((a, b) -> b).orElseThrow();
            var lessonId = lastA1.get("lesson_id");
            db.update("UPDATE lessons SET display_order=-1 WHERE id=?", lessonId);
            var reordered = listening.units(-1L);
            assertThat(reordered.get(0).get("lesson_id")).isEqualTo(lessonId);
            assertThat(reordered).extracting(row -> row.get("id"))
                    .containsExactlyInAnyOrderElementsOf(before.stream().map(row -> row.get("id")).toList());
        });
    }

    @Test void audioUsesOneOwnershipJoinWithoutLoadingSessionJson() {
        AppUser owner = users.save(new AppUser("audio-query-" + UUID.randomUUID() + "@test.local", "Owner", "unused", Instant.now()));
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES (?,?,'cafe','A1','{}')", id, owner.getId());
        db.update("INSERT INTO ai_conversation_audio(session_id,turn_number,variant,wav) VALUES (?,1,'original',?)", id, new byte[]{1,2,3});
        JdbcQueryCounter.start(); var audio = conversation.audio(owner.getId(), id, 1, "original"); var sql = JdbcQueryCounter.stop();
        assertThat(audio).containsExactly(1,2,3); assertThat(JdbcQueryCounter.selects(sql)).isEqualTo(1);
        assertThat(sql.get(0)).doesNotContain("state_json");
        assertThatThrownBy(() -> conversation.audio(-1L, id, 1, "original"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void backgroundWakeupOnlyOccursAfterCommitAndGaugeScrapeDoesNotQueryDb() {
        var gate = new IdleDispatch(java.time.Clock.fixed(Instant.now(), java.time.ZoneOffset.UTC));
        gate.complete(gate.begin(), false);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> { gate.wakeAfterCommit(); status.setRollbackOnly(); });
        assertThat(gate.begin()).isEqualTo(-1);
        tx.executeWithoutResult(status -> gate.wakeAfterCommit());
        assertThat(gate.begin()).isGreaterThanOrEqualTo(0);
        JdbcQueryCounter.start();
        metrics.get("dictalab.media.cleanup.pending").gauge().value();
        assertThat(JdbcQueryCounter.stop()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dictalab.testcontainers", matches="true")
    void listeningAggregateHandlesThousandUnitsAndHundredThousandSessions() {
        assertThat(db.queryForObject("SELECT current_database()", String.class)).startsWith("dictalab_test");
        AppUser owner = users.save(new AppUser("scale-query-" + UUID.randomUUID() + "@test.local", "Owner", "unused", Instant.now()));
        var source = db.queryForMap("SELECT id,lesson_id,audio_url FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1");
        String title = "scale-query-" + UUID.randomUUID();
        db.update("""
                INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active)
                SELECT ? || '-' || n::text,?,'Test','A1','Test',1,10000,TRUE FROM generate_series(1,1000) n
                """, title, title);
        db.update("""
                INSERT INTO listening_units(lesson_id,audio_url,title,level,duration_seconds,content_json,published)
                SELECT l.id,u.audio_url,?,u.level,u.duration_seconds,u.content_json,TRUE
                FROM listening_units u CROSS JOIN lessons l WHERE u.id=? AND l.title=?
                """, title, source.get("id"), title);
        db.update("""
                INSERT INTO lesson_sentences(lesson_id,position_index,transcript,audio_url,speech_rate)
                SELECT id,n,'Test',?,1 FROM lessons CROSS JOIN generate_series(1,5) n WHERE title=?
                """, source.get("audio_url"), title);
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,first_exposure,version)
                SELECT gen_random_uuid()::text,'scale-query',?,?,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,FALSE,0
                FROM generate_series(1,100000)
                """, owner.getId(), source.get("lesson_id"));
        db.execute("ANALYZE practice_sessions"); db.execute("ANALYZE listening_units");
        JdbcQueryCounter.start(); var units = listening.units(owner.getId()); var queries = JdbcQueryCounter.stop();
        assertThat(JdbcQueryCounter.selects(queries)).isEqualTo(1);
        var fixtureIds = db.queryForList("SELECT id FROM listening_units WHERE title=?", Long.class, title);
        assertThat(units.stream().filter(row -> fixtureIds.contains(((Number)row.get("id")).longValue())))
                .hasSize(1000).allSatisfy(row -> assertThat(((Number)row.get("prior_sessions")).longValue()).isEqualTo(100000));
        var plan = db.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + queries.get(0), String.class,
                owner.getId(),owner.getId(),owner.getId());
        System.out.println("QUERY_SCALE units=" + units.size() + " sessions=100000 plan=" + plan);
        db.update("DELETE FROM practice_sessions WHERE user_id=?", owner.getId());
        db.update("DELETE FROM listening_units WHERE title=?", title);
        db.update("DELETE FROM lessons WHERE title=?", title);
    }
}
