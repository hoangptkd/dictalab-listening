package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Opt-in, sequential MockMvc benchmark; never connects to the application's normal database. */
@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.orm=ERROR"})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "dictalab.benchmark", matches = "true")
class BackendBenchmarkTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired EntityManagerFactory emf;
    @Autowired ObjectMapper json;

    @Test
    void measureHistoryAndDashboard() throws Exception {
        String database = db.queryForObject("SELECT current_database()", String.class);
        assertThat(database).isEqualTo("dictalab_test");
        String label = System.getProperty("dictalab.benchmark.label", "current");
        assertThat(label).matches("[a-zA-Z0-9-]+");
        StringBuilder csv = new StringBuilder("label,sessions,endpoint,iterations,p50_ms,p95_ms,jpa_statements,entities_loaded,response_bytes\n");
        for (int count : List.of(1000, 10000)) {
            long id = seed(count);
            // The security principal is loaded independently; benchmark measures the real filter/controller/service path.
            String email = db.queryForObject("SELECT email FROM app_users WHERE id=?", String.class, id);
            var principal = new DictaLabPrincipal(id, email, "Benchmark", "unused", "USER", true, 0);
            try {
                for (String endpoint : List.of("history", "dashboard")) {
                    for (int i = 0; i < 5; i++) request(endpoint, principal);
                    double[] elapsed = new double[30];
                    long queries = 0, entities = 0;
                    int bytes = 0;
                    var statistics = emf.unwrap(SessionFactory.class).getStatistics();
                    for (int i = 0; i < elapsed.length; i++) {
                        statistics.clear();
                        long start = System.nanoTime();
                        byte[] body = request(endpoint, principal);
                        elapsed[i] = (System.nanoTime() - start) / 1_000_000.0;
                        queries += statistics.getPrepareStatementCount();
                        entities += statistics.getEntityLoadCount();
                        bytes = body.length;
                        var response = json.readTree(body);
                        if (endpoint.equals("dashboard")) assertThat(response.path("summary").path("completedLessons").asInt()).isEqualTo(count);
                        else assertThat(response.isArray() ? response.size() : response.path("totalElements").asInt()).isEqualTo(count);
                    }
                    Arrays.sort(elapsed);
                    csv.append(String.format(Locale.ROOT, "%s,%d,%s,%d,%.3f,%.3f,%d,%d,%d%n",
                            label, count, endpoint, elapsed.length, elapsed[14], elapsed[28],
                            queries / elapsed.length, entities / elapsed.length, bytes));
                }
            } finally {
                db.update("DELETE FROM practice_sessions WHERE user_id=?", id);
                db.update("DELETE FROM app_users WHERE id=?", id);
            }
        }
        Path output = Path.of("target", "backend-benchmark-" + label + ".csv");
        Files.writeString(output, csv.toString());
        System.out.println("BACKEND_BENCHMARK " + output.toAbsolutePath() + "\n" + csv);
    }

    private byte[] request(String endpoint, DictaLabPrincipal principal) throws Exception {
        var response = mvc.perform(get("/api/v1/" + endpoint).with(user(principal)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsByteArray();
    }

    private long seed(int count) {
        String email = "benchmark-" + UUID.randomUUID() + "@dictalab.local";
        long id = db.queryForObject("""
                INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at)
                VALUES (?,'Benchmark','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, email);
        var lessons = db.queryForList("SELECT id FROM lessons WHERE active=TRUE ORDER BY id LIMIT 20", Long.class);
        assertThat(lessons).isNotEmpty();
        var rows = new ArrayList<Object[]>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{UUID.randomUUID().toString(), email, id, lessons.get(i % lessons.size()),
                    Timestamp.from(base.plusSeconds(i * 60L)), Timestamp.from(base.plusSeconds(i * 60L + 30)), i < lessons.size()});
        }
        db.batchUpdate("""
                INSERT INTO practice_sessions(id,learner_id,user_id,lesson_id,status,started_at,completed_at,
                    total_practice_seconds,first_attempt_accuracy,final_accuracy,overall_score,first_exposure,
                    known_missed_count,unknown_count)
                VALUES (?,?,?,?,'COMPLETED',?,?,60,75,90,80,?,2,1)
                """, rows);
        return id;
    }
}
