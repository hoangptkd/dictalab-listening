package com.dictalab.service;

import com.dictalab.domain.ReplayEventType;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"dictalab.learning.enabled=true", "dictalab.learning.index-delay-ms=600000"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ListeningLearningIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired PracticeService practice;
    @Autowired PracticeSubmissions receipts;
    @Autowired ListeningLearning learning;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    long user() {
        String email=UUID.randomUUID()+"@learning.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Learner','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        return db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor authenticated(long id) {
        String email=db.queryForObject("SELECT email FROM app_users WHERE id=?",String.class,id);
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                new com.dictalab.security.DictaLabPrincipal(id,email,"Learner","unused","USER",true));
    }
    @Test void learningHttpRequiresAuthenticationCsrfOwnershipAndBoundedAnswers() throws Exception {
        String path="/api/v1/listening-learning";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        long owner=user(),attempt=completed(owner);collect(owner,attempt);
        var challenge=learning.startReview(owner).get(0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(authenticated(owner)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items").isArray());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/reviews").with(authenticated(owner)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/reviews/"+challenge.id()).with(authenticated(user())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/reviews/"+challenge.id()+"/answer")
                .with(authenticated(owner)).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"answer\":\""+"x".repeat(201)+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(db.queryForObject("SELECT revision FROM listening_review_cards WHERE user_id=?",Long.class,owner)).isZero();
    }
    long lesson(String title,String transcript,String audio) {
        String slug=UUID.randomUUID().toString();
        db.update("INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active) VALUES (?,?,?,'A1','test',1,1,TRUE)",slug,title,title);
        long id=db.queryForObject("SELECT id FROM lessons WHERE slug=?",Long.class,slug);
        db.update("INSERT INTO lesson_sentences(lesson_id,position_index,transcript,audio_url) VALUES (?,1,?,?)",id,transcript,audio);
        long sentence=db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=?",Long.class,id);
        // Fixture index is independent of the matching query under test.
        db.update("INSERT INTO listening_review_sources(sentence_id,transcript,transcript_hash) VALUES (?,?,?)",sentence,transcript,AiBackgroundJobs.hash(transcript));
        var words=new TranscriptTokenizer().words(transcript);
        for(int i=0;i<words.size();i++) db.update("INSERT INTO listening_review_tokens(sentence_id,word_index,target) VALUES (?,?,?)",sentence,i,words.get(i).toLowerCase());
        return id;
    }
    ApiDtos.PracticeStartResponse start(long user) {
        long lesson=lesson("Source", "She buys milk.","/test-original-"+UUID.randomUUID()+".mp3");
        var started=practice.start(user,new ApiDtos.StartSessionRequest(lesson));
        practice.replay(user,started.sessionId(),started.sentence().sentenceId(),new ApiDtos.ReplayRequest(ReplayEventType.STARTED));
        return started;
    }
    <T> List<T> race(int count,Supplier<T> call) throws Exception {
        var pool=Executors.newFixedThreadPool(count); var gate=new CountDownLatch(1);
        try {
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<count;i++) futures.add(pool.submit(()->{gate.await();return call.get();}));
            gate.countDown(); List<T> result=new ArrayList<>();
            for(var future:futures) result.add(future.get(45,TimeUnit.SECONDS));
            return result;
        } finally {pool.shutdownNow();}
    }
    @Test void tenDuplicateChecksAreOneEventButDistinctKeysRemainRealAttempts() throws Exception {
        long user=user();var session=start(user);var request=new ApiDtos.CheckWordRequest("bye",123L);
        Supplier<ApiDtos.SentenceState> write=()->practice.checkWord(user,session.sessionId(),session.sentence().sentenceId(),1,request);
        var result=race(10,()->receipts.execute(user,session.sessionId(),"check-one", "check/1",request,ApiDtos.SentenceState.class,write));
        assertThat(result).allMatch(result.get(0)::equals);
        assertThat(events(session.sessionId())).isEqualTo(1);
        receipts.execute(user,session.sessionId(),"check-two","check/1",request,ApiDtos.SentenceState.class,write);
        assertThat(events(session.sessionId())).isEqualTo(2);
        assertThatThrownBy(()->receipts.execute(user,session.sessionId(),"check-one","check/1",new ApiDtos.CheckWordRequest("changed"),ApiDtos.SentenceState.class,write)).isInstanceOf(InvalidPracticeStateException.class);
        assertThatThrownBy(()->receipts.execute(user(),session.sessionId(),"check-one","check/1",request,ApiDtos.SentenceState.class,write)).isInstanceOf(ResourceNotFoundException.class);
    }
    int events(String session) { return db.queryForObject("SELECT COUNT(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id JOIN sentence_attempts a ON a.id=w.sentence_attempt_id WHERE a.practice_session_id=?",Integer.class,session); }

    @Test void rollbackRemovesReceiptAndAttemptTogether() {
        long user=user();var session=start(user);var request=new ApiDtos.CheckWordRequest("wrong");
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            receipts.execute(user,session.sessionId(),"rollback-key","check/1",request,ApiDtos.SentenceState.class,
                    ()->practice.checkWord(user,session.sessionId(),session.sentence().sentenceId(),1,request));
            throw new IllegalStateException("injected");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events(session.sessionId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_submission_receipts WHERE session_id=?",Integer.class,session.sessionId())).isZero();
    }

    long completed(long user) {
        var session=start(user);long sentence=session.sentence().sentenceId();
        practice.checkWord(user,session.sessionId(),sentence,0,new ApiDtos.CheckWordRequest("She"));
        practice.checkWord(user,session.sessionId(),sentence,1,new ApiDtos.CheckWordRequest("bye"));
        practice.checkWord(user,session.sessionId(),sentence,1,new ApiDtos.CheckWordRequest("buys"));
        practice.checkWord(user,session.sessionId(),sentence,2,new ApiDtos.CheckWordRequest("milk"));
        var request=new ApiDtos.CompleteSentenceRequest(10);
        var result=receipts.execute(user,session.sessionId(),"complete-key",sentence+"/complete",request,ApiDtos.CompleteSentenceResponse.class,
                ()->practice.completeSentence(user,session.sessionId(),sentence,request));
        assertThat(result.lessonCompleted()).isTrue();
        var repeated=receipts.execute(user,session.sessionId(),"complete-other",sentence+"/complete",new ApiDtos.CompleteSentenceRequest(99),ApiDtos.CompleteSentenceResponse.class,
                ()->{throw new AssertionError("Must replay natural completion");});
        assertThat(repeated).isEqualTo(result);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM learning_jobs j JOIN sentence_attempts a ON a.id=j.attempt_id WHERE a.practice_session_id=?",Integer.class,session.sessionId())).isEqualTo(1);
        return db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,session.sessionId());
    }
    void collect(long user,long attempt) {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE",Long.class,user);
            learning.collect(attempt);
        });
    }
    @Test void firstEvidenceIsDeduplicatedAndLaterCorrectionDoesNotReplaceIt() {
        long user=user(),attempt=completed(user);collect(user,attempt);collect(user,attempt);
        var bank=learning.bank(user);
        assertThat((List<?>)bank.get("items")).hasSize(1);
        assertThat(db.queryForObject("SELECT heard FROM listening_error_evidence WHERE user_id=? AND first_correct=FALSE",String.class,user)).isEqualTo("bye");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_error_evidence WHERE user_id=?",Integer.class,user)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_review_cards WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat((List<?>)learning.bank(user()).get("items")).isEmpty();
    }
    @Test void differentUsersCanCreateReviewsAcrossLessonsInOppositeOrder() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())),"PostgreSQL row-lock semantics required");
        long first=user(),second=user();
        collect(first,completed(first));collect(second,completed(second));
        String alpha="alpha"+UUID.randomUUID().toString().replace("-","");
        String beta="beta"+UUID.randomUUID().toString().replace("-","");
        long lessonA=lesson("Shared A","He "+alpha+" bread.","/shared-a-"+UUID.randomUUID()+".mp3");
        long lessonB=lesson("Shared B","He "+beta+" bread.","/shared-b-"+UUID.randomUUID()+".mp3");
        for(long owner:new long[]{first,second}) {
            long source=db.queryForObject("SELECT source_word_id FROM listening_review_cards WHERE user_id=?",Long.class,owner);
            db.update("UPDATE listening_review_cards SET target=?,due_at=TIMESTAMP '2000-01-01' WHERE user_id=?",owner==first?alpha:beta,owner);
            db.update("INSERT INTO listening_review_cards(id,user_id,target,source_word_id,due_at) VALUES (?,?,?,?,TIMESTAMP '2000-01-02')",
                    UUID.randomUUID().toString(),owner,owner==first?beta:alpha,source);
        }
        var gate=new CountDownLatch(2);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<List<ListeningLearning.Challenge>>> futures=new ArrayList<>();
            for(int n=0;n<2;n++) {
                long owner=n==0?first:second,heldLesson=n==0?lessonA:lessonB;
                futures.add(pool.submit(()->new TransactionTemplate(manager).execute(status->{
                    // Both real transactions hold their first lesson before entering the opposite-order path.
                    db.queryForObject("SELECT id FROM lessons WHERE id=? FOR SHARE",Long.class,heldLesson);
                    gate.countDown();
                    try { if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Second transaction did not enter"); }
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                    return learning.startReview(owner);
                })));
            }
            for(var future:futures)assertThat(future.get(20,TimeUnit.SECONDS)).hasSize(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_review_attempts r JOIN listening_review_cards c ON c.id=r.card_id WHERE c.user_id IN (?,?)",Integer.class,first,second)).isEqualTo(4);
        } finally { pool.shutdownNow(); }
    }
    @Test void reviewPublicationReadLockStillBlocksContentChangesUntilCommit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())),"PostgreSQL row-lock semantics required");
        long owner=user();collect(owner,completed(owner));
        String target="publication"+UUID.randomUUID().toString().replace("-","");
        long candidate=lesson("Publication guard","He "+target+" bread.","/publication-"+UUID.randomUUID()+".mp3");
        db.update("UPDATE listening_review_cards SET target=? WHERE user_id=?",target,owner);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var reader=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                var result=learning.startReview(owner);entered.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Reader was not released"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                return result;
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                db.execute("SET LOCAL lock_timeout='300ms'");
                db.update("UPDATE lessons SET active=FALSE WHERE id=?",candidate);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause()
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(error->assertThat(((java.sql.SQLException)error).getSQLState()).isEqualTo("55P03"));
            release.countDown();assertThat(reader.get(10,TimeUnit.SECONDS)).hasSize(1);
            assertThat(db.queryForObject("SELECT active FROM lessons WHERE id=?",Boolean.class,candidate)).isTrue();
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void collectorFailureRollsBackEveryProjectionAndRetryRestoresThem() {
        long user=user(),attempt=completed(user);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{learning.collect(attempt);throw new IllegalStateException("crash");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_error_evidence WHERE user_id=?",Integer.class,user)).isZero();
        collect(user,attempt);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM listening_error_evidence WHERE user_id=?",Integer.class,user)).isEqualTo(3);
    }
    @Test void newContextReviewGradesOnceAndPreservesScoresAndExposure() throws Exception {
        long user=user(),attempt=completed(user);collect(user,attempt);
        lesson("Transfer","He buys bread.","/alternative-"+UUID.randomUUID()+".mp3");
        var challenge=learning.startReview(user).get(0);
        assertThat(challenge.prompt()).contains("_____").doesNotContain("buys");
        assertThat(challenge.newContext()).isTrue();
        assertThat(learning.startReview(user).get(0)).isEqualTo(challenge);
        assertThatThrownBy(()->learning.answer(user,challenge.id(),"buys",false)).isInstanceOf(InvalidPracticeStateException.class);
        assertThatThrownBy(()->learning.answer(user(),challenge.id(),"buys",false)).isInstanceOf(ResourceNotFoundException.class);
        learning.played(user,challenge.id());
        var results=race(10,()->learning.answer(user,challenge.id(),"buys",false));
        assertThat(results).allMatch(results.get(0)::equals);
        assertThat(results.get(0).stage()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT revision FROM listening_review_cards WHERE user_id=?",Long.class,user)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audio_exposure_events WHERE user_id=? AND source='SPACED_REVIEW'",Integer.class,user)).isEqualTo(1);
        assertThatThrownBy(()->learning.answer(user,challenge.id(),"different",false)).isInstanceOf(InvalidPracticeStateException.class);
        assertThat(db.queryForObject("SELECT total_practice_seconds FROM practice_sessions s JOIN sentence_attempts a ON a.practice_session_id=s.id WHERE a.id=?",Integer.class,attempt)).isEqualTo(10);
        assertThat(learning.startReview(user)).isEmpty();
    }
    @Test void wrongOrRevealedReviewDoesNotPromoteAndAbandonDoesNotReschedule() {
        long user=user(),attempt=completed(user);collect(user,attempt);
        var challenge=learning.startReview(user).get(0);
        assertThat(db.queryForObject("SELECT revision FROM listening_review_cards WHERE user_id=?",Long.class,user)).isZero();
        learning.played(user,challenge.id());
        var result=learning.answer(user,challenge.id(),"",true);
        assertThat(result.assisted()).isTrue(); assertThat(result.correct()).isFalse(); assertThat(result.stage()).isZero();
        assertThat(result.dueAt()).isAfter(java.time.Instant.now().plusSeconds(550));
    }
    @Test void staleReviewCannotRegressSchedule() {
        long user=user(),attempt=completed(user);collect(user,attempt);var challenge=learning.startReview(user).get(0);
        learning.played(user,challenge.id());
        db.update("UPDATE listening_review_cards SET revision=revision+1,stage=4 WHERE user_id=?",user);
        assertThatThrownBy(()->learning.answer(user,challenge.id(),"buys",false)).isInstanceOf(InvalidPracticeStateException.class);
        assertThat(db.queryForObject("SELECT stage FROM listening_review_cards WHERE user_id=?",Integer.class,user)).isEqualTo(4);
    }
    @Test void reviewUsesAnotherUnheardAudioAndReadOnlyRecoveryReturnsCommittedResult() {
        long user=user(),attempt=completed(user);collect(user,attempt);
        lesson("Transfer one","He buys bread.","/fresh-one-"+UUID.randomUUID()+".mp3");
        lesson("Transfer two","He buys food.","/fresh-two-"+UUID.randomUUID()+".mp3");
        var first=learning.startReview(user).get(0);learning.played(user,first.id());
        var result=learning.answer(user,first.id(),"buys",false);
        assertThat(learning.review(user,first.id()).result()).isEqualTo(result);
        assertThatThrownBy(()->learning.review(user(),first.id())).isInstanceOf(ResourceNotFoundException.class);
        db.update("UPDATE listening_review_cards SET due_at=CURRENT_TIMESTAMP WHERE user_id=?",user);
        var next=learning.startReview(user).get(0);
        assertThat(next.audioUrl()).isNotEqualTo(first.audioUrl());assertThat(next.newContext()).isTrue();
    }
    @Test void defaultLearnerLabelIsNotTreatedAsExplicitSelfReport() {
        long user=user(),attempt=completed(user);collect(user,attempt);
        assertThat(db.queryForObject("SELECT learner_reported_error_type FROM listening_error_evidence WHERE user_id=? AND first_correct=FALSE",String.class,user)).isNull();
        long other=user();var session=start(other);long sentence=session.sentence().sentenceId();
        practice.checkWord(other,session.sessionId(),sentence,1,new ApiDtos.CheckWordRequest("bye"));
        practice.classifyWord(other,session.sessionId(),sentence,new ApiDtos.ClassifyWordRequest(1,com.dictalab.domain.LearnerErrorType.UNKNOWN));
        practice.checkWord(other,session.sessionId(),sentence,0,new ApiDtos.CheckWordRequest("She"));
        practice.checkWord(other,session.sessionId(),sentence,1,new ApiDtos.CheckWordRequest("buys"));
        practice.checkWord(other,session.sessionId(),sentence,2,new ApiDtos.CheckWordRequest("milk"));
        practice.completeSentence(other,session.sessionId(),sentence,new ApiDtos.CompleteSentenceRequest(10));
        collect(other,db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,session.sessionId()));
        assertThat(db.queryForObject("SELECT learner_reported_error_type FROM listening_error_evidence WHERE user_id=? AND first_correct=FALSE",String.class,other)).isEqualTo("UNKNOWN");
    }
}
