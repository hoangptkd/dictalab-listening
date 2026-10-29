package com.dictalab.progress;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties="dictalab.progress.service-token=progress-test-token-at-least-32-chars")
@AutoConfigureMockMvc
class ProgressIntegrationTest {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(System.getProperty("dictalab.testcontainers.postgres-image", "postgres:18-alpine"));
    static final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:4.3.6-management-alpine");
    static {
        Startables.deepStart(Stream.of(postgres, broker)).join();
    }
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.rabbitmq.host", broker::getHost);
        r.add("spring.rabbitmq.port", broker::getAmqpPort);
        r.add("spring.rabbitmq.username", broker::getAdminUsername);
        r.add("spring.rabbitmq.password", broker::getAdminPassword);
    }
    @Autowired ProgressStore store;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.core.AmqpAdmin admin;
    @Autowired ObjectMapper json;
    final UUID sessionId = UUID.randomUUID();

    @Test void auroraRuntimeValidatesWithoutDdlAndRejectsPendingMigrations(@org.junit.jupiter.api.io.TempDir java.nio.file.Path migrations) throws Exception {
        db.execute("CREATE ROLE progress_schema_runtime LOGIN PASSWORD 'synthetic-schema-check'");
        db.execute("GRANT USAGE ON SCHEMA public TO progress_schema_runtime");
        db.execute("GRANT SELECT ON flyway_schema_history TO progress_schema_runtime");
        var flyway = org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "progress_schema_runtime", "synthetic-schema-check")
            .locations("classpath:db/migration", "filesystem:" + migrations).load();
        var strategy = new ProgressApplication().auroraSchemaValidation();
        assertThatCode(() -> strategy.migrate(flyway)).doesNotThrowAnyException();
        java.nio.file.Files.writeString(migrations.resolve("V2__pending.sql"), "CREATE TABLE must_not_be_created(id INTEGER);");
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT to_regclass('must_not_be_created')", String.class)).isNull();
    }

    @BeforeEach void resetData() {
        db.update("DELETE FROM progress_sessions");
        db.update("DELETE FROM progress_users");
        admin.purgeQueue(ProgressConsumer.QUEUE + ".dlq");
    }

    ProgressEvent event(long user, UUID id, long version, double first, int seconds) {
        Instant completed = Instant.parse("2026-10-27T08:00:00Z");
        return new ProgressEvent(1, UUID.randomUUID(), "PracticeCompleted.v1", user, completed,
                new ProgressEvent.Session(id, version, 10, "A listening lesson", "A2", completed,
                        LocalDate.of(2026, 10, 27), seconds, first, 100, 83.4, 2, 1));
    }
    ProgressEvent deletion(long user) {
        return new ProgressEvent(1, UUID.randomUUID(), "UserDeleted.v1", user, Instant.now(), null);
    }

    @Test void duplicateReorderedAndRevisedEventsHaveOneProjection() {
        var original = event(1, sessionId, 1, 70, 30);
        assertThat(store.apply(original)).isEqualTo("applied");
        assertThat(store.apply(original)).isEqualTo("duplicate");
        assertThat(store.apply(event(1, sessionId, 3, 90, 120))).isEqualTo("applied");
        assertThat(store.apply(event(1, sessionId, 2, 80, 60))).isEqualTo("stale");
        var response = store.dashboard(1);
        assertThat(response.summary().completedLessons()).isEqualTo(1);
        assertThat(response.summary().learningMinutes()).isEqualTo(2);
        assertThat(response.summary().averageFirstAttemptAccuracy()).isEqualTo(90);
        assertThat(response.errors()).isEqualTo(new ProgressStore.Errors(2, 1));
        assertThat(response.byLevel()).extracting(ProgressStore.LevelProgress::level).containsExactly("A1", "A2", "B1", "B2");
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test void conflictingRevisionAndCrossUserSessionDoNotOverwriteData() {
        store.apply(event(1, sessionId, 1, 70, 30));
        assertThatThrownBy(() -> store.apply(event(1, sessionId, 1, 99, 30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.apply(event(2, sessionId, 2, 99, 30))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.dashboard(1).summary().averageFirstAttemptAccuracy()).isEqualTo(70);
        assertThat(store.dashboard(2).summary().completedLessons()).isZero();
    }

    @Test void deletionBeforeOrAfterCompletionPreventsResurrection() {
        store.apply(event(1, sessionId, 1, 70, 30));
        store.apply(deletion(1));
        assertThat(store.apply(event(1, sessionId, 100, 99, 30))).isEqualTo("deleted");
        store.apply(deletion(2));
        assertThat(store.apply(event(2, UUID.randomUUID(), 1, 80, 30))).isEqualTo("deleted");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM progress_sessions", Integer.class)).isZero();
    }

    @Test void concurrentDuplicateDeliveriesCommitOnce() throws Exception {
        var event = event(1, sessionId, 1, 80, 40);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Future<String>>();
            for (int i=0; i<8; i++) tasks.add(executor.submit(() -> store.apply(event)));
            for (var task : tasks) assertThat(task.get(15, TimeUnit.SECONDS)).isIn("applied", "duplicate");
        } finally { executor.shutdownNow(); }
        assertThat(store.dashboard(1).summary().completedLessons()).isEqualTo(1);
    }

    @Test void trendOrderingRoundingAndUserIsolationMatchCoreRules() {
        Instant completed = Instant.parse("2026-10-27T08:00:00Z");
        for (int i=0; i<10; i++) {
            var row = event(1, new UUID(0, i+1), 1, 70.04 + i, 3);
            store.apply(row);
        }
        store.apply(event(2, UUID.randomUUID(), 1, 100, 3600));
        var response = store.dashboard(1);
        assertThat(response.summary()).isEqualTo(new ProgressStore.Summary(10, 1, 74.5, 100, 83.4));
        assertThat(response.trend()).hasSize(8);
        assertThat(response.trend().get(0).sessionId()).isEqualTo(new UUID(0, 3).toString());
        assertThat(response.trend().get(7).sessionId()).isEqualTo(new UUID(0, 10).toString());
        assertThat(response.lastEventAt()).isEqualTo(completed);
        assertThat(store.dashboard(999).summary().completedLessons()).isZero();
    }

    @Test void internalEndpointsRejectMissingWrongAndUserSpoofedCredentials() throws Exception {
        mvc.perform(get("/internal/v1/users/1/dashboard").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/users/1/dashboard").header("X-Service-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/users/1/dashboard")
                .header("X-Service-Token", "progress-test-token-at-least-32-chars"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.completedLessons").value(0));
        mvc.perform(get("/internal/v1/users/0/dashboard")
                .header("X-Service-Token", "progress-test-token-at-least-32-chars"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(post("/internal/v1/users/1/dashboard")
                .header("X-Service-Token", "progress-test-token-at-least-32-chars"))
                .andExpect(status().isForbidden());
    }

    @Test void realRabbitDeliveryAndPoisonMessagesReachCommittedProjectionAndDlq() {
        var event = event(1, sessionId, 1, 88, 60);
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbit.send("", ProgressConsumer.QUEUE, new Message(json.writeValueAsBytes(event), properties));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(store.dashboard(1).summary().completedLessons()).isEqualTo(1));
        rabbit.send("", ProgressConsumer.QUEUE, new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(admin.getQueueInfo(ProgressConsumer.QUEUE + ".dlq").getMessageCount()).isEqualTo(1));
    }

    @Test void invalidSchemaAndNonFiniteMetricsCannotEnterTheProjection() {
        var good = event(1, sessionId, 1, 50, 30);
        assertThatThrownBy(() -> store.apply(new ProgressEvent(2, good.eventId(), good.type(), 1,
                good.occurredAt(), good.session()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.apply(event(1, sessionId, 1, Double.NaN, 30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.apply(new ProgressEvent(1, UUID.randomUUID(), "UserDeletedXv1", 1,
                Instant.now(), null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM progress_users", Integer.class)).isZero();
    }
}
