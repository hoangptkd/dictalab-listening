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
    @Test void upgradesPreserveLegacySessionAndHistoricalDraftAndFreshInstallHasEmptyScenarioTables() throws Exception {
        var postgres = ReliabilityContainers.disposablePostgres();
        for (int previous : new int[]{47,49,0}) {
            String schema = "scenario_migration_" + UUID.randomUUID().toString().replace("-", "");
            var data = new DriverManagerDataSource(postgres.getJdbcUrl()
                    + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                    postgres.getUsername(), postgres.getPassword());
            var db = new JdbcTemplate(data);
            try {
                if (previous>0) {
                    Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema)
                            .locations("classpath:db/migration", "classpath:db/vendor/postgresql").target(Integer.toString(previous)).load().migrate();
                    db.update("""
                            INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json)
                            VALUES ('legacy-scenario-fixture',(SELECT id FROM app_users WHERE email='legacy@dictalab.local'),'cafe','A1','{"fixture":"unchanged"}')
                            """);
                    if (previous==49) {
                        var program=new ScenarioProgramRegistry().require("general-communication",1);
                        db.update("INSERT INTO scenario_program_versions(program_code,program_version,definition_json,definition_hash,status) VALUES (?,1,?,?,'DRAFT')",
                                program.code(),ScenarioJson.MAPPER.writeValueAsString(program),program.definitionHash());
                        db.update("""
                                INSERT INTO scenario_drafts(id,user_id,input_vi,program_code,program_version,program_hash,profile_json,target_json,status,family_id,expires_at)
                                VALUES ('old-draft',(SELECT id FROM app_users WHERE email='legacy@dictalab.local'),'Original brief',?,1,?,'{"id":"supported"}','[]','READY','old-family',CURRENT_TIMESTAMP)
                                """,program.code(),program.definitionHash());
                    }
                }
                var flyway = Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema)
                        .locations("classpath:db/migration", "classpath:db/vendor/postgresql").load();
                flyway.migrate(); flyway.validate();
                assertThat(db.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("50");
                for (String table : new String[]{"scenario_program_versions", "scenario_learning_goals", "scenario_goal_requests", "scenario_drafts", "scenario_sessions", "scenario_activities", "scenario_attempts", "scenario_operations", "scenario_operation_budget", "scenario_media", "scenario_receipts", "scenario_learning_preferences"})
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(previous==49 && java.util.Set.of("scenario_program_versions","scenario_drafts").contains(table) ? 1 : 0);
                if (previous>0) assertThat(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id='legacy-scenario-fixture'", String.class))
                        .isEqualTo("{\"fixture\":\"unchanged\"}");
                if (previous==49) {
                    var draft=db.queryForMap("SELECT input_vi,profile_json,learning_context_json FROM scenario_drafts WHERE id='old-draft'");
                    assertThat(draft.get("input_vi")).isEqualTo("Original brief");
                    assertThat(draft.get("profile_json")).isEqualTo("{\"id\":\"supported\"}");
                    assertThat(draft.get("learning_context_json")).isNull();
                }
            } finally {
                // Generated schema belongs only to this test's disposable container.
                db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
