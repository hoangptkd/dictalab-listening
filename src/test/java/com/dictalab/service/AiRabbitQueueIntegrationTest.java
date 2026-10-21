package com.dictalab.service;

import com.dictalab.config.AiQueueProperties;
import com.dictalab.support.ReliabilityContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@EnabledIfEnvironmentVariable(named = "DICTALAB_MESSAGING_TEST", matches = "true")
@SpringBootTest(properties = {"dictalab.ai.queue.enabled=true", "dictalab.ai.queue.dispatch-delay-ms=600000",
        "dictalab.conversation.enabled=true", "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.simple.prefetch=1", "spring.rabbitmq.listener.simple.default-requeue-rejected=false",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"})
class AiRabbitQueueIntegrationTest {
    private static final String EXCHANGE = "dictalab.test.ai." + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.rabbitmq.host", () -> System.getProperty("spring.rabbitmq.host", "127.0.0.1"));
        properties.add("spring.rabbitmq.port", () -> System.getProperty("spring.rabbitmq.port", System.getenv("TEST_RABBITMQ_PORT")));
        properties.add("dictalab.ai.queue.exchange", () -> EXCHANGE);
        properties.add("dictalab.ai.queue.audio-queue", () -> EXCHANGE + ".audio");
        properties.add("dictalab.ai.queue.coach-queue", () -> EXCHANGE + ".coach");
    }
    @MockitoSpyBean AiBackgroundJobs jobs;
    @Autowired ConversationService conversation;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired AiQueueProperties config;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoBean CartesiaClient tts;
    @MockitoBean GeminiClient ai;
    private long owner;
    private String session;

    @BeforeEach void setup() {
        admin.initialize();
        String email = UUID.randomUUID() + "@rabbit-ai.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Rabbit test','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
        owner = db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        given(ai.configured()).willReturn(true); given(tts.enabled()).willReturn(true);
        given(tts.synthesize(anyString())).willReturn(new byte[]{1,2,3});
        session = conversation.create(owner, "cafe", "A1").path("id").asText();
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", json.readTree("""
                {"completed":false,"turns":[{"prompt":{"utteranceEn":"Private learner audio text."},
                "done":false,"attempts":[],"plays":0}]}
                """).toString(), session);
    }
    @AfterEach void cleanup() {
        listeners.stop();
        admin.initialize();
        for (String queue : new String[]{config.audioQueue(), config.coachQueue(), config.audioQueue()+".dlq", config.coachQueue()+".dlq"})
            admin.purgeQueue(queue);
        db.update("DELETE FROM app_users WHERE id=?", owner);
    }

    @Test void persistentIdOnlyMessageSurvivesDuplicateDeliveryWithOnePaidCall() throws Exception {
        conversation.play(owner, session, 1, "original", false);
        String id = job(); jobs.dispatch();
        var message = rabbit.receive(config.audioQueue(), 2000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(id);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        rabbit.convertAndSend(config.exchange(), config.audioQueue(), id);
        rabbit.convertAndSend(config.exchange(), config.audioQueue(), id);
        listeners.start();
        await(() -> "DONE".equals(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id)));
        await(() -> admin.getQueueInfo(config.audioQueue()).getMessageCount() == 0);
        then(tts).should(times(1)).synthesize("Private learner audio text.");
    }

    @Test void invalidMessagesGoToDeadLetterQueueWithoutProviderCall() throws Exception {
        rabbit.convertAndSend(config.exchange(), config.audioQueue(), "invalid-job");
        listeners.start();
        await(() -> admin.getQueueInfo(config.audioQueue()+".dlq").getMessageCount() == 1);
        then(tts).should(never()).synthesize(anyString());
    }

    @Test void unroutablePublicationRemainsPendingForRecovery() {
        conversation.play(owner, session, 1, "original", false);
        double before = metrics.counter("dictalab.ai.queue.publish_failures", "kind", "AUDIO").count();
        admin.deleteQueue(config.audioQueue());
        jobs.dispatch();
        assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, job())).isEqualTo("PENDING");
        assertThat(metrics.counter("dictalab.ai.queue.publish_failures", "kind", "AUDIO").count()).isGreaterThan(before);
        then(tts).should(never()).synthesize(anyString());
    }

    @Test @Timeout(90)
    @EnabledIfSystemProperty(named = "dictalab.testcontainers", matches = "true")
    void lateConfirmationAndRepublicationDoNotRepeatPaidWork() throws Exception {
        conversation.play(owner, session, 1, "original", false);
        String id = job();
        rabbit.execute(channel -> channel.queueDeclarePassive(config.audioQueue())); // Warm before stalling the broker.
        double failures = metrics.counter("dictalab.ai.queue.publish_failures", "kind", "AUDIO").count();
        double published = metrics.counter("dictalab.ai.queue.published", "kind", "AUDIO").count();
        var publisher = Executors.newSingleThreadExecutor();
        try {
            ReliabilityContainers.setRabbitPaused(true);
            try {
                long started = System.nanoTime();
                publisher.submit(jobs::dispatch).get(15, TimeUnit.SECONDS);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
                assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id)).isEqualTo("PENDING");
                assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?", Integer.class, id)).isZero();
                assertThat(metrics.counter("dictalab.ai.queue.publish_failures", "kind", "AUDIO").count()).isEqualTo(failures + 1);
                assertThat(metrics.counter("dictalab.ai.queue.published", "kind", "AUDIO").count()).isEqualTo(published);
                then(tts).should(never()).synthesize(anyString());
            } finally {
                ReliabilityContainers.setRabbitPaused(false);
            }
        } finally {
            publisher.shutdownNow();
            assertThat(publisher.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isEqualTo(1));
        // Advance only this fixture's publication deadline; do not retry the provider.
        db.update("UPDATE ai_background_jobs SET publish_after=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id=?", id);
        jobs.dispatch();
        assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isEqualTo(2);
        listeners.start();
        await(() -> "DONE".equals(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id)));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                then(jobs).should(times(2)).consumeAudio(id));
        listeners.stop();
        assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isZero();
        assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, session)).isEqualTo(1);
        then(tts).should(times(1)).synthesize("Private learner audio text.");
    }

    @Test @Timeout(120)
    @EnabledIfSystemProperty(named = "dictalab.testcontainers", matches = "true")
    void committedResultSurvivesBrokerCrashBeforeAckWithoutAnotherPaidCall() throws Exception {
        conversation.play(owner, session, 1, "original", false);
        String id = job();
        var committed = new CountDownLatch(1);
        var allowAck = new CountDownLatch(1);
        var redelivered = new CountDownLatch(1);
        var firstDelivery = new AtomicBoolean(true);
        willAnswer(call -> {
            call.callRealMethod();
            if (firstDelivery.getAndSet(false)) {
                committed.countDown();
                assertThat(allowAck.await(90, TimeUnit.SECONDS)).isTrue();
            } else redelivered.countDown();
            return null;
        }).given(jobs).consumeAudio(id);
        try {
            jobs.dispatch();
            listeners.start();
            assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id)).isEqualTo("DONE");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, session)).isEqualTo(1);
            then(tts).should(times(1)).synthesize(anyString());
            int port = ReliabilityContainers.crashAndRestartRabbit();
            var factory = (org.springframework.amqp.rabbit.connection.CachingConnectionFactory) rabbit.getConnectionFactory();
            factory.setAddresses(System.getProperty("spring.rabbitmq.host") + ":" + port);
            factory.resetConnection();
            allowAck.countDown();
            assertThat(redelivered.await(30, TimeUnit.SECONDS)).isTrue();
            listeners.stop();
            assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isZero();
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?", String.class, id)).isEqualTo("DONE");
            assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?", Integer.class, id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, session)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT wav FROM ai_conversation_audio WHERE session_id=?", byte[].class, session)).containsExactly(1, 2, 3);
            then(jobs).should(times(2)).consumeAudio(id);
            then(tts).should(times(1)).synthesize("Private learner audio text.");
        } finally {
            allowAck.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
    void confirmedPersistentJobSurvivesAbruptBrokerCrashAndReconnect() throws Exception {
        conversation.play(owner,session,1,"original",false); String id=job(); jobs.dispatch();
        assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isEqualTo(1);
        int port=com.dictalab.support.ReliabilityContainers.crashAndRestartRabbit();
        var factory=(org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory();
        factory.setAddresses(System.getProperty("spring.rabbitmq.host")+":"+port);
        factory.resetConnection(); // Docker may remap an automatically allocated host port.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->{
            assertThat(admin.getQueueInfo(config.audioQueue())).isNotNull();
            assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isEqualTo(1);
        });
        listeners.start();
        await(()->"DONE".equals(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,id)));
        listeners.stop(); jobs.consumeAudio(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,session)).isEqualTo(1);
        then(tts).should(times(1)).synthesize(anyString());
    }

    @Test void listenerShutdownWaitsForResultCommitAndAck() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        given(tts.synthesize(anyString())).willAnswer(call->{
            entered.countDown();assertThat(release.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return new byte[]{1,2,3};
        });
        conversation.play(owner,session,1,"original",false);String id=job();jobs.dispatch();listeners.start();
        var stopper=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var stopped=stopper.submit(()->{listeners.stop();});
            // Cross the old 5s shutdown deadline while the provider is still active.
            org.awaitility.Awaitility.await().during(java.time.Duration.ofSeconds(6)).atMost(java.time.Duration.ofSeconds(8))
                    .until(()->!stopped.isDone());
            release.countDown();stopped.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,id)).isEqualTo("DONE");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,session)).isEqualTo(1);
            jobs.consumeAudio(id);then(tts).should(times(1)).synthesize(anyString());
        } finally {release.countDown();stopper.shutdownNow();}
    }

    @Test @Timeout(180)
    @EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
    void killedWorkerLeavesUnknownOutcomeUntilExplicitRetry() throws Exception {
        ReliabilityContainers.disposablePostgres(); // Never start this fixture against an arbitrary messaging environment.
        conversation.play(owner,session,1,"original",false);
        String previous=job();jobs.dispatch();
        var folder=Path.of(System.getProperty("dictalab.build.directory","target"),"ai-worker-recovery",previous);
        Files.createDirectories(folder);var log=folder.resolve("killed-worker.log");
        Process child=startCrashWorker(log);
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(100)).until(()->
                    !child.isAlive() || (Files.exists(log) && Files.readString(log).contains("SIMULATED_TTS_ACCEPTED")));
            assertThat(child.isAlive()).as("Child startup failed; see %s",log).isTrue();
            assertThat(Files.readString(log)).contains("SIMULATED_TTS_ACCEPTED");
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,previous)).isEqualTo("RUNNING");
            assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?",Integer.class,previous)).isEqualTo(1);
            child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(()->
                    assertThat(admin.getQueueInfo(config.audioQueue()).getMessageCount()).isEqualTo(1));
            listeners.start();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->
                    then(jobs).should(times(1)).consumeAudio(previous));
            listeners.stop();
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,previous)).isEqualTo("RUNNING");
            then(tts).should(never()).synthesize(anyString());
            db.update("UPDATE ai_background_jobs SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id=?",previous);
            jobs.dispatch();jobs.consumeAudio(previous);
            assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,previous)).isEqualTo("UNKNOWN");
            assertThat(db.queryForObject("SELECT last_error FROM ai_background_jobs WHERE id=?",String.class,previous)).isEqualTo("OUTCOME_UNKNOWN");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,session)).isZero();
            then(tts).should(never()).synthesize(anyString());
            // Only a new explicit playback request may authorize another possibly billable attempt.
            conversation.play(owner,session,1,"original",false);
            String replacement=job();assertThat(replacement).isNotEqualTo(previous);
            jobs.consumeAudio(previous);jobs.dispatch();listeners.start();
            await(()->"DONE".equals(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,replacement)));
            listeners.stop();
            assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?",Integer.class,replacement)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,session)).isEqualTo(1);
            assertThat(Files.readString(log).split("SIMULATED_TTS_ACCEPTED",-1)).hasSize(2);
            then(tts).should(times(1)).synthesize("Private learner audio text.");
        } finally {
            if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();}
        }
    }

    private Process startCrashWorker(Path log) throws Exception {
        var settings=new HashMap<String,Object>();
        for(String property:new String[]{"spring.datasource.url","spring.datasource.username","spring.datasource.password",
                "spring.rabbitmq.host","spring.rabbitmq.port","spring.rabbitmq.username","spring.rabbitmq.password"})
            settings.put(property,System.getProperty(property));
        settings.put("spring.datasource.hikari.jdbc-url",System.getProperty("spring.datasource.url"));
        settings.put("spring.datasource.driver-class-name","org.postgresql.Driver");
        settings.put("spring.datasource.hikari.maximum-pool-size",3);settings.put("spring.datasource.hikari.minimum-idle",0);
        settings.put("spring.profiles.active","restore-smoke,ai-worker-crash-fixture");
        settings.put("spring.config.location","classpath:/application.yml");settings.put("spring.config.additional-location","");settings.put("spring.config.import","");
        settings.put("server.address","127.0.0.1");settings.put("server.port",0);settings.put("management.server.port",0);
        settings.put("spring.data.redis.url",System.getProperty("spring.data.redis.url"));
        settings.put("dictalab.auth.signing-key","dGVzdC1vbmx5LWRpY3RhbGFiLXNpZ25pbmcta2V5LTMyaGFyYWN0ZXJz");
        settings.put("dictalab.auth.previous-signing-key","");settings.put("dictalab.mail-from","");settings.put("spring.mail.host","");
        settings.put("dictalab.media.root",log.getParent().resolve("media").toAbsolutePath().toString());
        for(String property:new String[]{"dictalab.media.s3.enabled","dictalab.media.s3.cleanup-enabled","dictalab.listening.queue.enabled",
                "dictalab.coach.enabled","dictalab.personal-content.enabled","management.health.rabbit.enabled","management.health.mail.enabled",
                "management.health.redis.enabled","management.tracing.export.otlp.enabled","management.otlp.metrics.export.enabled","management.logging.export.otlp.enabled"})settings.put(property,false);
        settings.put("dictalab.conversation.cartesia-api-key","");settings.put("dictalab.gemini.api-key","");
        settings.put("dictalab.ai.queue.enabled",true);settings.put("dictalab.ai.queue.exchange",config.exchange());
        settings.put("dictalab.ai.queue.audio-queue",config.audioQueue());settings.put("dictalab.ai.queue.coach-queue",config.coachQueue());
        settings.put("dictalab.ai.queue.audio-workers",config.audioWorkers());settings.put("dictalab.ai.queue.coach-workers",config.coachWorkers());
        settings.put("spring.rabbitmq.listener.simple.auto-startup",true);settings.put("spring.rabbitmq.listener.simple.prefetch",1);
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-Xmx256m","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),CrashWorker.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        for(String key:new String[]{"JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"})builder.environment().remove(key);
        builder.environment().put("SPRING_APPLICATION_JSON",json.writeValueAsString(settings));
        return builder.start();
    }

    public static class CrashWorker {
        public static void main(String[] args) {
            var settings=new ObjectMapper().readTree(System.getenv("SPRING_APPLICATION_JSON"));
            var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(settings.path("spring.datasource.url").asText(),
                    settings.path("spring.datasource.username").asText(),settings.path("spring.datasource.password").asText());
            assertThat(new JdbcTemplate(source).queryForObject("SELECT current_database()",String.class)).isEqualTo("dictalab_test");
            var context=org.springframework.boot.SpringApplication.run(new Class<?>[]{com.dictalab.DictaLabApplication.class,CrashProvider.class},args);
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT current_database()",String.class)).isEqualTo("dictalab_test");
            assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        }
    }

    @Test @Timeout(90)
    @EnabledIfSystemProperty(named="dictalab.testcontainers",matches="true")
    void liveBrokerNetworkIsolationLeavesPendingWorkRecoverable() throws Exception {
        ReliabilityContainers.disposablePostgres();
        var factory=(org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory();
        String originalAddress=System.getProperty("spring.rabbitmq.host")+":"+System.getProperty("spring.rabbitmq.port");
        // Keep the fault target's IP stable; reconnecting a shared bridge can invalidate Docker's host forwarding.
        // Docker requires an explicitly configured subnet to restore an endpoint's fixed IP.
        try(var isolatedNetwork=org.testcontainers.containers.Network.builder().createNetworkCmdModifier(command ->
                command.withIpam(new com.github.dockerjava.api.model.Network.Ipam().withConfig(
                        new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet("10.253.241.0/24")))).build();
                var broker=new org.testcontainers.rabbitmq.RabbitMQContainer("rabbitmq:4.3.6-management-alpine")
                .withNetwork(isolatedNetwork).withNetworkAliases("rabbit-network-fixture")
                .withAdminUser("dictalab_test").withAdminPassword("test-only-password")) {
            broker.start();factory.setAddresses(broker.getHost()+":"+broker.getAmqpPort());factory.resetConnection();admin.initialize();
            var docker=org.testcontainers.DockerClientFactory.instance().client();
            var before=docker.inspectContainerCmd(broker.getContainerId()).exec();
            var networks=before.getNetworkSettings().getNetworks();assertThat(networks).hasSize(1);
            var endpoint=networks.values().iterator().next();
            String network=endpoint.getNetworkID();String originalIp=endpoint.getIpAddress();
            assertThat(originalIp).isNotBlank();
            conversation.play(owner,session,1,"original",false);String id=job();
            rabbit.execute(channel->channel.queueDeclarePassive(config.audioQueue()));
            double failures=metrics.counter("dictalab.ai.queue.publish_failures","kind","AUDIO").count();
            var publisher=Executors.newSingleThreadExecutor();
            // Close live channels before isolation; a graceful channel.close needs the broker's reply.
            factory.resetConnection();
            try {
                docker.disconnectFromNetworkCmd().withNetworkId(network).withContainerId(broker.getContainerId()).exec();
                try {
                    // Established Docker forwarding can survive a network detach; verify a fresh connection is blocked.
                    assertThat(broker.execInContainer("rabbitmq-diagnostics","-q","ping").getExitCode()).isZero();
                    assertThat(docker.inspectContainerCmd(broker.getContainerId()).exec().getNetworkSettings().getNetworks()).isEmpty();
                    assertThatThrownBy(()->rabbit.execute(channel->channel.queueDeclarePassive(config.audioQueue())))
                            .isInstanceOf(org.springframework.amqp.AmqpException.class);
                    publisher.submit(jobs::dispatch).get(15,TimeUnit.SECONDS);
                    assertThat(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,id)).isEqualTo("PENDING");
                    assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?",Integer.class,id)).isZero();
                    assertThat(metrics.counter("dictalab.ai.queue.publish_failures","kind","AUDIO").count()).isEqualTo(failures+1);
                    then(tts).should(never()).synthesize(anyString());
                } finally {
                    docker.connectToNetworkCmd().withNetworkId(network).withContainerId(broker.getContainerId())
                            .withContainerNetwork(new com.github.dockerjava.api.model.ContainerNetwork()
                                    .withAliases(endpoint.getAliases()).withIpamConfig(new com.github.dockerjava.api.model.ContainerNetwork.Ipam()
                                            .withIpv4Address(originalIp))).exec();
                    var restored=docker.inspectContainerCmd(broker.getContainerId()).exec();
                    assertThat(restored.getNetworkSettings().getNetworks()).hasSize(1);
                    String restoredIp=restored.getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
                    assertThat(restoredIp).isEqualTo(originalIp);
                    int port=Integer.parseInt(restored.getNetworkSettings().getPorts().getBindings()
                            .get(com.github.dockerjava.api.model.ExposedPort.tcp(5672))[0].getHostPortSpec());
                    System.out.println("RABBIT_NETWORK_RECOVERY originalIp="+originalIp+" restoredIp="+restoredIp+" mappedPort="+port);
                    factory.setAddresses(broker.getHost()+":"+port);factory.resetConnection();
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(()->
                            assertThat(admin.getQueueInfo(config.audioQueue())).isNotNull());
                }
            } finally {publisher.shutdownNow();assertThat(publisher.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
            var restored=docker.inspectContainerCmd(broker.getContainerId()).exec();
            assertThat(restored.getState().getStartedAt()).isEqualTo(before.getState().getStartedAt());
            db.update("UPDATE ai_background_jobs SET publish_after=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id=?",id);
            jobs.dispatch();listeners.start();
            await(()->"DONE".equals(db.queryForObject("SELECT status FROM ai_background_jobs WHERE id=?",String.class,id)));
            listeners.stop();
            assertThat(db.queryForObject("SELECT attempts FROM ai_background_jobs WHERE id=?",Integer.class,id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?",Integer.class,session)).isEqualTo(1);
            then(tts).should(times(1)).synthesize("Private learner audio text.");
        } finally {
            listeners.stop();factory.setAddresses(originalAddress);factory.resetConnection();admin.initialize();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("ai-worker-crash-fixture")
    public static class CrashProvider {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        CartesiaClient crashTts() {
            var provider=mock(CartesiaClient.class);given(provider.enabled()).willReturn(true);
            given(provider.synthesize(anyString())).willAnswer(call->{
                System.out.println("SIMULATED_TTS_ACCEPTED");System.out.flush();
                new CountDownLatch(1).await(120,TimeUnit.SECONDS);
                throw new IllegalStateException("Fixture provider response never arrived");
            });
            return provider;
        }
    }

    private String job() { return db.queryForObject("SELECT id FROM ai_background_jobs WHERE conversation_id=?", String.class, session); }
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
