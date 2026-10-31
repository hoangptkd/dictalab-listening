package com.dictalab.scenario;

import com.dictalab.support.ReliabilityContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "dictalab.testcontainers", matches = "true")
class ScenarioMigrationIntegrationTest {
    @Test void upgradeFrom47PreservesLegacySessionAndFreshInstallHasEmptyScenarioTables() {
        var postgres = ReliabilityContainers.disposablePostgres();
        for (boolean upgrade : new boolean[]{true, false}) {
            String schema = "scenario_migration_" + UUID.randomUUID().toString().replace("-", "");
            var data = new DriverManagerDataSource(postgres.getJdbcUrl()
                    + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    postgres.getUsername(), postgres.getPassword());
            var db = new JdbcTemplate(data);
            try {
                if (upgrade) {
                    Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration", "classpath:db/vendor/postgresql").target("47").load().migrate();
                    db.update("""
                            INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json)
                            VALUES ('legacy-scenario-fixture',(SELECT id FROM app_users WHERE email='legacy@dictalab.local'),'cafe','A1','{"fixture":"unchanged"}')
                            """);
                }
                var flyway = Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema)
                        .locations("classpath:db/migration", "classpath:db/vendor/postgresql").load();
                flyway.migrate(); flyway.validate();
                assertThat(db.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("49");
                for (String table : new String[]{"scenario_program_versions", "scenario_learning_goals", "scenario_goal_requests", "scenario_drafts", "scenario_sessions", "scenario_activities", "scenario_attempts", "scenario_operations", "scenario_operation_budget", "scenario_media", "scenario_receipts"})
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
                if (upgrade) assertThat(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id='legacy-scenario-fixture'", String.class))
                        .isEqualTo("{\"fixture\":\"unchanged\"}");
            } finally {
                // Generated schema belongs only to this test's disposable container.
                db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
