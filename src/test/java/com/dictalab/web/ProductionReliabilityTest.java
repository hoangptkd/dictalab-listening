package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.mail.host=test.invalid","dictalab.mail-from=test@dictalab.local","dictalab.email.dispatch-delay-ms=600000","management.health.mail.enabled=false"})
class ProductionReliabilityTest {
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired PracticeService practice;
    @Autowired AccountManagementService accounts;
    @Autowired AccountService registration;
    @Autowired ListeningComprehensionService listening;
    @Autowired EmailOutbox outbox;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @MockitoBean ShopAiKeyClient ai;
    @MockitoBean JavaMailSender mail;

    private AppUser user(){return users.saveAndFlush(new AppUser("reliability-"+UUID.randomUUID()+"@test.local","Learner","unused",Instant.now()));}
    private long lesson(){return db.queryForObject("SELECT id FROM lessons WHERE active=TRUE AND content_origin<>'BRITISH_COUNCIL_PERSONAL' ORDER BY id LIMIT 1",Long.class);}

    private java.util.List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var first=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return a.call();});
            var second=pool.submit(()->{start.await(5,TimeUnit.SECONDS);return b.call();});start.countDown();
            return java.util.List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }

    @Test void concurrentRegistrationCreatesExactlyOneNormalizedAccount() throws Exception {
        String email="race-"+UUID.randomUUID()+"@test.local";
        Callable<Integer> call=()->{
            try{registration.register(new com.dictalab.dto.AuthDtos.RegisterRequest("Learner",email.toUpperCase(),"Test-only-password-2026"));return 1;}
            catch(com.dictalab.exception.EmailAlreadyExistsException duplicate){return 0;}
        };
        assertThat(race(call,call)).containsExactlyInAnyOrder(1,0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE email=?",Integer.class,email)).isEqualTo(1);
    }

    @Test void onlyOneConsumerCanUseAnEmailToken() throws Exception {
        var owner=user();String token=UUID.randomUUID().toString();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("INSERT INTO account_tokens(token_hash,user_id,purpose,created_at,expires_at) VALUES(?,?,'VERIFY_EMAIL',?,?)",
                hash,owner.getId(),java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
        Callable<Integer> call=()->{
            try{accounts.consumeToken(token,"VERIFY_EMAIL",null);return 1;}
            catch(org.springframework.web.server.ResponseStatusException used){assertThat(used.getStatusCode().value()).isEqualTo(400);return 0;}
        };
        assertThat(race(call,call)).containsExactlyInAnyOrder(1,0);
        assertThat(db.queryForObject("SELECT used_at IS NOT NULL FROM account_tokens WHERE token_hash=?",Boolean.class,hash)).isTrue();
    }

    @Test void parallelAdminChangesKeepOneEnabledAdministrator() throws Exception {
        var prior=db.queryForList("SELECT id FROM app_users WHERE role='ADMIN' AND enabled=TRUE",Long.class);
        var a=user();var b=user();
        db.update("UPDATE app_users SET enabled=FALSE WHERE role='ADMIN'");
        db.update("UPDATE app_users SET role='ADMIN' WHERE id IN (?,?)",a.getId(),b.getId());
        try{
            Callable<Integer> removeA=()->{try{accounts.setEnabled(b.getId(),a.getId(),false,"race");return 1;}
                catch(org.springframework.web.server.ResponseStatusException policy){assertThat(policy.getStatusCode().value()).isEqualTo(409);return 0;}};
            Callable<Integer> removeB=()->{try{accounts.setEnabled(a.getId(),b.getId(),false,"race");return 1;}
                catch(org.springframework.web.server.ResponseStatusException policy){assertThat(policy.getStatusCode().value()).isEqualTo(409);return 0;}};
            assertThat(race(removeA,removeB)).containsExactlyInAnyOrder(1,0);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND enabled=TRUE",Integer.class)).isEqualTo(1);
        }finally{
            db.update("UPDATE app_users SET role='USER' WHERE id IN (?,?)",a.getId(),b.getId());
            prior.forEach(id->db.update("UPDATE app_users SET enabled=TRUE WHERE id=?",id));
        }
    }

    @Test void duplicateHintAndCompleteNeverDoubleCount() throws Exception {
        var owner=user();var started=practice.start(owner.getId(),new ApiDtos.StartSessionRequest(lesson()));
        String id=started.sessionId();long sentence=started.sentence().sentenceId();
        practice.replay(owner.getId(),id,sentence,new ApiDtos.ReplayRequest(com.dictalab.domain.ReplayEventType.STARTED));
        Callable<Integer> hint=()->{practice.useHint(owner.getId(),id,sentence,new ApiDtos.HintRequest(com.dictalab.domain.HintType.REVEAL_SENTENCE,null));return 1;};
        assertThat(race(hint,hint)).containsOnly(1);
        assertThat(db.queryForObject("SELECT reveal_count FROM practice_sessions WHERE id=?",Integer.class,id)).isEqualTo(1);
        practice.replay(owner.getId(),id,sentence,new ApiDtos.ReplayRequest(com.dictalab.domain.ReplayEventType.COMPLETED));
        db.update("UPDATE sentence_attempts SET started_at=? WHERE practice_session_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),id);
        Callable<Integer> finish=()->{
            try{practice.completeSentence(owner.getId(),id,sentence,new ApiDtos.CompleteSentenceRequest(5));return 1;}
            catch(com.dictalab.exception.InvalidPracticeStateException duplicate){return 0;}
        };
        assertThat(race(finish,finish)).containsExactlyInAnyOrder(1,0);
        assertThat(db.queryForObject("SELECT time_spent_seconds FROM sentence_attempts WHERE practice_session_id=? AND sentence_id=?",Integer.class,id,sentence)).isEqualTo(5);
    }

    @Test void invalidStoredCountersAndEmailsAreRejected(){
        var owner=user();
        assertThatThrownBy(()->db.update("UPDATE app_users SET email=UPPER(email) WHERE id=?",owner.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("UPDATE app_users SET auth_version=-1 WHERE id=?",owner.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void simultaneousStartsResumeTheSameSoloSession() throws Exception {
        var owner=user();long lesson=lesson();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            Callable<String> call=()->{start.await(5,TimeUnit.SECONDS);return practice.start(owner.getId(),new ApiDtos.StartSessionRequest(lesson)).sessionId();};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM practice_sessions WHERE user_id=? AND lesson_id=? AND status='IN_PROGRESS' AND room_id IS NULL",Integer.class,owner.getId(),lesson)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void concurrentChecksKeepEveryEventAndCounter() throws Exception {
        var owner=user();var session=practice.start(owner.getId(),new ApiDtos.StartSessionRequest(lesson()));
        long sentence=session.sentence().sentenceId();
        practice.replay(owner.getId(),session.sessionId(),sentence,new ApiDtos.ReplayRequest(com.dictalab.domain.ReplayEventType.STARTED));
        int index=session.sentence().words().stream().filter(w->!w.prefilled()).findFirst().orElseThrow().index();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            Callable<Void> call=()->{start.await(5,TimeUnit.SECONDS);practice.checkWord(owner.getId(),session.sessionId(),sentence,index,new ApiDtos.CheckWordRequest("wrong"));return null;};
            var a=pool.submit(call);var b=pool.submit(()->{start.await(5,TimeUnit.SECONDS);practice.checkWord(owner.getId(),session.sessionId(),sentence,index,new ApiDtos.CheckWordRequest("different"));return null;});
            start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertThat(db.queryForObject("""
                    SELECT w.attempt_count FROM word_attempts w JOIN sentence_attempts s ON s.id=w.sentence_attempt_id
                    WHERE s.practice_session_id=? AND w.word_index=?
                    """,Integer.class,session.sessionId(),index)).isEqualTo(2);
            assertThat(db.queryForObject("""
                    SELECT count(*) FROM word_attempt_events e JOIN word_attempts w ON w.id=e.word_attempt_id
                    JOIN sentence_attempts s ON s.id=w.sentence_attempt_id WHERE s.practice_session_id=? AND w.word_index=?
                    """,Integer.class,session.sessionId(),index)).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }

    @Test void emailDeliveryRunsAfterCommitAndRollbackLeavesNoDelivery(){
        var owner=user();
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->{accounts.requestVerification(owner.getId(),"127.0.0.1");throw new IllegalStateException("rollback");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE token_hash IN (SELECT token_hash FROM account_tokens WHERE user_id=?)",Integer.class,owner.getId())).isZero();
        accounts.requestVerification(owner.getId(),"127.0.0.1");
        verify(mail,never()).send(any(org.springframework.mail.SimpleMailMessage.class));
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return null;})
                .when(mail).send(any(org.springframework.mail.SimpleMailMessage.class));
        outbox.deliver();
        verify(mail,atLeastOnce()).send(any(org.springframework.mail.SimpleMailMessage.class));
        assertThat(db.queryForObject("SELECT status FROM email_outbox WHERE token_hash IN (SELECT token_hash FROM account_tokens WHERE user_id=?)",String.class,owner.getId())).isEqualTo("DELIVERED");
    }

    @Test void passwordResetEmailExplainsExpiryNewPasswordAndNextLogin() {
        var owner = user();
        accounts.requestReset(owner.getEmail(), "127.0.0.1");
        var message = db.queryForMap("SELECT subject,body FROM email_outbox WHERE recipient=?", owner.getEmail());
        assertThat((String) message.get("subject")).contains("Đặt lại mật khẩu");
        assertThat((String) message.get("body")).contains("30 phút", "8 ký tự", "chữ cái và chữ số",
                "đăng nhập lại", "/reset-password?token=", "Nếu bạn không yêu cầu");
        var duration = db.queryForObject("SELECT created_at,expires_at FROM account_tokens WHERE user_id=? AND purpose='RESET_PASSWORD'",
                (row, index) -> java.time.Duration.between(row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("expires_at").toInstant()), owner.getId());
        assertThat(duration).isEqualTo(java.time.Duration.ofMinutes(30));
    }

    @Test void staleGraderCannotOverwriteNewLease() throws Exception {
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)listening.start(owner.getId(),unit,"ONE_PASS",false).get("sessionId");
        db.update("UPDATE listening_passes SET grading_status='PENDING',recall_text='fixture' WHERE session_id=?",id);
        when(ai.completeJson(anyString(),anyString())).thenAnswer(invocation->{
            db.update("UPDATE listening_passes SET grading_lease='new-owner',grading_attempts=2 WHERE session_id=?",id);
            var content=json.readTree(db.queryForObject("SELECT content_json FROM listening_units WHERE id=?",String.class,unit));
            var result=json.createObjectNode();var facts=result.putArray("facts");
            for(var fact:content.path("facts"))facts.addObject().put("factId",fact.path("id").asText()).put("status","CAPTURED");
            return result;
        });
        listening.gradePending();
        assertThat(db.queryForObject("SELECT grading_status FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT recall_score FROM listening_passes WHERE session_id=?",Double.class,id)).isNull();
        db.update("UPDATE listening_passes SET grading_status='FAILED',grading_lease=NULL WHERE session_id=?",id);
    }

    @Test void expiredGraderCannotCommitWithoutANewOwner(){
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)listening.start(owner.getId(),unit,"ONE_PASS",false).get("sessionId");
        db.update("UPDATE listening_passes SET grading_status='PENDING',recall_text='fixture' WHERE session_id=?",id);
        when(ai.completeJson(anyString(),anyString())).thenAnswer(invocation->{
            db.update("UPDATE listening_passes SET grading_started_at=? WHERE session_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(121)),id);
            var content=json.readTree(db.queryForObject("SELECT content_json FROM listening_units WHERE id=?",String.class,unit));
            var result=json.createObjectNode();var facts=result.putArray("facts");
            for(var fact:content.path("facts"))facts.addObject().put("factId",fact.path("id").asText()).put("status","CAPTURED");
            return result;
        });
        try{
            listening.gradePending();
            assertThat(db.queryForObject("SELECT grading_status FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("RUNNING");
            assertThat(db.queryForObject("SELECT recall_score FROM listening_passes WHERE session_id=?",Double.class,id)).isNull();
        }finally{db.update("UPDATE listening_passes SET grading_status='FAILED',grading_lease=NULL,grading_started_at=NULL WHERE session_id=?",id);}
    }

    @Test void failedListeningJobHasSanitizedObservationAndRetainsRecall(){
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)listening.start(owner.getId(),unit,"ONE_PASS",false).get("sessionId");
        db.update("UPDATE listening_passes SET grading_status='PENDING',recall_text='private-fixture' WHERE session_id=?",id);
        when(ai.completeJson(anyString(),anyString())).thenThrow(new IllegalStateException("private-provider-response"));
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var errors=new java.util.ArrayList<Throwable>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>(){
            public boolean supportsContext(io.micrometer.observation.Observation.Context context){return true;}
            public void onError(io.micrometer.observation.Observation.Context context){errors.add(context.getError());}
        });
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var isolated=new ListeningComprehensionService(db,json,ai,manager,1,metrics,registry);
        try{
            isolated.gradePending();
            assertThat(errors).anySatisfy(error->assertThat(error.getMessage()).isEqualTo("Listening grading failed"));
            assertThat(errors).allSatisfy(error->assertThat(error.getMessage()).doesNotContain("private-"));
            var pass=db.queryForMap("SELECT grading_status,recall_text,grading_last_error FROM listening_passes WHERE session_id=?",id);
            assertThat(pass.get("grading_status")).isEqualTo("FAILED");
            assertThat(pass.get("recall_text")).isEqualTo("private-fixture");
            assertThat(pass.get("grading_last_error").toString()).doesNotContain("private-");
        }finally{
            isolated.stopWorkers();metrics.close();
            db.update("UPDATE listening_passes SET grading_status='FAILED' WHERE session_id=?",id);
        }
    }

    @Test void listeningDoesNotAutomaticallyRetryAnUncertainProviderResponse(){
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)listening.start(owner.getId(),unit,"ONE_PASS",false).get("sessionId");
        db.update("UPDATE listening_passes SET grading_status='PENDING',recall_text='fixture' WHERE session_id=?",id);
        when(ai.completeJson(anyString(),anyString())).thenThrow(new ProviderGuard.Unavailable("rejected",120));
        Instant before=Instant.now();
        try{
            listening.gradePending();listening.gradePending();
            assertThat(db.queryForObject("SELECT grading_status FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("FAILED");
            assertThat(db.queryForObject("SELECT grading_attempts FROM listening_passes WHERE session_id=?",Integer.class,id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT next_grading_at FROM listening_passes WHERE session_id=?",java.sql.Timestamp.class,id)).isNull();
        }finally{db.update("UPDATE listening_passes SET grading_status='FAILED' WHERE session_id=?",id);}
    }

    @Test void crashedBillableGraderRequiresManualRetryInsteadOfAnotherAutomaticProviderCall(){
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)listening.start(owner.getId(),unit,"ONE_PASS",false).get("sessionId");
        db.update("UPDATE listening_passes SET grading_status='RUNNING',grading_started_at=?,grading_lease='dead-worker',grading_attempts=1,recall_text='saved recall' WHERE session_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(121)),id);
        listening.gradePending();
        assertThat(db.queryForObject("SELECT grading_status FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT grading_last_error FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(db.queryForObject("SELECT recall_text FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("saved recall");
        verify(ai,never()).completeJson(anyString(),anyString());
    }

    @Test void historyIsBoundedAndStable(){
        var owner=user();Long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        for(int i=0;i<13;i++)listening.start(owner.getId(),unit,"ONE_PASS",true);
        var first=listening.historyPage(owner.getId(),0,10);var second=listening.historyPage(owner.getId(),1,10);
        assertThat(first.total()).isEqualTo(13);assertThat(first.items()).hasSize(10);assertThat(second.items()).hasSize(3);
        assertThat(first.items().stream().map(item->item.get("id")).toList()).doesNotContainAnyElementsOf(second.items().stream().map(item->item.get("id")).toList());
        assertThatThrownBy(()->listening.historyPage(owner.getId(),0,101)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
