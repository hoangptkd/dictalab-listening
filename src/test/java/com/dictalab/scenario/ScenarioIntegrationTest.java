package com.dictalab.scenario;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AiPlatformClient;
import com.dictalab.service.AiUsageBudget;
import com.dictalab.service.CartesiaClient;
import com.dictalab.service.GeminiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "dictalab.scenario.enabled=true")
@AutoConfigureMockMvc
class ScenarioIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AppUserRepository users;
    @Autowired ScenarioProgramStore programs;
    @Autowired ScenarioGoalService goals;
    @MockitoBean AiPlatformClient ai;
    @MockitoBean GeminiClient gemini;
    @MockitoBean CartesiaClient tts;
    @MockitoBean AiUsageBudget budget;
    AppUser owner;
    static final String BODY = """
            {"contractVersion":"scenario-goal.v1","programCode":"ielts-listening","programVersion":1,
             "target":{"scaleId":"ielts-listening-band","component":"LISTENING","value":"6.5"},"expectedVersion":0}
            """;

    @BeforeEach void setup() {
        owner = users.saveAndFlush(new AppUser(UUID.randomUUID() + "@scenario.test", "Learner", "unused", Instant.now()));
    }

    @Test void catalogIsAuthenticatedAndNeverAdvertisesUnimplementedGeneration() throws Exception {
        mvc.perform(get("/api/v2/scenarios/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/scenarios/catalog").with(user(DictaLabPrincipal.from(owner))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.generationAvailable").value(false))
                .andExpect(jsonPath("$.supportedPracticeModes").isEmpty()).andExpect(jsonPath("$.goalPrograms.length()").value(3))
                .andExpect(jsonPath("$.goalPrograms[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.goalPrograms[0].promptArtifactRefs").doesNotExist());
        verifyNoInteractions(ai, gemini, tts, budget);
    }

    @Test void goalsAreCsrfProtectedOwnerScopedAndNeverAssessedScores() throws Exception {
        mvc.perform(put("/api/v2/scenarios/learning-goal").with(user(DictaLabPrincipal.from(owner)))
                .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        save(UUID.randomUUID().toString(), BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance").value("USER_DECLARED_GOAL"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(header().string("Cache-Control", "no-store"));
        var other = users.saveAndFlush(new AppUser(UUID.randomUUID() + "@scenario.test", "Other", "unused", Instant.now()));
        mvc.perform(get("/api/v2/scenarios/learning-goals").with(user(DictaLabPrincipal.from(other))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        String overall = BODY.replace("ielts-listening-band", "ielts-overall-band").replace("LISTENING", "OVERALL");
        save(UUID.randomUUID().toString(), overall).andExpect(status().isOk()).andExpect(jsonPath("$.target.component").value("OVERALL"));
        assertThat(goals.list(owner.getId())).hasSize(2);
        verifyNoInteractions(ai, gemini, tts, budget);
    }

    @Test void duplicateRetryReturnsOriginalReceiptAndDifferentPayloadConflicts() throws Exception {
        String key = UUID.randomUUID().toString();
        String original = save(key, BODY).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        save(UUID.randomUUID().toString(), BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":1").replace("6.5", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        save(key, BODY).andExpect(status().isOk()).andExpect(content().json(original));
        save(key, BODY.replace("6.5", "8")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        save(UUID.randomUUID().toString(), BODY).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GOAL_VERSION_CONFLICT"));
        assertThat(goals.list(owner.getId()).get(0).target().value()).isEqualTo("7");
    }

    @Test void concurrentDuplicateCreateCommitsOneGoalAndOneReceipt() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = ScenarioJson.read(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8), ScenarioGoalRequest.class);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> goals.save(owner.getId(), key, request));
            var second = executor.submit(() -> goals.save(owner.getId(), key, request));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_goal_requests WHERE user_id=?", Integer.class, owner.getId())).isEqualTo(1);
        assertThat(goals.list(owner.getId())).hasSize(1);
    }

    @Test void receiptFailureRollsBackGoalWrite() {
        String key = UUID.randomUUID().toString();
        String constraint = "scenario_receipt_fixture_" + key.replace("-", "");
        db.execute("ALTER TABLE scenario_goal_requests ADD CONSTRAINT " + constraint
                + " CHECK (idempotency_key <> '" + key + "')");
        try {
            var request = ScenarioJson.read(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8), ScenarioGoalRequest.class);
            assertThatThrownBy(() -> goals.save(owner.getId(), key, request))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(goals.list(owner.getId())).isEmpty();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_goal_requests WHERE user_id=?", Integer.class, owner.getId())).isZero();
        } finally { db.execute("ALTER TABLE scenario_goal_requests DROP CONSTRAINT " + constraint); }
    }

    @Test void concurrentEditsAtSameVersionCannotOverwriteEachOther() throws Exception {
        var bytes = java.nio.charset.StandardCharsets.UTF_8;
        var initial = ScenarioJson.read(BODY.getBytes(bytes), ScenarioGoalRequest.class);
        goals.save(owner.getId(), UUID.randomUUID().toString(), initial);
        var edit = ScenarioJson.read(BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":1").getBytes(bytes), ScenarioGoalRequest.class);
        var executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<String> work = () -> {
            try { goals.save(owner.getId(), UUID.randomUUID().toString(), edit); return "SAVED"; }
            catch (ScenarioFailure conflict) { return conflict.code; }
        };
        try {
            var first = executor.submit(work); var second = executor.submit(work);
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SAVED", "GOAL_VERSION_CONFLICT");
        } finally { executor.shutdownNow(); }
        assertThat(goals.list(owner.getId()).get(0).version()).isEqualTo(2);
    }

    @Test void invalidAndOversizedRequestsCannotWriteAndErrorsAreSanitizedAndCorrelated() throws Exception {
        save(UUID.randomUUID().toString(), BODY.replace("6.5", "6.3"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TARGET"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").value("scenario-test"))
                .andExpect(header().string("Cache-Control", "no-store"));
        save(UUID.randomUUID().toString(), BODY.replace("\"value\":\"6.5\"", "\"value\":6.5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        save("not-a-uuid", BODY).andExpect(status().isBadRequest());
        save(UUID.randomUUID().toString(), BODY + " ".repeat(32769)).andExpect(status().isPayloadTooLarge());
        assertThat(goals.list(owner.getId())).isEmpty();
    }

    @Test void importerIsRepeatableAndDetectsStoredDefinitionDrift() {
        programs.run(null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_program_versions", Integer.class)).isEqualTo(3);
        var saved = db.queryForObject("SELECT definition_json FROM scenario_program_versions WHERE program_code='general-communication'", String.class);
        try {
            db.update("UPDATE scenario_program_versions SET definition_json='{}' WHERE program_code='general-communication'");
            assertThatThrownBy(() -> programs.run(null)).isInstanceOf(IllegalStateException.class);
        } finally {
            db.update("UPDATE scenario_program_versions SET definition_json=? WHERE program_code='general-communication'", saved);
        }
    }

    @Test void accountDeletionCascadesGoalsAndIdempotencyReceipts() throws Exception {
        save(UUID.randomUUID().toString(), BODY).andExpect(status().isOk());
        users.deleteById(owner.getId()); users.flush();
        assertThat(goals.list(owner.getId())).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scenario_goal_requests WHERE user_id=?", Integer.class, owner.getId())).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions save(String key, String body) throws Exception {
        return mvc.perform(put("/api/v2/scenarios/learning-goal").with(user(DictaLabPrincipal.from(owner))).with(csrf())
                .header("Idempotency-Key", key).header("X-Request-ID", "scenario-test").contentType("application/json").content(body));
    }
}
