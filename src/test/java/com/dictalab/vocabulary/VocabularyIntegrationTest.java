package com.dictalab.vocabulary;

import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static com.dictalab.vocabulary.ReviewScheduler.Grade.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="dictalab.vocabulary.enabled=true")
@AutoConfigureMockMvc
class VocabularyIntegrationTest {
    @Autowired VocabularyRepository repo;
    @Autowired VocabularyContentService content;
    @Autowired VocabularyStudyService study;
    @Autowired VocabularyImportService imports;
    @Autowired ContextExerciseProvider contexts;
    @Autowired VocabularySourceService sources;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    String key() { return UUID.randomUUID().toString(); }
    long user() {
        String email=key()+"@vocabulary.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Vocabulary','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        return db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
    }
    DictaLabPrincipal principal(long user) { return new DictaLabPrincipal(user,db.queryForObject("SELECT email FROM app_users WHERE id=?",String.class,user),"Vocabulary","unused","USER",true); }
    org.springframework.test.web.servlet.request.RequestPostProcessor user(DictaLabPrincipal principal) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal);
    }
    String deck(long user) { return (String)content.createDeck(user,key(),new DeckEdit("Test","Notes","A1",0),false).get("id"); }
    NoteEdit edit(String term,String meaning,boolean reverse) { return new NoteEdit(term,meaning,"noun","","","",List.of(),0,reverse); }
    String note(long user,String deck,String term,boolean reverse) { return (String)content.addNote(user,key(),deck,edit(term,"nghĩa "+term,reverse),false).get("id"); }
    Session start(long user,String deck) { return study.start(user,key(),new SessionStart("SRS",deck)); }
    void correct(long user,Item item,String answer) { study.answer(user,key(),item.id(),new Answer(answer),false); study.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of())); }
    @Test void ownershipCsrfValidationAndAdminBoundaryAreEnforced() throws Exception {
        long owner=user(),other=user(); String deck=deck(owner);
        mvc.perform(get("/api/v1/vocabulary/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/vocabulary/decks/"+deck).with(user(principal(other)))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vocabulary/decks").with(user(principal(owner))).header("Idempotency-Key",key()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/vocabulary/decks").with(user(principal(owner))).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/vocabulary/decks").with(user(principal(owner)))).andExpect(status().isForbidden());
        mvc.perform(get("/vocabulary")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/vocabulary/decks?page=-1").with(user(principal(owner)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/vocabulary/session-items/unknown/grade").with(user(principal(owner))).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content("{\"grade\":\"GOOD\",\"targets\":[null]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void copiesCatalogOnceAcrossDecksWithoutDuplicatingCardsOrChangingSource() {
        long user=user(); String one=deck(user),two=deck(user);
        content.copy(user,key(),"vocab-starter-daily",one);
        int count=db.queryForObject("SELECT COUNT(*) FROM vocab_cards WHERE user_id=?",Integer.class,user);
        content.copy(user,key(),"vocab-starter-daily",two);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_cards WHERE user_id=?",Integer.class,user)).isEqualTo(count);
        assertThat(count).isEqualTo(14);
        var copied=repo.one("SELECT * FROM vocab_notes WHERE owner_user_id=? ORDER BY id LIMIT 1",user);
        String id=(String)copied.get("id"),source=(String)copied.get("source_note_id");
        content.updateNote(user,key(),id,edit("personal","nghĩa riêng",false),false);
        assertThat(repo.note(null,source).get("term")).isNotEqualTo("personal");
        content.membership(user,key(),one,id,true);
        assertThat(repo.note(user,id)).isNotEmpty();
    }
    @Test void sessionHidesAnswersUntilAttemptAndKeepsImmutableSnapshot() throws Exception {
        long user=user(); String deck=deck(user); String note=note(user,deck,"borrow",false);
        var session=start(user,deck); var item=session.items().get(0);
        assertThat(item.feedback()).isEmpty(); assertThat(item.prompt()).isEqualTo("nghĩa borrow");
        mvc.perform(get("/api/v1/vocabulary/sessions/"+session.id()).with(user(principal(user))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].feedback").isEmpty());
        content.updateNote(user,key(),note,edit("changed","nghĩa mới",false),false);
        var answer=study.answer(user,key(),item.id(),new Answer("borrow"),false);
        assertThat(answer.get("evaluation")).isEqualTo("CORRECT");
        study.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of()));
        assertThat(study.session(user,session.id()).status()).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT interval_days FROM vocab_cards WHERE note_id=?",Integer.class,note)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_review_logs WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void receiptRejectsChangedPayloadAndConcurrentGradesChangeScheduleOnlyOnce() throws Exception {
        long user=user(); String deck=deck(user); note(user,deck,"borrow",false);
        var item=start(user,deck).items().get(0); String answerKey=key();
        study.answer(user,answerKey,item.id(),new Answer("borrow"),false);
        assertThatThrownBy(()->study.answer(user,answerKey,item.id(),new Answer("other"),false)).isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
        String gradeKey=key(); var pool=Executors.newFixedThreadPool(8); var gate=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{gate.await();return study.grade(user,gradeKey,item.id(),new GradeRequest(GOOD,List.of()));}));
            gate.countDown(); for(var future:futures) assertThat(future.get(30,TimeUnit.SECONDS).get("grade").toString()).isEqualTo("GOOD");
        } finally { pool.shutdownNow(); }
        assertThat(db.queryForObject("SELECT revision FROM vocab_cards WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_review_logs WHERE user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void wrongOrRevealedAnswersCannotBeMarkedRememberedAndSiblingsAreBuried() {
        long user=user(); String deck=deck(user); note(user,deck,"borrow",true);
        var session=start(user,deck); assertThat(session.items()).hasSize(1);
        var item=session.items().get(0);
        study.answer(user,key(),item.id(),new Answer(""),true);
        assertThatThrownBy(()->study.grade(user,key(),item.id(),new GradeRequest(HARD,List.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        study.grade(user,key(),item.id(),new GradeRequest(AGAIN,List.of()));
        assertThat(start(user,deck).items()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_cards WHERE user_id=? AND buried_until IS NOT NULL",Integer.class,user)).isEqualTo(1);
    }
    @Test void practiceRecordsEvidenceWithoutAdvancingScheduledReviewAndSuspensionInvalidatesStaleSession() {
        long user=user(); String deck=deck(user); note(user,deck,"borrow",false);
        var practice=study.start(user,key(),new SessionStart("PRACTICE",deck)); correct(user,practice.items().get(0),"borrow");
        assertThat(db.queryForObject("SELECT revision FROM vocab_cards WHERE user_id=?",Integer.class,user)).isZero();
        var item=start(user,deck).items().get(0);
        var card=repo.one("SELECT * FROM vocab_cards WHERE user_id=?",user);
        content.suspend(user,key(),(String)card.get("id"),new Suspension(true,0));
        study.answer(user,key(),item.id(),new Answer("borrow"),false);
        assertThatThrownBy(()->study.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of()))).isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
    }
    @Test void dailyNewLimitIsEnforcedAndOtherUsersCannotSubmitItems() {
        long user=user(); String deck=deck(user);
        for(int i=0;i<12;i++) note(user,deck,"word"+i,false);
        var session=start(user,deck); assertThat(session.items()).hasSize(10);
        for(var item:session.items()) {
            String answer=((String)item.prompt()).substring("nghĩa ".length()); correct(user,item,answer);
        }
        assertThat(start(user,deck).items()).isEmpty();
        assertThatThrownBy(()->study.answer(user(),key(),session.items().get(0).id(),new Answer("x"),false)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
    }
    @Test void importPreviewIsAtomicRepeatableAndPreservesUnicodeAndSenses() {
        long user=user(); String deck=deck(user);
        String csv=String.join(",",VocabularyCsv.HEADER)+"\n\"bank\",ngân hàng,noun,,,\n\"bank\",bờ sông,noun,,,\n";
        var preview=imports.preview(user,key(),new ImportCsv(deck,csv)); String id=(String)preview.get("id");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_notes WHERE owner_user_id=?",Integer.class,user)).isZero();
        imports.confirm(user,key(),id); imports.confirm(user,key(),id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_notes WHERE owner_user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(imports.export(user,deck)).contains("ngân hàng","bờ sông");
        var bad=imports.preview(user,key(),new ImportCsv(deck,String.join(",",VocabularyCsv.HEADER)+"\nmissing\n"));
        assertThat((List<?>)bad.get("errors")).isNotEmpty();
        assertThat(bad.get("id")).isEqualTo("");
    }
    @Test void contextUsesMultipleKnownSensesAndNeverAdvancesEveryTargetSchedule() {
        long user=user(); String deck=deck(user); content.copy(user,key(),"vocab-starter-daily",deck);
        var selected=contexts.select(user,deck,5); assertThat(selected).isNotEmpty(); assertThat(selected.get(0).targets()).hasSize(2);
        var session=study.start(user,key(),new SessionStart("CONTEXT_TRANSLATION",deck)); var item=session.items().get(session.items().size()-1);
        study.answer(user,key(),item.id(),new Answer("Một bản dịch hợp lý khác"),false);
        var feedback=study.session(user,session.id()).items().get(item.position()).feedback();
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)feedback.get("targets");
        var results=targets.stream().map(t->new TargetResult((String)t.get("noteId"),"FORGOTTEN")).toList();
        study.grade(user,key(),item.id(),new GradeRequest(GOOD,results));
        assertThat(db.queryForObject("SELECT SUM(revision) FROM vocab_cards WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(repo.one("SELECT * FROM vocab_review_logs WHERE item_id=?",item.id()).get("grading_source")).isEqualTo("SELF");
        db.update("UPDATE vocab_study_sessions SET created_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(172800)),session.id());
        assertThat(contexts.select(user,deck,1).get(0).targets().stream().map(t->t.get("noteId"))).containsExactlyInAnyOrderElementsOf(targets.stream().map(t->t.get("noteId")).toList());
    }
    long oldWord(long user,String text) {
        String slug=key();
        db.update("INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active) VALUES (?,'Vocabulary source','Vocabulary source','A1','test',1,1,TRUE)",slug);
        long lesson=db.queryForObject("SELECT id FROM lessons WHERE slug=?",Long.class,slug);
        String session=key();
        db.update("INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at) VALUES (?,'vocab-test',?,?,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",session,user,lesson);
        db.update("INSERT INTO lesson_sentences(lesson_id,position_index,transcript) VALUES (?,?,?)",lesson,10000+(int)user,text);
        long sentence=db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=? AND position_index=?",Long.class,lesson,10000+(int)user);
        db.update("INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed) VALUES (?,?,CURRENT_TIMESTAMP,TRUE)",session,sentence);
        long attempt=db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,session);
        db.update("INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,final_correct,error_type) VALUES (?,0,1,FALSE,FALSE,'UNKNOWN')",attempt);
        return db.queryForObject("SELECT id FROM word_attempts WHERE sentence_attempt_id=?",Long.class,attempt);
    }
    @Test void oldLessonsKeepIndependentEvidenceAndCombineCompatibleSenses() {
        long user=user(); String one=deck(user),two=deck(user);
        long borrow=oldWord(user,"borrow"),umbrella=oldWord(user,"umbrella");
        var borrowEdit=edit("borrow","mượn",false);
        var saved=sources.save(user,key(),one,new SourceNote(borrow,null,borrowEdit));
        sources.save(user,key(),two,new SourceNote(borrow,null,borrowEdit));
        sources.save(user,key(),one,new SourceNote(umbrella,null,edit("umbrella","ô",false)));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",Integer.class,two,saved.get("id"))).isEqualTo(1);
        long other=user(); String otherDeck=deck(other);
        assertThatThrownBy(()->sources.save(other,key(),otherDeck,new SourceNote(borrow,null,borrowEdit))).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
        long repeatedBorrow=oldWord(user,"borrow");
        assertThat(sources.save(user,key(),one,new SourceNote(repeatedBorrow,null,borrowEdit)).get("id")).isEqualTo(saved.get("id"));
        assertThat(sources.suggestions(user,1).items()).allMatch(row->row.get("evidence").equals("LISTENING_MISS"));
        db.update("UPDATE word_attempts SET learner_reported_error_type='UNKNOWN' WHERE id=?",borrow);
        assertThat(sources.suggestions(user,1).items()).anyMatch(row->row.get("evidence").equals("UNKNOWN_WORD"));
        var selected=contexts.select(user,one,5);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).source()).isEqualTo("CURATED");
        assertThat(selected.get(0).targets()).hasSize(2);
        assertThat(selected.get(0).targets()).allMatch(t->!((List<?>)t.get("sourceLessons")).isEmpty());
        db.update("DELETE FROM practice_sessions WHERE user_id=?",user);
        assertThat(content.note(user,(String)saved.get("id")).get("sources")).asList().hasSize(2);
        content.updateNote(user,key(),(String)saved.get("id"),edit("borrow","mang thai",false),false);
        assertThat(contexts.select(user,one,5)).isEmpty();
    }
    @Test void personalExampleFallbackPersistsAndDoesNotChangeSchedule() {
        long user=user(); String deck=deck(user);
        content.addNote(user,key(),deck,new NoteEdit("custom","nghĩa riêng","noun","A custom example.","Một ví dụ riêng.","",List.of(),0,false),false);
        var session=study.start(user,key(),new SessionStart("CONTEXT_TRANSLATION",deck));
        assertThat(session.items()).hasSize(1);
        var item=session.items().get(0);
        study.answer(user,key(),item.id(),new Answer("Một ví dụ riêng."),false);
        var feedback=study.session(user,session.id()).items().get(0).feedback();
        assertThat(feedback.get("source")).isEqualTo("PERSONAL_EXAMPLE");
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)feedback.get("targets");
        study.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of(new TargetResult((String)targets.get(0).get("noteId"),"REMEMBERED"))));
        assertThat(db.queryForObject("SELECT revision FROM vocab_cards WHERE user_id=?",Integer.class,user)).isZero();
    }
    @Test void thousandRowImportIsAtomicAndReusesAcrossDecks() {
        long user=user(); String one=deck(user),two=deck(user);
        StringBuilder csv=new StringBuilder(String.join(",",VocabularyCsv.HEADER)).append('\n');
        for(int i=0;i<1000;i++) csv.append("term").append(i).append(",nghĩa,noun,,,\n");
        String id=(String)imports.preview(user,key(),new ImportCsv(one,csv.toString())).get("id");
        assertThat(imports.confirm(user,key(),id).get("added")).isEqualTo(1000);
        String second=(String)imports.preview(user,key(),new ImportCsv(two,csv.toString())).get("id");
        assertThat(imports.confirm(user,key(),second).get("reused")).isEqualTo(1000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_cards WHERE user_id=?",Integer.class,user)).isEqualTo(1000);
        String failed=(String)imports.preview(user,key(),new ImportCsv(one,String.join(",",VocabularyCsv.HEADER)+"\nrollback,hoàn tác,noun,,,\n")).get("id");
        db.update("UPDATE vocab_decks SET status='ARCHIVED' WHERE id=?",one);
        assertThatThrownBy(()->imports.confirm(user,key(),failed)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_notes WHERE owner_user_id=? AND term='rollback'",Integer.class,user)).isZero();
        assertThat(repo.one("SELECT result_json FROM vocab_imports WHERE id=?",failed).get("result_json")).isNull();
    }
    @Test void expiredSessionsRejectWritesAndMeaningRecallHasIndependentSchedule() {
        long user=user(); String deck=deck(user); String note=note(user,deck,"borrow",true);
        var expired=start(user,deck);
        db.update("UPDATE vocab_study_sessions SET expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),expired.id());
        assertThat(study.session(user,expired.id()).status()).isEqualTo("ABANDONED");
        assertThatThrownBy(()->study.answer(user,key(),expired.items().get(0).id(),new Answer("borrow"),false)).isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
        db.update("UPDATE vocab_cards SET suspended=TRUE WHERE note_id=? AND card_type='WORD_RECALL'",note);
        var next=start(user,deck); var item=next.items().get(0);
        assertThat(item.kind()).isEqualTo("MEANING_RECALL");
        study.answer(user,key(),item.id(),new Answer("Một nghĩa diễn đạt khác"),false);
        var result=study.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of()));
        assertThat(result.get("gradingSource")).isEqualTo("SELF");
        assertThat(db.queryForObject("SELECT revision FROM vocab_cards WHERE note_id=? AND card_type='WORD_RECALL'",Integer.class,note)).isZero();
        assertThat(db.queryForObject("SELECT revision FROM vocab_cards WHERE note_id=? AND card_type='MEANING_RECALL'",Integer.class,note)).isEqualTo(1);
    }
    @Test void dailyAllowanceResetsAtUtcPlusSevenMidnight() {
        long user=user(); String deck=deck(user); note(user,deck,"one",false); note(user,deck,"two",false);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        Instant beforeMidnight=Instant.parse("2026-10-28T16:59:59Z");
        db.update("UPDATE vocab_cards SET due_at=? WHERE user_id=?",java.sql.Timestamp.from(beforeMidnight.minusSeconds(60)),user);
        var before=new VocabularyStudyService(repo,new VocabularyProperties(true,1,20),new Sm2Scheduler(),new RuleAnswerEvaluator(),contexts,Clock.fixed(beforeMidnight,ZoneOffset.UTC));
        var session=transaction.execute(s->before.start(user,key(),new SessionStart("SRS",deck)));
        assertThat(session.items()).hasSize(1); var item=session.items().get(0);
        transaction.executeWithoutResult(s->before.answer(user,key(),item.id(),new Answer(item.prompt().substring("nghĩa ".length())),false));
        transaction.executeWithoutResult(s->before.grade(user,key(),item.id(),new GradeRequest(GOOD,List.of())));
        assertThat(db.queryForObject("SELECT learning_day FROM vocab_review_logs WHERE user_id=?",LocalDate.class,user)).isEqualTo(LocalDate.of(2026,10,28));
        assertThat(transaction.execute(s->before.start(user,key(),new SessionStart("SRS",deck))).items()).isEmpty();
        var after=new VocabularyStudyService(repo,new VocabularyProperties(true,1,20),new Sm2Scheduler(),new RuleAnswerEvaluator(),contexts,Clock.fixed(beforeMidnight.plusSeconds(2),ZoneOffset.UTC));
        assertThat(transaction.execute(s->after.start(user,key(),new SessionStart("SRS",deck))).items()).hasSize(1);
    }
    @Test void adminAuthoringPublicationAndContextValidationUseSecuredApi() throws Exception {
        long admin=user(); db.update("UPDATE app_users SET role='ADMIN' WHERE id=?",admin);
        var principal=new DictaLabPrincipal(admin,db.queryForObject("SELECT email FROM app_users WHERE id=?",String.class,admin),"Admin","unused","ADMIN",true);
        var created=mvc.perform(post("/api/admin/vocabulary/decks").with(user(principal)).with(csrf())
                .header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(new DeckEdit("Editorial","","A1",0))))
                .andExpect(status().isOk()).andReturn();
        String deck=(String)repo.object(created.getResponse().getContentAsString()).get("id");
        String one=(String)content.addNote(admin,key(),deck,edit("bring","mang theo",false),true).get("id");
        String two=(String)content.addNote(admin,key(),deck,edit("coat","áo khoác",false),true).get("id");
        var context=new ContextEdit("Bring your coat.","Hãy mang theo áo khoác của bạn.","A1",List.of(),List.of(new ContextTarget(one,"Bring"),new ContextTarget(two,"coat")),true,0);
        mvc.perform(post("/api/admin/vocabulary/contexts").with(user(principal)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(context)))
                .andExpect(status().isOk());
        var invalid=new ContextEdit("Bring your coat.","Hãy mang theo áo khoác.","A1",List.of(),List.of(new ContextTarget(one,"missing"),new ContextTarget(two,"coat")),true,0);
        mvc.perform(post("/api/admin/vocabulary/contexts").with(user(principal)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(invalid)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/vocabulary/decks/"+deck+"/publication").with(user(principal)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(new Publication(true,0))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/vocabulary/catalog/decks/"+deck).with(user(principal(user())))).andExpect(status().isOk());
        mvc.perform(patch("/api/admin/vocabulary/decks/"+deck).with(user(principal)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(new DeckEdit("Stale","","A1",0))))
                .andExpect(status().isConflict());
    }
}
