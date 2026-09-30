package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ListeningComprehensionService;
import com.dictalab.service.ShopAiKeyClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ListeningComprehensionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired ListeningComprehensionService service;
    @MockitoBean ShopAiKeyClient ai;

    @Test
    void seededListeningUnitsCoverEveryLevelWithAlignedAudioAndValidContent() throws Exception {
        var units = db.queryForList("SELECT lesson_id,audio_url,level,duration_seconds,content_json FROM listening_units WHERE published=TRUE");
        for (String level : new String[] {"A1", "A2", "B1", "B2"}) {
            assertThat(units.stream().filter(unit -> level.equals(unit.get("level"))).count()).isGreaterThanOrEqualTo(2);
        }
        for (Map<String, Object> unit : units) {
            long lessonId = ((Number) unit.get("lesson_id")).longValue();
            String audioUrl = (String) unit.get("audio_url");
            assertThat(getClass().getResource("/static" + audioUrl)).isNotNull();
            Integer sentences = db.queryForObject("""
                    SELECT COUNT(*) FROM lesson_sentences
                    WHERE lesson_id=? AND audio_url=? AND audio_start_seconds IS NOT NULL AND audio_end_seconds IS NOT NULL
                    """, Integer.class, lessonId, audioUrl);
            assertThat(sentences).isGreaterThanOrEqualTo(5);
            Double end = db.queryForObject("SELECT MAX(audio_end_seconds) FROM lesson_sentences WHERE lesson_id=? AND audio_url=?",
                    Double.class, lessonId, audioUrl);
            assertThat(Math.abs(end - ((Number) unit.get("duration_seconds")).doubleValue())).isLessThan(1);
            var content = json.readTree((String) unit.get("content_json"));
            assertThat(content.path("facts").size()).isBetween(4, 12);
            assertThat(content.path("questions").size()).isBetween(5, 8);
        }
    }

    @Test
    void twoPassAssessmentHidesAnswersUntilCompleteAndKeepsSessionsPrivate() throws Exception {
        when(ai.configured()).thenReturn(true);
        when(ai.completeJson(anyString(), anyString())).thenReturn(json.readTree("""
                {"facts":[{"factId":"F1","status":"CAPTURED","explanationVi":"Đúng"},
                {"factId":"F2","status":"PARTIAL","explanationVi":"Một phần"},
                {"factId":"F3","status":"MISSED","explanationVi":"Thiếu"},
                {"factId":"F4","status":"INCORRECT","explanationVi":"Sai"}]}
                """));
        AppUser owner = users.save(new AppUser("listening-" + UUID.randomUUID() + "@test.local",
                "Listener", "unused", Instant.now()));
        AppUser stranger = users.save(new AppUser("listening-" + UUID.randomUUID() + "@test.local",
                "Other", "unused", Instant.now()));
        DictaLabPrincipal learner = principal(owner);
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String start = "{\"unitId\":" + unit + ",\"mode\":\"TWO_PASS\",\"heardBefore\":false}";
        mvc.perform(post("/api/v1/listening/sessions").with(user(learner)).contentType(MediaType.APPLICATION_JSON).content(start))
                .andExpect(status().isForbidden());
        String id = json.readTree(mvc.perform(post("/api/v1/listening/sessions").with(user(learner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(start)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY")).andReturn().getResponse().getContentAsString())
                .path("sessionId").asText();
        String path = "/api/v1/listening/sessions/" + id;
        mvc.perform(get(path).with(user(principal(stranger)))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/review").with(user(learner))).andExpect(status().isConflict());
        mvc.perform(get("/audio/elllo/segments.json").with(user(learner))).andExpect(status().isForbidden());
        mvc.perform(get("/audio/elllo/segments%2Ejson").with(user(learner))).andExpect(status().is4xxClientError());

        for (int pass = 1; pass <= 2; pass++) {
            String token = json.readTree(mvc.perform(post(path + "/play").with(user(learner)).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PLAYING"))
                    .andReturn().getResponse().getContentAsString()).path("playbackToken").asText();
            mvc.perform(post(path + "/play").with(user(learner)).with(csrf())).andExpect(status().isConflict());
            db.update("UPDATE listening_passes SET playback_started_at=? WHERE session_id=? AND pass_number=?",
                    Instant.now().minusSeconds(120), id, pass);
            mvc.perform(post(path + "/play/complete").with(user(learner)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("RECALL"));
            if (pass == 2) {
                mvc.perform(get(path).with(user(learner)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.recallDraft").value("Tony welcomes Beth"));
            }
            mvc.perform(post(path + "/recall").with(user(learner)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Tony welcomes Beth\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].correct").doesNotExist());
            mvc.perform(get(path + "/review").with(user(learner))).andExpect(status().isConflict());
            mvc.perform(post(path + "/answers").with(user(learner)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(answers(id, pass == 2)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stage").value(pass == 1 ? "PASS1_RESULT" : "COMPLETE"));
            if (pass == 1) {
                service.gradePending();
                mvc.perform(get(path + "/pass-1-result").with(user(learner)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.comprehensionScore").value(100.0))
                        .andExpect(jsonPath("$.recallScore").value(37.5))
                        .andExpect(jsonPath("$.facts").doesNotExist());
                mvc.perform(get(path + "/review").with(user(learner))).andExpect(status().isConflict());
                mvc.perform(post(path + "/second-pass").with(user(learner)).with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.stage").value("READY"))
                        .andExpect(jsonPath("$.passNumber").value(2));
            }
        }
        service.gradePending();
        mvc.perform(get(path + "/report").with(user(learner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.passes[0].comprehension_score").value(100.0))
                .andExpect(jsonPath("$.passes[1].comprehension_score").value(80.0))
                .andExpect(jsonPath("$.passes[0].recall_score").value(37.5))
                .andExpect(jsonPath("$.questionTypes.MAIN_IDEA[0]").value(100.0));
        mvc.perform(get(path + "/review").with(user(learner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.questions[0].correct").value(0))
                .andExpect(jsonPath("$.sentences").isArray());
    }

    @Test
    void interruptedPassCannotBeReplayedOrReportedAndAdminApiIsProtected() throws Exception {
        AppUser owner = users.save(new AppUser("listening-" + UUID.randomUUID() + "@test.local",
                "Listener", "unused", Instant.now()));
        DictaLabPrincipal learner = principal(owner);
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String body = "{\"unitId\":" + unit + ",\"mode\":\"ONE_PASS\",\"heardBefore\":true}";
        String id = json.readTree(mvc.perform(post("/api/v1/listening/sessions").with(user(learner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstExposure").value(false))
                .andReturn().getResponse().getContentAsString()).path("sessionId").asText();
        String path = "/api/v1/listening/sessions/" + id;
        mvc.perform(post(path + "/play").with(user(learner)).with(csrf())).andExpect(status().isOk());
        mvc.perform(post(path + "/abandon").with(user(learner)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("ABANDONED"));
        mvc.perform(post(path + "/play").with(user(learner)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get(path + "/report").with(user(learner))).andExpect(status().isConflict());
        mvc.perform(get("/api/admin/listening/units").with(user(learner))).andExpect(status().isForbidden());
        owner.promoteToAdmin(Instant.now());
        users.saveAndFlush(owner);
        DictaLabPrincipal admin = DictaLabPrincipal.from(owner);
        mvc.perform(get("/api/admin/listening/units").with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content_json").exists());
        mvc.perform(put("/api/admin/listening/units/" + unit).with(user(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Changed\",\"content\":{\"facts\":[],\"questions\":[]}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void questionDraftAndOptionOrderSurviveReloadWithoutRevealingAnswers() throws Exception {
        AppUser owner = users.save(new AppUser("draft-" + UUID.randomUUID() + "@test.local",
                "Listener", "unused", Instant.now()));
        Long unit = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String id = (String) service.start(owner.getId(), unit, "ONE_PASS", false).get("sessionId");
        db.update("UPDATE listening_sessions SET stage='QUESTIONS' WHERE id=?", id);
        String path = "/api/v1/listening/sessions/" + id;
        String first = mvc.perform(get(path).with(user(principal(owner)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].correct").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var order = json.readTree(db.queryForObject("SELECT option_order_json FROM listening_sessions WHERE id=?",
                String.class, id));
        java.util.Set<Integer> correctPositions = new java.util.HashSet<>();
        for (int i = 1; i <= 5; i++) {
            var indices = order.path("Q" + i);
            for (int position = 0; position < indices.size(); position++)
                if (indices.path(position).asInt() == 0) correctPositions.add(position);
        }
        assertThat(correctPositions.size()).isGreaterThan(1);
        mvc.perform(put(path + "/answers").with(user(principal(owner))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{\"Q1\":2}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answersDraft.Q1").value(2));
        String after = mvc.perform(get(path).with(user(principal(owner)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.answersDraft.Q1").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(after).path("questions")).isEqualTo(json.readTree(first).path("questions"));
    }

    @Test
    @Transactional
    void replacingAndRetiringUnitKeepsOldSessionButRemovesOldVersionFromCatalog() throws Exception {
        AppUser owner = users.save(new AppUser("version-" + UUID.randomUUID() + "@test.local",
                "Listener", "unused", Instant.now()));
        Long original = db.queryForObject("SELECT id FROM listening_units WHERE published=TRUE ORDER BY id LIMIT 1", Long.class);
        String sessionId = (String) service.start(owner.getId(), original, "ONE_PASS", false).get("sessionId");

        long replacement = ((Number) service.copyDraft(original).get("id")).longValue();
        assertThat(service.units(owner.getId())).anyMatch(unit -> original.equals(unit.get("id")));
        service.publishUnit(replacement);
        assertThat(service.state(owner.getId(), sessionId).get("unitId")).isEqualTo(original);
        assertThat(service.units(owner.getId())).noneMatch(unit -> original.equals(unit.get("id")));
        assertThat(service.units(owner.getId())).anyMatch(unit -> replacement == ((Number) unit.get("id")).longValue());
        assertThat(service.adminUnits().stream().filter(unit -> original.equals(unit.get("id"))).findFirst().orElseThrow()
                .get("retired")).isEqualTo(true);
        assertThatThrownBy(() -> service.publishUnit(original)).isInstanceOf(InvalidPracticeStateException.class);

        service.retireUnit(replacement);
        assertThat(service.units(owner.getId())).noneMatch(unit -> replacement == ((Number) unit.get("id")).longValue());
        long nextDraft = ((Number) service.copyDraft(replacement).get("id")).longValue();
        assertThat(service.adminUnits().stream().filter(unit -> nextDraft == ((Number) unit.get("id")).longValue())
                .findFirst().orElseThrow().get("retired")).isEqualTo(false);
        assertThatThrownBy(() -> service.copyDraft(nextDraft)).isInstanceOf(InvalidPracticeStateException.class);
    }

    private DictaLabPrincipal principal(AppUser user) {
        return new DictaLabPrincipal(user.getId(), user.getEmail(), user.getDisplayName(),
                "unused", "USER", true);
    }

    private String answers(String sessionId, boolean wrongFirst) throws Exception {
        String orderJson = db.queryForObject("SELECT option_order_json FROM listening_sessions WHERE id=?",
                String.class, sessionId);
        var order = json.readTree(orderJson);
        Map<String, Integer> selected = new java.util.HashMap<>();
        for (int i = 1; i <= 5; i++) {
            String id = "Q" + i;
            var positions = order.path(id);
            for (int position = 0; position < positions.size(); position++)
                if (positions.path(position).asInt() == 0)
                    selected.put(id, wrongFirst && i == 1 ? (position + 1) % positions.size() : position);
        }
        return json.writeValueAsString(Map.of("answers", selected));
    }
}
