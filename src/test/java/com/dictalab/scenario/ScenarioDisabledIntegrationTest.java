package com.dictalab.scenario;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "dictalab.scenario.enabled=false")
@AutoConfigureMockMvc
class ScenarioDisabledIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired ScenarioProgramRegistry registry;

    @Test void defaultRolloutHidesGoalProgramsAndRejectsWritesButPreservesReads() throws Exception {
        var owner = users.saveAndFlush(new AppUser(UUID.randomUUID() + "@disabled.test", "Learner", "unused", Instant.now()));
        var auth = user(DictaLabPrincipal.from(owner));
        var program = registry.require("ielts-listening", 1);
        db.update("""
                INSERT INTO scenario_program_versions(program_code,program_version,definition_json,definition_hash,status)
                VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                """, program.code(), program.version(), ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(program)),
                program.definitionHash(), program.status().name());
        db.update("""
                INSERT INTO scenario_learning_goals(id,user_id,program_code,program_version,component,scale_id,target_value,provenance,version)
                VALUES (?,?,'ielts-listening',1,'LISTENING','ielts-listening-band','6.5','USER_DECLARED_GOAL',1)
                """, UUID.randomUUID().toString(), owner.getId());
        mvc.perform(get("/api/v2/scenarios/catalog").with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.goalPrograms").isEmpty());
        mvc.perform(put("/api/v2/scenarios/learning-goal").with(auth).with(csrf()).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(ScenarioIntegrationTest.BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SCENARIO_DISABLED"));
        mvc.perform(get("/api/v2/scenarios/learning-goals").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].target.value").value("6.5"));
        mvc.perform(post("/api/v2/scenarios/drafts").with(auth).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content("""
                    {"inputVi":"Tôi muốn đặt bánh","programCode":"general-communication","programVersion":1,"profileId":"supported"}
                    """))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNSUPPORTED_PROGRAM_MODE"));
    }
}
