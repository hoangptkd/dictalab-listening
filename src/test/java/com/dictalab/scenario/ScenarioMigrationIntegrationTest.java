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
        for (int previous : new int[]{47,49,50,51,0}) {
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
                    if (previous==51) {
                        String operation="legacy-budget-operation";
                        db.update("""
                                INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                                VALUES (?,(SELECT id FROM app_users WHERE email='legacy@dictalab.local'),?,'migration-test',?,'GENERATE',?,'{}',CURRENT_TIMESTAMP)
                                """,operation,UUID.randomUUID().toString(),"0".repeat(64),UUID.randomUUID().toString());
                        db.update("INSERT INTO scenario_operation_budget(operation_id,user_id,calls_reserved,local_provider,tts) VALUES (?,(SELECT id FROM app_users WHERE email='legacy@dictalab.local'),7,FALSE,FALSE)",operation);
                    }
                }
                var flyway = Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema)
                        .locations("classpath:db/migration", "classpath:db/vendor/postgresql").load();
                flyway.migrate(); flyway.validate();
                assertThat(db.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
                assertThat(flyway.info().current().getVersion().toString()).isEqualTo("52");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND ((table_name='scenario_drafts' AND column_name='workflow_snapshot_json') OR (table_name='scenario_activities' AND column_name='assessment_state_json')) AND is_nullable='YES'",Integer.class,schema)).isEqualTo(2);
                for (String table : new String[]{"scenario_program_versions", "scenario_learning_goals", "scenario_goal_requests", "scenario_drafts", "scenario_sessions", "scenario_activities", "scenario_attempts", "scenario_operations", "scenario_operation_budget", "scenario_media", "scenario_receipts", "scenario_learning_preferences"})
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(
                            previous==49 && java.util.Set.of("scenario_program_versions","scenario_drafts").contains(table) ||
                            previous==51 && java.util.Set.of("scenario_operations","scenario_operation_budget").contains(table) ? 1 : 0);
                if (previous>0) assertThat(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id='legacy-scenario-fixture'", String.class))
                        .isEqualTo("{\"fixture\":\"unchanged\"}");
                if (previous==49) {
                    var draft=db.queryForMap("SELECT input_vi,profile_json,learning_context_json,workflow_snapshot_json FROM scenario_drafts WHERE id='old-draft'");
                    assertThat(draft.get("input_vi")).isEqualTo("Original brief");
                    assertThat(draft.get("profile_json")).isEqualTo("{\"id\":\"supported\"}");
                    assertThat(draft.get("learning_context_json")).isNull();
                    assertThat(draft.get("workflow_snapshot_json")).isNull();
                }
                if (previous==51) {
                    assertThat(db.queryForObject("SELECT calls_reserved FROM scenario_operation_budget WHERE operation_id='legacy-budget-operation'",Integer.class)).isEqualTo(7);
                    var newUser=db.queryForObject("SELECT id FROM app_users WHERE email='legacy@dictalab.local'",Long.class);
                    String operation="new-budget-operation";
                    db.update("""
                            INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                            VALUES (?,? ,?,'migration-test',?,'GENERATE',?,'{}',CURRENT_TIMESTAMP)
                            """,operation,newUser,UUID.randomUUID().toString(),"1".repeat(64),UUID.randomUUID().toString());
                    db.update("INSERT INTO scenario_operation_budget(operation_id,user_id,calls_reserved,local_provider,tts) VALUES (?,?,11,FALSE,FALSE)",operation,newUser);
                    assertThatThrownBy(() -> db.update("UPDATE scenario_operation_budget SET calls_reserved=12 WHERE operation_id=?",operation))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThatThrownBy(() -> db.update("UPDATE scenario_operation_budget SET calls_reserved=0 WHERE operation_id=?",operation))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThatThrownBy(() -> db.update("UPDATE scenario_operation_budget SET calls_reserved=NULL WHERE operation_id=?",operation))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                }
            } finally {
                // Generated schema belongs only to this test's disposable container.
                db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
