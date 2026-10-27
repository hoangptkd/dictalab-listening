package com.dictalab.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.dictalab.support.ReliabilityContainers;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Runs the packaged Progress process against a database physically separate from Core. */
@EnabledIfSystemProperty(named="dictalab.testcontainers", matches="true")
@SpringBootTest(properties={"dictalab.progress.enabled=true", "dictalab.progress.dispatch-delay-ms=600000",
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true", "spring.rabbitmq.listener.simple.auto-startup=false"})
class ProgressDeliveryIntegrationTest {
    static final PostgreSQLContainer progressDb = new PostgreSQLContainer("postgres:18-alpine");
    static final String token = UUID.randomUUID().toString()+UUID.randomUUID();
    static final Path evidence = Path.of("target", "readiness", "progress-delivery");
    static Process process;
    static int port;
    static String base;
    @Autowired JdbcTemplate db;
    @Autowired ProgressOutbox outbox;
    @Autowired ProgressPublisher publisher;
    @Autowired DashboardService dashboard;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired PlatformTransactionManager manager;

    @BeforeAll static void startService() throws Exception {
        Files.createDirectories(evidence);
        assertThat(Path.of("services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar"))
                .as("Build and verify the Progress JAR before this cross-process test").exists();
        progressDb.start();
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:"+port;
        launch();
    }

    static void launch() throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(java, "-Xmx192m", "-Dlogging.level.root=WARN", "-Dspring.main.banner-mode=off",
                "-jar", Path.of("services/progress/target/dictalab-progress-0.1.0-SNAPSHOT.jar").toAbsolutePath().toString());
        var inherited = new HashMap<>(builder.environment());
        var env = builder.environment();
        env.clear();
        for (var item : inherited.entrySet()) {
            if (Set.of("PATH","SYSTEMROOT","WINDIR","TEMP","TMP","LANG","LC_ALL","TZ").contains(item.getKey().toUpperCase(Locale.ROOT)))
                env.put(item.getKey(), item.getValue());
        }
        env.put("PROGRESS_PORT", Integer.toString(port));
        env.put("PROGRESS_DB_URL", progressDb.getJdbcUrl());
        env.put("PROGRESS_DB_USERNAME", progressDb.getUsername());
        env.put("PROGRESS_DB_PASSWORD", progressDb.getPassword());
        env.put("PROGRESS_SERVICE_TOKEN", token);
        env.put("RABBITMQ_HOST", System.getProperty("spring.rabbitmq.host"));
        env.put("RABBITMQ_PORT", System.getProperty("spring.rabbitmq.port"));
        env.put("RABBITMQ_USER", System.getProperty("spring.rabbitmq.username"));
        env.put("RABBITMQ_PASSWORD", System.getProperty("spring.rabbitmq.password"));
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(evidence.resolve("progress.log").toFile()));
        process = builder.start();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(() -> {
            if (!process.isAlive()) throw new AssertionError("Progress process exited; inspect target/readiness/progress-delivery/progress.log");
            return http.send(HttpRequest.newBuilder(URI.create(base+"/actuator/health/readiness"))
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()==200;
        });
    }
    static void stopProcess() throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    @AfterAll static void cleanup() throws Exception { try { stopProcess(); } finally { progressDb.stop(); } }

    String complete(long user, double accuracy) {
        String id = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,
                    total_practice_seconds,first_attempt_accuracy,final_accuracy,overall_score,known_missed_count,unknown_count)
                VALUES (?,'progress-fixture',?,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,90,?,98,82.5,2,1)
                """, id, user, accuracy);
        String cursor = "";
        for (int batch=0; batch<100; batch++) {
            var result = outbox.backfill(cursor, 100);
            if (result.done()) return id;
            cursor = result.nextCursor();
        }
        throw new AssertionError("Backfill did not terminate");
    }
    void drain(String session) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            publisher.dispatch();
            return db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE payload LIKE ? AND status='SENT'",
                    Integer.class, "%"+session+"%")>0;
        });
    }

    @Test void packagedServicePreservesParityThroughDuplicatesOutageRestartAndDeletion() throws Exception {
        String email=UUID.randomUUID()+"@progress-delivery.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Learner','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", email);
        long user = db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        var remote = new ProgressClient(json, new SimpleMeterRegistry(), "remote", base, token, 2000, true);
        String first = complete(user, 72.3);
        String latePayload = db.queryForObject("SELECT payload FROM progress_outbox WHERE user_id=?", String.class, user);
        drain(first);
        await().atMost(Duration.ofSeconds(15)).until(() -> remote.fetch(user).summary().completedLessons()==1);
        var local = dashboard.dashboard(user);
        var projection = remote.fetch(user);
        assertThat(projection.summary()).isEqualTo(local.summary());
        assertThat(projection.trend()).isEqualTo(local.trend());
        assertThat(projection.byLevel()).isEqualTo(local.byLevel());
        assertThat(projection.errors()).isEqualTo(local.errors());

        // A publisher crash after ACK but before recording SENT leads to a safe duplicate.
        db.update("UPDATE progress_outbox SET status='PENDING',available_at=CURRENT_TIMESTAMP WHERE user_id=?", user);
        drain(first);
        assertThat(remote.fetch(user).summary().completedLessons()).isEqualTo(1);

        ReliabilityContainers.setRabbitPaused(true);
        String second;
        try {
            second = complete(user, 91.7); // Domain result and outbox persist without the broker.
            publisher.dispatch();
            assertThat(db.queryForObject("SELECT status FROM practice_sessions WHERE id=?", String.class, second)).isEqualTo("COMPLETED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE user_id=? AND status='PENDING'", Integer.class, user)).isEqualTo(1);
        } finally { ReliabilityContainers.setRabbitPaused(false); }
        db.update("UPDATE progress_outbox SET available_at=CURRENT_TIMESTAMP WHERE user_id=?", user);
        drain(second);
        await().atMost(Duration.ofSeconds(20)).until(() -> remote.fetch(user).summary().completedLessons()==2);
        stopProcess(); launch();
        assertThat(remote.fetch(user).summary()).isEqualTo(dashboard.dashboard(user).summary());

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, user);
            outbox.userDeleted(user);
            db.update("DELETE FROM practice_sessions WHERE user_id=?", user);
            db.update("DELETE FROM app_users WHERE id=?", user);
        });
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            publisher.dispatch();
            return remote.fetch(user).summary().completedLessons()==0;
        });
        rabbit.send("", ProgressOutbox.QUEUE, new Message(latePayload.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
        long marker = user + 1_000_000;
        var tombstone = new ProgressOutbox.Event(1, UUID.randomUUID(), "UserDeleted.v1", marker, Instant.now(), null);
        rabbit.send("", ProgressOutbox.QUEUE, new Message(json.writeValueAsBytes(tombstone), new MessageProperties()));
        await().atMost(Duration.ofSeconds(15)).until(() -> remote.fetch(marker).updatedAt()!=null);
        assertThat(remote.fetch(user).summary().completedLessons()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM progress_outbox WHERE user_id=? AND event_type='PracticeCompleted.v1'", Integer.class, user)).isZero();
        Files.writeString(evidence.resolve("result.json"), json.writeValueAsString(Map.of(
                "separateDatabases", true, "dashboardParity", true, "lostAckDuplicate", true,
                "brokerOutageRecovery", true, "processRestart", true, "deletionPreventsResurrection", true)), StandardCharsets.UTF_8);
    }
}
