package com.dictalab.scenario;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Import after Flyway, never update a stored definition in place (including drafts). */
@Service
public class ScenarioProgramStore implements ApplicationRunner {
    private final JdbcTemplate db;
    private final ScenarioProgramRegistry registry;
    private final ScenarioProperties properties;

    public ScenarioProgramStore(JdbcTemplate db, ScenarioProgramRegistry registry, ScenarioProperties properties) {
        this.db = db; this.registry = registry; this.properties = properties;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) return;
        for (var program : registry.definitions()) {
            String definition = ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(program));
            db.update("""
                    INSERT INTO scenario_program_versions(program_code,program_version,definition_json,definition_hash,status)
                    VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, program.code(), program.version(), definition, program.definitionHash(), program.status().name());
            var rows = db.queryForList("""
                    SELECT definition_json,definition_hash,status FROM scenario_program_versions
                    WHERE program_code=? AND program_version=?
                    """, program.code(), program.version());
            var row = rows.get(0);
            if (!definition.equals(row.get("definition_json")) || !program.definitionHash().equals(row.get("definition_hash"))
                    || !program.status().name().equals(row.get("status")))
                throw new IllegalStateException("Scenario program version already exists with different content");
        }
    }
}
