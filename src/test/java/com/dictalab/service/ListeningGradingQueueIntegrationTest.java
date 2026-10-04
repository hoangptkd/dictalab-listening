package com.dictalab.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@EnabledIfEnvironmentVariable(named="DICTALAB_MESSAGING_TEST",matches="true")
@SpringBootTest(properties={"dictalab.listening.queue.enabled=true","dictalab.listening.dispatch-delay-ms=600000",
        "spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.listener.simple.prefetch=1",
        "spring.rabbitmq.listener.simple.default-requeue-rejected=false","spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true","spring.rabbitmq.template.mandatory=true"})
class ListeningGradingQueueIntegrationTest {
    private static final String EXCHANGE="dictalab.test."+UUID.randomUUID();
    private static final String QUEUE=EXCHANGE+".grade",DLQ=QUEUE+".dlq";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host",()->"127.0.0.1");
        properties.add("spring.rabbitmq.port",()->System.getenv("TEST_RABBITMQ_PORT"));
        properties.add("dictalab.listening.queue.exchange",()->EXCHANGE);
        properties.add("dictalab.listening.queue.name",()->QUEUE);
        properties.add("dictalab.listening.queue.dead-letter-queue",()->DLQ);
    }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ListeningComprehensionService service;
    @Autowired ListeningGradingQueue queue;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager manager;
    @Autowired MeterRegistry metrics;
    @MockitoBean ShopAiKeyClient ai;
    private final List<String> sessions=new ArrayList<>();
    private final List<Long> users=new ArrayList<>();

    @BeforeEach void declare() {
        assertThat(db.queryForObject("SELECT current_database()",String.class)).isEqualTo("dictalab_messaging_test");
        listeners.stop();
        // Declares durable queues even while the consumers are stopped for deterministic assertions.
        admin.initialize();
        admin.purgeQueue(QUEUE);admin.purgeQueue(DLQ);
    }
    @AfterEach void cleanup() {
        listeners.stop();
        sessions.forEach(id->db.update("DELETE FROM listening_sessions WHERE id=?",id));
        users.forEach(id->db.update("DELETE FROM app_users WHERE id=?",id));
    }
    private String prepare(String recall) {
        String email="queue-"+UUID.randomUUID()+"@test.local";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES(?,'Queue test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        long user=db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);users.add(user);
        long unit=db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1",Long.class);
        String id=(String)service.start(user,unit,"ONE_PASS",false).get("sessionId");sessions.add(id);
        db.update("UPDATE listening_sessions SET stage='QUESTIONS' WHERE id=?",id);
        db.update("UPDATE listening_passes SET recall_text=?,recall_submitted_at=CURRENT_TIMESTAMP WHERE session_id=?",recall,id);
        return id;
    }
    private void submit(String id) {
        var questions=(List<Map<String,Object>>)service.state(users.get(users.size()-1),id).get("questions");
        var answers=new HashMap<String,Integer>();questions.forEach(q->answers.put((String)q.get("id"),0));
        service.submitAnswers(users.get(users.size()-1),id,answers);
    }
    private String status(String id) {return db.queryForObject("SELECT grading_status FROM listening_passes WHERE session_id=? AND pass_number=1",String.class,id);}
    private void awaitStatus(String id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!expected.equals(status(id)) && System.nanoTime()<deadline)Thread.sleep(25);
        assertThat(status(id)).isEqualTo(expected);
    }
    @Test void rollbackPublishesNothingAndCommittedJobSurvivesDuplicateDelivery() throws Exception {
        String id=prepare("");
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(s->{submit(id);throw new IllegalStateException("rollback");}))
                .hasMessage("rollback");
        assertThat(status(id)).isEqualTo("NOT_REQUESTED");
        assertThat(rabbit.receive(QUEUE)).isNull();
        submit(id);service.dispatchGrades();service.dispatchGrades();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT grading_publish_after IS NOT NULL FROM listening_passes WHERE session_id=?",Boolean.class,id)).isTrue();
        listeners.start();awaitStatus(id,"COMPLETED");
        queue.publish(id,1,0,0);queue.publish(id,1,0,0);
        queue.consume(id+"/1/0/0");
        assertThat(db.queryForObject("SELECT grading_attempts FROM listening_passes WHERE session_id=?",Integer.class,id)).isEqualTo(1);
        then(ai).should(never()).completeJson(anyString(),anyString());
    }
    @Test void concurrentDeliveriesCallTheProviderOnlyOnce() throws Exception {
        given(ai.configured()).willReturn(true);given(ai.model()).willReturn("test-model");
        String id=prepare("saved recall");submit(id);
        var result=json.createObjectNode();var labels=result.putArray("facts");
        var content=json.readTree(db.queryForObject("""
                SELECT u.content_json FROM listening_sessions s JOIN listening_units u ON u.id=s.unit_id WHERE s.id=?
                """,String.class,id));
        content.path("facts").forEach(f->labels.addObject().put("factId",f.path("id").asText())
                .put("status","CAPTURED").put("explanationVi","fixture"));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        given(ai.completeJson(anyString(),anyString())).willAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return result;
        });
        double before=metrics.counter("dictalab.listening.queue.consumed").count();
        queue.publish(id,1,0,0);queue.publish(id,1,0,0);listeners.start();
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(metrics.counter("dictalab.listening.queue.consumed").count()==before && System.nanoTime()<deadline)Thread.sleep(25);
            assertThat(metrics.counter("dictalab.listening.queue.consumed").count()).isGreaterThan(before);
        } finally {release.countDown();}
        awaitStatus(id,"COMPLETED");
        then(ai).should(times(1)).completeJson(anyString(),anyString());
        assertThat(db.queryForObject("SELECT grading_attempts FROM listening_passes WHERE session_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void unroutablePublishKeepsPendingWorkAndRecoveryRepublishes() throws Exception {
        String id=prepare("");submit(id);
        var unroutable=new ListeningGradingQueue(rabbit,service,EXCHANGE,QUEUE+".missing",metrics);
        assertThatThrownBy(()->unroutable.publish(id,1,0,0)).isInstanceOf(IllegalStateException.class);
        assertThat(status(id)).isEqualTo("PENDING");
        // Simulate restart after a publication claim, with no completed send.
        db.update("UPDATE listening_passes SET grading_publish_after=CURRENT_TIMESTAMP WHERE session_id=?",id);
        service.dispatchGrades();listeners.start();awaitStatus(id,"COMPLETED");
    }
    @Test void oldRevisionAndAttemptCannotClaimANewRetry() {
        given(ai.configured()).willReturn(true);
        String id=prepare("saved recall");submit(id);
        db.update("UPDATE listening_passes SET grading_status='FAILED',grading_attempts=1 WHERE session_id=?",id);
        service.retryGrading(users.get(0),id,1);
        queue.consume(id+"/1/0/0");
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT grading_attempts FROM listening_passes WHERE session_id=?",Integer.class,id)).isZero();
        then(ai).should(never()).completeJson(anyString(),anyString());
        db.update("UPDATE listening_passes SET grading_attempts=1 WHERE session_id=?",id);
        queue.consume(id+"/1/1/0");
        assertThat(status(id)).isEqualTo("PENDING");
        then(ai).should(never()).completeJson(anyString(),anyString());
    }
    @Test void lostBillableWorkerBecomesOutcomeUnknownWithoutResubmission() {
        given(ai.configured()).willReturn(true);
        String id=prepare("saved recall");submit(id);
        db.update("UPDATE listening_passes SET grading_status='RUNNING',grading_attempts=1,grading_started_at=?,grading_lease=? WHERE session_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(130)),UUID.randomUUID().toString(),id);
        queue.consume(id+"/1/0/0");service.dispatchGrades();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT grading_last_error FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(db.queryForObject("SELECT recall_text FROM listening_passes WHERE session_id=?",String.class,id)).isEqualTo("saved recall");
        then(ai).should(never()).completeJson(anyString(),anyString());
    }
    @Test void malformedMessagesGoToDeadLetterQueueAndDeletedSessionsAreIgnored() throws Exception {
        rabbit.convertAndSend(EXCHANGE,QUEUE,"malformed");listeners.start();
        var dead=rabbit.receive(DLQ,10000);assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("malformed");
        assertThatNoException().isThrownBy(()->queue.consume(UUID.randomUUID()+"/1/0/0"));
    }
}
