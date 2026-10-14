package com.dictalab.service;

import com.dictalab.config.LearningRabbitConfig;
import com.dictalab.support.ReliabilityContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
@SpringBootTest(properties={"dictalab.learning.enabled=true", "dictalab.learning.queue-enabled=true",
        "dictalab.learning.dispatch-delay-ms=600000", "dictalab.learning.index-delay-ms=600000",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
class LearningJobsIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired LearningJobs jobs;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired org.springframework.amqp.rabbit.connection.CachingConnectionFactory connection;
    @MockitoSpyBean ListeningLearning learning;
    @AfterEach void stop() {listeners.stop();reset(learning);}
    String fixture() {
        String email=UUID.randomUUID()+"@learning-rabbit.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Learner','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        long user=db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
        String session=UUID.randomUUID().toString();
        db.update("INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,first_exposure) VALUES (?,'test',?,1,'IN_PROGRESS',CURRENT_TIMESTAMP,TRUE)",session,user);
        long sentence=db.queryForObject("SELECT id FROM lesson_sentences WHERE lesson_id=1 ORDER BY position_index LIMIT 1",Long.class);
        db.update("INSERT INTO sentence_attempts(practice_session_id,sentence_id,started_at,completed) VALUES (?,?,CURRENT_TIMESTAMP,TRUE)",session,sentence);
        long attempt=db.queryForObject("SELECT id FROM sentence_attempts WHERE practice_session_id=?",Long.class,session);
        db.update("INSERT INTO word_attempts(sentence_attempt_id,word_index,attempt_count,first_attempt_correct,final_correct,first_answer) VALUES (?,0,1,FALSE,FALSE,'wrong')",attempt);
        String id=UUID.randomUUID().toString();db.update("INSERT INTO learning_jobs(id,attempt_id) VALUES (?,?)",id,attempt);return id;
    }
    int evidence(String id) {return db.queryForObject("SELECT COUNT(*) FROM listening_error_evidence e JOIN word_attempts w ON w.id=e.word_attempt_id JOIN learning_jobs j ON j.attempt_id=w.sentence_attempt_id WHERE j.id=?",Integer.class,id);}
    String state(String id) {return db.queryForObject("SELECT status FROM learning_jobs WHERE id=?",String.class,id);}
    void ready(String id) {db.update("UPDATE learning_jobs SET publish_after=CURRENT_TIMESTAMP-INTERVAL '1' SECOND,available_at=CURRENT_TIMESTAMP-INTERVAL '1' SECOND WHERE id=?",id);}
    @Test void duplicateDeliveryAndLostAckHaveOneProjection() {
        String id=fixture();jobs.consume(id);jobs.consume(id);
        assertThat(state(id)).isEqualTo("DONE");assertThat(evidence(id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT attempts FROM learning_jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void exhaustedDeterministicWorkCanBeExplicitlyRecoveredOnce() {
        String id=fixture();
        doThrow(new IllegalStateException("injected")).when(learning).collect(anyLong());
        for(int i=0;i<4;i++) {ready(id);jobs.consume(id);}
        ready(id);
        assertThatThrownBy(()->jobs.consume(id)).isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertThat(state(id)).isEqualTo("FAILED");assertThat(evidence(id)).isZero();
        reset(learning);jobs.retryFailed(id);jobs.consume(id);
        assertThat(state(id)).isEqualTo("DONE");assertThat(evidence(id)).isEqualTo(1);
        assertThatThrownBy(()->jobs.retryFailed(id)).isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
    }
    @Test void projectionFailureRollsBackAndRetryRecoversWithoutTouchingCompletion() {
        String id=fixture();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after projection");}).doCallRealMethod().when(learning).collect(anyLong());
        jobs.consume(id);assertThat(state(id)).isEqualTo("PENDING");assertThat(evidence(id)).isZero();
        jobs.consume(id);assertThat(state(id)).isEqualTo("PENDING");assertThat(evidence(id)).isZero();
        ready(id);jobs.consume(id);assertThat(state(id)).isEqualTo("DONE");assertThat(evidence(id)).isEqualTo(1);
    }
    @Test void expiredLeaseCannotCommitAndCrashCanBeRecovered() {
        String id=fixture();
        doAnswer(call->{call.callRealMethod();db.update("UPDATE learning_jobs SET locked_until=CURRENT_TIMESTAMP-INTERVAL '1' SECOND WHERE id=?",id);return null;}).when(learning).collect(anyLong());
        jobs.consume(id);assertThat(evidence(id)).isZero();assertThat(state(id)).isEqualTo("RUNNING");
        reset(learning);db.update("UPDATE learning_jobs SET locked_until=CURRENT_TIMESTAMP-INTERVAL '1' SECOND WHERE id=?",id);
        jobs.dispatch();jobs.consume(id);assertThat(state(id)).isEqualTo("DONE");assertThat(evidence(id)).isEqualTo(1);
    }
    @Test void brokerOutageKeepsDurableOutboxAndPoisonDeliveryGoesToDlq() {
        admin.initialize();String id=fixture();ready(id);
        ReliabilityContainers.setRabbitPaused(true);
        try {jobs.dispatch();assertThat(state(id)).isEqualTo("PENDING");assertThat(evidence(id)).isZero();}
        finally {ReliabilityContainers.setRabbitPaused(false);}
        ready(id);jobs.dispatch();listeners.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(()->state(id).equals("DONE"));
        assertThat(evidence(id)).isEqualTo(1);
        rabbit.convertAndSend("",LearningRabbitConfig.QUEUE,"not-a-uuid");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(()->
                assertThat(admin.getQueueInfo(LearningRabbitConfig.QUEUE+".dlq").getMessageCount()).isGreaterThan(0));
    }
    @Test void confirmedPersistentMessageSurvivesBrokerSigkillAndRestart() {
        // Isolate persistence from cached Spring contexts and pending jobs in other tests.
        String queue=LearningRabbitConfig.QUEUE+".restart-"+UUID.randomUUID();
        admin.declareQueue(org.springframework.amqp.core.QueueBuilder.durable(queue).build());
        String id=fixture();
        var confirm=new org.springframework.amqp.rabbit.connection.CorrelationData(UUID.randomUUID().toString());
        rabbit.convertAndSend("",queue,id,message->{
            message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            return message;
        },confirm);
        try {
            assertThat(confirm.getFuture().get(10,java.util.concurrent.TimeUnit.SECONDS).ack()).isTrue();
            assertThat(confirm.getReturned()).isNull();
        } catch(Exception failure) {throw new AssertionError("Persistent publish must be confirmed before crash",failure);}
        int port=ReliabilityContainers.crashAndRestartRabbit();
        connection.setAddresses(System.getProperty("spring.rabbitmq.host")+":"+port);connection.resetConnection();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->
                assertThat(admin.getQueueInfo(queue).getMessageCount()).isEqualTo(1));
        assertThat(rabbit.receiveAndConvert(queue)).isEqualTo(id);
        admin.deleteQueue(queue);
        jobs.consume(id);
        assertThat(state(id)).isEqualTo("DONE");
        assertThat(evidence(id)).isEqualTo(1);
    }
}
