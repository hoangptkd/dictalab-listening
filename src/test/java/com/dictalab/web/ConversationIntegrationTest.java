package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.CartesiaClient;
import com.dictalab.service.ConversationService;
import com.dictalab.service.ShopAiKeyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"dictalab.conversation.enabled=true", "dictalab.coach.enabled=false"})
@AutoConfigureMockMvc
class ConversationIntegrationTest {
    @Test void recentSessionsReportCompletionWithoutExposingTheirStoredState() {
        var owner = users.saveAndFlush(new AppUser("recent-" + UUID.randomUUID() + "@test.local", "Recent", "unused", Instant.now()));
        for (boolean completed : new boolean[]{false, true}) {
            db.update("INSERT INTO ai_conversation_sessions(id,user_id,scenario,difficulty,state_json) VALUES(?,?,'cafe','A1',?)",
                    UUID.randomUUID().toString(), owner.getId(), "{\"completed\":" + completed + ",\"private\":\"hidden\"}");
        }
        var catalog = service.catalog(owner.getId());
        @SuppressWarnings("unchecked") var sessions = (java.util.List<Map<String,Object>>) catalog.get("sessions");
        assertThat(sessions).extracting(row -> row.get("completed")).containsExactlyInAnyOrder(false, true);
        assertThat(sessions).allSatisfy(row -> assertThat(row).doesNotContainKey("state_json"));
    }
    @Autowired ConversationService service;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired com.dictalab.config.ConversationProperties config;
    @Autowired com.dictalab.service.AiUsageBudget budget;
    @Autowired MockMvc mvc;
    @MockitoBean ShopAiKeyClient ai;
    @MockitoBean CartesiaClient tts;
    long owner;

    @BeforeEach void setup() {
        owner = users.saveAndFlush(new AppUser("conversation-" + UUID.randomUUID() + "@test.local", "Learner", "unused", Instant.now())).getId();
        when(ai.configured()).thenReturn(true); when(tts.enabled()).thenReturn(true);
        when(tts.synthesize(anyString())).thenReturn(new byte[]{1,2,3});
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("ten"));
        when(ai.completeJson(startsWith("Audit a listening task"), anyString(), anyString()))
                .thenReturn(json.readTree("{\"valid\":true,\"reason\":\"\"}"));
    }
    private JsonNode prompt(String detail) {
        return json.readTree("""
                {"taskVi":"Nghe giờ hẹn và địa điểm.","questionVi":"Bạn cần đến đâu, lúc nào?",
                "skill":"Nghe giờ và địa điểm","utteranceEn":"Please meet me at the %s cafe at %s tomorrow. Bring your ticket with you, and we can talk about our plans over a cup of tea.",
                "translationVi":"Hãy gặp tôi ở quán lúc giờ đã hẹn vào ngày mai. Mang theo vé.",
                "facts":[{"id":"f1","textVi":"Quán %s","evidenceEn":"at the %s cafe"},
                {"id":"f2","textVi":"Giờ hẹn %s","evidenceEn":"at %s tomorrow"}]}
                """.formatted(detail, detail, detail, detail, detail, detail));
    }
    private JsonNode grade(String first, String second, String evidence) {
        JsonNode evaluation = json.readTree("""
                {"facts":[{"factId":"f1","status":"%s","evidenceVi":"%s"},
                {"factId":"f2","status":"%s","evidenceVi":"%s"}],"feedbackVi":"Bạn đã ghi lại ý nghe được.",
                "clarificationVi":"Bạn nhớ thêm gì về giờ hẹn?"}
                """.formatted(first, evidence, second, evidence));
        return evaluation;
    }
    private String start() {
        String id = service.create(owner, "cafe", "A1").path("id").asText();
        service.next(owner, id, 0); return id;
    }

    @Test void missingApiKeyDisablesConversationWithoutWritingASession() {
        when(ai.configured()).thenReturn(false);
        assertThat(service.catalog(owner).get("enabled")).isEqualTo(false);
        assertThatThrownBy(() -> service.create(owner, "cafe", "A1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_sessions WHERE user_id=?", Integer.class, owner)).isZero();
        verify(ai, never()).completeJson(anyString(), anyString(), anyString());
    }

    @Test void semanticReviewRejectsUnaskedDetailsAndRepairsBeforePublishing() {
        var invalid = (tools.jackson.databind.node.ObjectNode) prompt("ten");
        ((tools.jackson.databind.node.ObjectNode) invalid.path("facts").path(0))
                .put("textVi", "Quán ten, mang theo vé");
        when(ai.completeJson(startsWith("You design"), anyString(), anyString()))
                .thenReturn(invalid, prompt("eleven"));
        when(ai.completeJson(startsWith("Audit a listening task"), anyString(), anyString()))
                .thenReturn(json.readTree("{\"valid\":false,\"reason\":\"Ticket is not requested by questionVi\"}"),
                        json.readTree("{\"valid\":true,\"reason\":\"\"}"));
        String id = service.create(owner, "cafe", "A1").path("id").asText();
        assertThat(service.next(owner, id, 0).path("turnNumber").asInt()).isEqualTo(1);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai, times(2)).completeJson(startsWith("You design"), input.capture(), anyString());
        JsonNode repair = json.readTree(input.getAllValues().get(1));
        assertThat(repair.path("validationError").asText()).contains("Ticket is not requested");
        assertThat(repair.path("previousInvalidPrompt")).isEqualTo(invalid);
        var stored = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        assertThat(stored.path("turns").path(0).path("prompt").path("utteranceEn").asText()).contains("eleven");
    }

    @Test void failedOrMalformedSemanticReviewNeverPublishesAnUncheckedTask() {
        String id = service.create(owner, "cafe", "A1").path("id").asText();
        for (JsonNode review : java.util.List.of(
                json.readTree("{\"valid\":false,\"reason\":\"Question has no answer in utterance\"}"),
                json.readTree("{\"valid\":\"true\",\"reason\":\"\"}"))) {
            when(ai.completeJson(startsWith("Audit a listening task"), anyString(), anyString())).thenReturn(review);
            assertThatThrownBy(() -> service.next(owner, id, 0)).isInstanceOf(ResponseStatusException.class);
            assertThat(service.get(owner, id).path("turnNumber").asInt()).isZero();
            assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        }
        when(ai.completeJson(startsWith("Audit a listening task"), anyString(), anyString()))
                .thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.next(owner, id, 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turnNumber").asInt()).isZero();
    }

    @Test void challengeAndPreciseMeaningRulesReachTheProvider() {
        String id = start();
        verify(ai).completeJson(contains("one plausible distractor"), anyString(), anyString());
        verify(ai).completeJson(contains("A1: two concrete requested details"), anyString(), anyString());
        verify(ai).completeJson(contains("A2: two to three details across sentences"), anyString(), anyString());
        verify(ai).completeJson(contains("B1: two to three details requiring connection"), anyString(), anyString());
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("PARTIAL", "INCORRECT", "quán"));
        var result = service.answer(owner, id, 1, UUID.randomUUID(), "quán");
        assertThat(result.path("turns").path(0).path("done").asBoolean()).isFalse();
        verify(ai).completeJson(contains("a relevant keyword alone does not prove"), anyString());
        verify(ai).completeJson(contains("Contradictions in the latest reply"), anyString());
    }

    @Test void audioCleanupPreservesHistoryRecentAudioAndAllowsResumingOldSessions() {
        String old = start();
        service.play(owner, old, 1, "original", false);
        String recent = start();
        service.play(owner, recent, 1, "original", false);
        String unfinished = start();
        service.play(owner, unfinished, 1, "original", false);
        String busy = start();
        service.play(owner, busy, 1, "original", false);
        for (String id : java.util.List.of(old, recent)) {
            var state = (tools.jackson.databind.node.ObjectNode) json.readTree(db.queryForObject(
                    "SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
            state.put("completed", true);
            db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", state.toString(), id);
        }
        for (String id : java.util.List.of(old, unfinished, busy)) db.update(
                "UPDATE ai_conversation_sessions SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(86400L * 31)), id);
        db.update("UPDATE ai_conversation_sessions SET lease_id=?,busy_until=? WHERE id=?", "cleanup-test",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)), busy);
        String history = db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, old);
        service.cleanupAudio();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, old)).isZero();
        assertThat(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, old)).isEqualTo(history);
        assertThat(service.audio(owner, recent, 1, "original")).containsExactly(1, 2, 3);
        assertThat(service.audio(owner, busy, 1, "original")).containsExactly(1, 2, 3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, unfinished)).isZero();
        service.cleanupAudio(); // Repeated maintenance is safe.
        service.play(owner, unfinished, 1, "original", false);
        assertThat(service.audio(owner, unfinished, 1, "original")).containsExactly(1, 2, 3);
        db.update("UPDATE ai_conversation_sessions SET busy_until=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), busy);
        service.cleanupAudio();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, busy)).isZero();
    }

    @Test void clarificationUsesShopAiKeyWithExplicitContextAndDoesNotDependOnCoach() {
        assertThat(service.catalog(owner).get("enabled")).isEqualTo(true);
        String id = start();
        String utterance = prompt("ten").path("utteranceEn").asText();
        when(ai.completeJson(anyString(), anyString())).thenReturn(json.valueToTree(Map.of(
                "utteranceEn", utterance, "translationVi", "Đến quán ten vào giờ ten ngày mai, mang theo vé.")));
        service.help(owner, id, 1, "clarify");
        service.help(owner, id, 1, "clarify");
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai, times(1)).completeJson(contains("Preserve ALL facts exactly"), input.capture());
        JsonNode context = json.readTree(input.getValue());
        assertThat(context.path("utteranceEn").asText()).isEqualTo(utterance);
        assertThat(context.path("facts").size()).isEqualTo(2);
        assertThat(context.has("session_id")).isFalse();
        var stored = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        assertThat(stored.path("turns").path(0).path("clarification").path("utteranceEn").asText()).isEqualTo(utterance);
        assertThat(stored.path("turns").path(0).path("attempts").size()).isZero();
    }

    @Test void rejectsShortOrLongUtterancesAndReleasesLeaseForRetry() {
        String id = service.create(owner, "cafe", "A1").path("id").asText();
        for (int words : new int[]{19, 66}) {
            var invalid = (tools.jackson.databind.node.ObjectNode) prompt("ten");
            invalid.put("utteranceEn", "word ".repeat(words).trim());
            ((tools.jackson.databind.node.ObjectNode) invalid.path("facts").path(0)).put("evidenceEn", "word");
            ((tools.jackson.databind.node.ObjectNode) invalid.path("facts").path(1)).put("evidenceEn", "word word");
            when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(invalid);
            assertThatThrownBy(() -> service.next(owner, id, 0)).isInstanceOf(ResponseStatusException.class);
            assertThat(service.get(owner, id).path("turnNumber").asInt()).isZero();
            assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        }
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("ten"));
        assertThat(service.next(owner, id, 0).path("turnNumber").asInt()).isEqualTo(1);
        verify(ai, never()).completeJson(anyString(), anyString());
    }

    @Test void invalidLegacyPrefetchIsReplacedRatherThanTrappingNextInRetryLoop() {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
        service.answer(owner, id, 1, UUID.randomUUID(), "quán");
        var stored = (tools.jackson.databind.node.ObjectNode) json.readTree(db.queryForObject(
                "SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        var legacy = (tools.jackson.databind.node.ObjectNode) prompt("eleven");
        legacy.put("utteranceEn", "A short legacy task.");
        stored.set("nextPrompt", legacy);
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?", stored.toString(), id);
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("twelve"));
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
        var saved = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        assertThat(saved.has("nextPrompt")).isFalse();
        assertThat(saved.path("turns").path(1).path("prompt").path("utteranceEn").asText()).contains("twelve");
    }

    @Test void invalidGenerationGetsOneRepairWithSameContextButNetworkFailuresAreNotRetried() {
        var invalid = (tools.jackson.databind.node.ObjectNode) prompt("ten");
        invalid.remove("skill");
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(invalid, prompt("eleven"));
        String id = service.create(owner, "cafe", "A1").path("id").asText();
        assertThat(service.next(owner, id, 0).path("turnNumber").asInt()).isEqualTo(1);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai, times(2)).completeJson(startsWith("You design"), input.capture(), eq("gemini-2.5-flash"));
        JsonNode first = json.readTree(input.getAllValues().get(0)), repaired = json.readTree(input.getAllValues().get(1));
        assertThat(repaired.path("scenario")).isEqualTo(first.path("scenario"));
        assertThat(repaired.path("turnNumber").asInt()).isEqualTo(1);
        assertThat(repaired.path("validationError").asText()).isEqualTo("Invalid JSON schema");
        assertThat(repaired.path("previousInvalidPrompt")).isEqualTo(invalid);
        clearInvocations(ai);
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        String failedId = service.create(owner, "cafe", "A1").path("id").asText();
        assertThatThrownBy(() -> service.next(owner, failedId, 0)).isInstanceOf(ResponseStatusException.class);
        verify(ai, times(1)).completeJson(startsWith("You design"), anyString(), anyString());
    }

    @Test void wordCountIncludesItsBoundsAndDuplicateFactsCannotPassAsIndependentDetails() {
        for (int words : new int[]{20, 65}) {
            var valid = (tools.jackson.databind.node.ObjectNode) prompt("ten");
            valid.put("utteranceEn", "first second " + "word ".repeat(words - 2).trim());
            ((tools.jackson.databind.node.ObjectNode) valid.path("facts").path(0)).put("evidenceEn", "first");
            ((tools.jackson.databind.node.ObjectNode) valid.path("facts").path(1)).put("evidenceEn", "second");
            when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(valid);
            String id = service.create(owner, "cafe", "A1").path("id").asText();
            assertThat(service.next(owner, id, 0).path("turnNumber").asInt()).isEqualTo(1);
        }
        var duplicate = (tools.jackson.databind.node.ObjectNode) prompt("ten");
        ((tools.jackson.databind.node.ObjectNode) duplicate.path("facts").path(1)).put("textVi", "Quán ten");
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(duplicate);
        String invalidId = service.create(owner, "cafe", "A1").path("id").asText();
        assertThatThrownBy(() -> service.next(owner, invalidId, 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, invalidId).path("turnNumber").asInt()).isZero();
    }

    @Test void transferRejectsRepeatedFactsEvenWhenUtteranceIsReworded() {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenThrow(new IllegalStateException("prefetch offline"));
        for (int n = 1; n <= 3; n++) {
            service.answer(owner, id, n, UUID.randomUUID(), "quán");
            if (n < 3) {
                doReturn(prompt("time" + n)).when(ai).completeJson(startsWith("You design"), anyString(), anyString());
                service.next(owner, id, n);
                when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenThrow(new IllegalStateException("prefetch offline"));
            }
        }
        var repeated = (tools.jackson.databind.node.ObjectNode) prompt("ten");
        repeated.put("utteranceEn", "Hello there. " + repeated.path("utteranceEn").asText());
        doReturn(repeated).when(ai).completeJson(startsWith("You design"), anyString(), anyString());
        assertThatThrownBy(() -> service.next(owner, id, 3)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turnNumber").asInt()).isEqualTo(3);
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("four"));
        assertThat(service.next(owner, id, 3).path("turnNumber").asInt()).isEqualTo(4);
    }

    @Test void answerDoesNotCallGeneratorAndNextGenerationIncludesGradedHistory() {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenThrow(new IllegalStateException("API offline"));
        UUID request = UUID.randomUUID();
        var result = service.answer(owner, id, 1, request, "quán");
        assertThat(result.path("turns").path(0).path("done").asBoolean()).isTrue();
        assertThat(result.path("turns").path(0).path("attempts").path(0).has("evaluation")).isTrue();
        service.answer(owner, id, 1, request, "quán");
        verify(ai, times(1)).completeJson(contains("Grade ONLY meanings explicitly asked"), anyString());
        verify(ai, times(1)).completeJson(startsWith("You design"), anyString(), anyString());
        doReturn(prompt("eleven")).when(ai).completeJson(startsWith("You design"), anyString(), anyString());
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai, times(2)).completeJson(startsWith("You design"), input.capture(), eq("gemini-2.5-flash"));
        JsonNode history = json.readTree(input.getAllValues().get(1)).path("history").path(0);
        assertThat(history.path("facts").size()).isEqualTo(2);
        assertThat(history.path("evaluation").path("facts").path(0).path("status").asText()).isEqualTo("CAPTURED");
    }

    @Test void privacyOwnershipCsrfAndCachedPrivateAudio() throws Exception {
        String id = start();
        var principal = DictaLabPrincipal.from(users.findById(owner).orElseThrow());
        String path = "/api/v1/conversation/sessions/" + id;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user(principal))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.turns[0].prompt.utteranceEn").doesNotExist())
                .andExpect(jsonPath("$.turns[0].prompt.translationVi").doesNotExist())
                .andExpect(jsonPath("$.turns[0].prompt.facts").doesNotExist());
        mvc.perform(post(path + "/help").with(user(principal)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnNumber\":1,\"kind\":\"translation\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path + "/help").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnNumber\":1,\"kind\":\"translation\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].prompt.translationVi").exists())
                .andExpect(jsonPath("$.turns[0].prompt.utteranceEn").doesNotExist());
        long other = users.saveAndFlush(new AppUser("other-" + UUID.randomUUID() + "@test.local", "Other", "unused", Instant.now())).getId();
        mvc.perform(get(path).with(user(DictaLabPrincipal.from(users.findById(other).orElseThrow())))).andExpect(status().isNotFound());
        when(tts.synthesize(anyString())).thenReturn(new byte[]{1,2,3});
        service.play(owner, id, 1, "original", false); service.play(owner, id, 1, "original", true);
        verify(tts, times(1)).synthesize(anyString());
        assertThatThrownBy(() -> service.audio(other, id, 1, "original")).isInstanceOf(ResponseStatusException.class);
        mvc.perform(get(path + "/audio/1/original").with(user(principal))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().bytes(new byte[]{1,2,3}));
        db.update("DELETE FROM app_users WHERE id=?", owner);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
    }

    @Test void uncertaintyNeedsConfirmationAndParaphraseAdvancesExactlyOnce() {
        String id = start(); UUID uncertain = UUID.randomUUID();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("UNCERTAIN", "MISSED", ""));
        var incomplete = service.answer(owner, id, 1, uncertain, "Mình chưa chắc");
        assertThat(incomplete.path("turns").path(0).path("done").asBoolean()).isFalse();
        assertThat(incomplete.path("turns").path(0).path("attempts").path(0).path("evaluation").path("clarificationVi").asText()).isNotBlank();
        assertThatThrownBy(() -> service.next(owner, id, 1)).isInstanceOf(ResponseStatusException.class);
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán lúc mười giờ"));
        UUID captured = UUID.randomUUID();
        assertThat(service.answer(owner, id, 1, captured, "Đến quán lúc mười giờ nhé").path("turns").path(0).path("done").asBoolean()).isTrue();
        service.answer(owner, id, 1, captured, "Đến quán lúc mười giờ nhé");
        verify(ai, times(2)).completeJson(anyString(), anyString()); // Grading calls only; generation uses a model override.
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("eleven"));
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> service.answer(owner, id, 1, UUID.randomUUID(), "late answer")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void providerFailureKeepsSubmittedAnswerAndInvalidEvidenceCannotAwardCredit() {
        String id = start(); UUID request = UUID.randomUUID();
        when(ai.completeJson(anyString(), anyString())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.answer(owner, id, 1, request, "Mình nghe thấy quán")).isInstanceOf(ResponseStatusException.class);
        JsonNode stored = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        assertThat(stored.path("turns").path(0).path("attempts").path(0).path("answerVi").asText()).isEqualTo("Mình nghe thấy quán");
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        doReturn(grade("CAPTURED", "CAPTURED", "invented evidence")).when(ai).completeJson(anyString(), anyString());
        assertThatThrownBy(() -> service.answer(owner, id, 1, request, "Mình nghe thấy quán")).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turns").path(0).path("done").asBoolean()).isFalse();
        var missingConfirmation = (tools.jackson.databind.node.ObjectNode) grade("PARTIAL", "MISSED", "quán");
        missingConfirmation.put("clarificationVi", "");
        when(ai.completeJson(anyString(), anyString())).thenReturn(missingConfirmation);
        assertThatThrownBy(() -> service.answer(owner, id, 1, request, "Mình nghe thấy quán"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        assertThat(service.get(owner, id).path("turns").path(0).path("attempts").path(0).has("evaluation")).isFalse();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("PARTIAL", "MISSED", "quán"));
        service.answer(owner, id, 1, request, "Mình nghe thấy quán");
        assertThat(service.get(owner, id).path("turns").path(0).path("attempts").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"JSON_SHAPE", "TEXT", "CONFIRMATION", "FACT_COUNT", "FACT", "LEARNER_EVIDENCE", "CONFIRMATION_MISSING"})
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void gradingFailureLogsOnlyFixedRulesAndPreservesPendingAnswer(String rule,
            org.springframework.boot.test.system.CapturedOutput output) {
        String id = start(); UUID request = UUID.randomUUID();
        String answer = "PRIVATE_LEARNER_ANSWER_MARKER";
        var invalid = (tools.jackson.databind.node.ObjectNode) grade("PARTIAL", "MISSED", answer);
        switch (rule) {
            case "JSON_SHAPE" -> invalid.put("privateUnexpectedField", answer);
            case "TEXT" -> invalid.put("feedbackVi", answer.repeat(20));
            case "CONFIRMATION" -> invalid.put("clarificationVi", 42);
            case "FACT_COUNT" -> ((tools.jackson.databind.node.ArrayNode) invalid.path("facts")).remove(1);
            case "FACT" -> ((tools.jackson.databind.node.ObjectNode) invalid.path("facts").path(0)).put("status", answer);
            case "LEARNER_EVIDENCE" -> ((tools.jackson.databind.node.ObjectNode) invalid.path("facts").path(0)).put("evidenceVi", "PRIVATE_INVENTED_EVIDENCE_MARKER");
            case "CONFIRMATION_MISSING" -> invalid.put("clarificationVi", "");
            default -> throw new AssertionError("Unknown test rule");
        }
        when(ai.completeJson(anyString(), anyString())).thenReturn(invalid);
        assertThatThrownBy(() -> service.answer(owner, id, 1, request, answer))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        var stored = json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id));
        var attempts = stored.path("turns").path(0).path("attempts");
        assertThat(attempts.size()).isEqualTo(1);
        assertThat(attempts.path(0).path("answerVi").asText()).isEqualTo(answer);
        assertThat(attempts.path(0).has("evaluation")).isFalse();
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, id)).isNull();
        verify(ai, times(1)).completeJson(anyString(), anyString());
        assertThat(output.getOut()).contains("operation=grade", "validationRule=" + rule)
                .doesNotContain(answer, "PRIVATE_INVENTED_EVIDENCE_MARKER", "privateUnexpectedField");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void arbitraryProviderExceptionTextNeverBecomesAValidationRule(org.springframework.boot.test.system.CapturedOutput output) {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenThrow(new IllegalStateException("PRIVATE_PROVIDER_OUTPUT_MARKER"));
        assertThatThrownBy(() -> service.answer(owner, id, 1, UUID.randomUUID(), "fixture"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(output.getOut()).contains("validationRule=NONE").doesNotContain("PRIVATE_PROVIDER_OUTPUT_MARKER");
        verify(ai, times(1)).completeJson(anyString(), anyString());
    }

    @Test void fourTasksEndWithNewTransferAndSupportIsSeparated() {
        String id = start();
        for (int n = 1; n <= 4; n++) {
            if (n == 2) service.help(owner, id, n, "translation");
            when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
            var result = service.answer(owner, id, n, UUID.randomUUID(), "quán");
            if (n < 4) {
                assertThat(result.path("completed").asBoolean()).isFalse();
                when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("time" + n));
                service.next(owner, id, n);
            }
        }
        var result = service.get(owner, id);
        assertThat(result.path("completed").asBoolean()).isTrue();
        assertThat(result.path("summary").path("independentFacts").asInt()).isEqualTo(6);
        assertThat(result.path("summary").path("assistedFacts").asInt()).isEqualTo(2);
        assertThat(result.path("summary").path("nextActionVi").asText()).contains("trước khi mở hỗ trợ").doesNotContain("cao hơn");
        assertThat(result.path("summary").path("understoodVi").size()).isLessThanOrEqualTo(3);
        assertThat(result.path("summary").path("transferVi").asText()).contains("câu mới");
        assertThat(service.next(owner, id, 4).path("turnNumber").asInt()).isEqualTo(4);
        verify(ai, times(4)).completeJson(anyString(), anyString());
    }

    @Test void concurrentAnswerCannotOverwriteLeaseAndNoDbLockSpansAi() throws Exception {
        String id = start(); var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        UUID request = UUID.randomUUID();
        when(ai.completeJson(anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown(); if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return grade("CAPTURED", "CAPTURED", "quán");
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> service.answer(owner, id, 1, request, "quán"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(owner, id).path("turns").path(0).path("attempts").path(0).path("answerVi").asText()).isEqualTo("quán");
            assertThatThrownBy(() -> service.answer(owner, id, 1, request, "quán")).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.help(owner, id, 1, "transcript")).isInstanceOf(ResponseStatusException.class);
            finish.countDown(); assertThat(pending.get(10, TimeUnit.SECONDS).path("turns").path(0).path("done").asBoolean()).isTrue();
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void staleReaderCannotOverwriteAResultCommittedAfterItsRead() throws Exception {
        String id = start();
        var providerEntered = new CountDownLatch(1);
        var finishProvider = new CountDownLatch(1);
        var staleRead = new CountDownLatch(1);
        var resultCommitted = new CountDownLatch(1);
        when(ai.completeJson(anyString(), anyString())).thenAnswer(invocation -> {
            providerEntered.countDown();
            if (!finishProvider.await(10, TimeUnit.SECONDS)) throw new AssertionError("Provider timed out");
            return grade("CAPTURED", "CAPTURED", "quán");
        });
        JdbcTemplate staleDb = spy(db);
        doAnswer(invocation -> {
            Object snapshot = invocation.callRealMethod();
            staleRead.countDown();
            if (!resultCommitted.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit timed out");
            return snapshot;
        }).when(staleDb).query(eq("SELECT * FROM ai_conversation_sessions WHERE id=? AND user_id=?"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(id), eq(owner));
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var staleService = new ConversationService(staleDb, json, ai, tts, config, manager, metrics,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.dictalab.service.AiBackgroundJobs.class),budget);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var answer = executor.submit(() -> service.answer(owner, id, 1, UUID.randomUUID(), "quán"));
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var staleHelp = executor.submit(() -> staleService.help(owner, id, 1, "transcript"));
            assertThat(staleRead.await(10, TimeUnit.SECONDS)).isTrue();
            finishProvider.countDown();
            assertThat(answer.get(10, TimeUnit.SECONDS).path("turns").path(0).path("done").asBoolean()).isTrue();
            String committed = db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id);
            resultCommitted.countDown();
            assertThatThrownBy(() -> staleHelp.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(409));
            assertThat(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?", String.class, id))
                    .isEqualTo(committed);
            verify(ai, times(1)).completeJson(anyString(), anyString());
        } finally {
            finishProvider.countDown(); resultCommitted.countDown(); executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(staleService, "stopAudioWorkers");
            metrics.close();
        }
    }

    @EnabledIfSystemProperty(named="dictalab.race-tests",matches="true")
    @ParameterizedTest @ValueSource(ints={2,5,10,20})
    void requestIdMatrixAndLostResponseRetry(int n) throws Exception {
        try(var connection=db.getDataSource().getConnection()){
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(db.queryForObject("SELECT current_database()",String.class)).matches("dictalab_test(?:_[a-f0-9]{32})?");
        int repeats=Integer.getInteger("dictalab.race-repeats",2);
        assertThat(repeats).isBetween(1,5);
        for(int repeat=0;repeat<repeats;repeat++){
            String id=start();UUID request=UUID.randomUUID();
            var barrier=new CyclicBarrier(n);var entered=new CountDownLatch(1);
            var rejected=new CountDownLatch(n-1);var release=new CountDownLatch(1);
            clearInvocations(ai);
            when(ai.completeJson(anyString(),anyString())).thenAnswer(invocation->{
                entered.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();
                return grade("CAPTURED","CAPTURED","fixture");
            });
            var pool=Executors.newFixedThreadPool(n);
            try{
                var futures=new java.util.ArrayList<Future<Boolean>>();
                for(int i=0;i<n;i++)futures.add(pool.submit(()->{
                    barrier.await(10,TimeUnit.SECONDS);
                    try{service.answer(owner,id,1,request,"fixture");return true;}
                    catch(ResponseStatusException busy){
                        assertThat(busy.getStatusCode().value()).isEqualTo(409);rejected.countDown();return false;
                    }
                }));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(rejected.await(10,TimeUnit.SECONDS)).as("All losers reached the live lease boundary").isTrue();
                verify(ai,times(1)).completeJson(anyString(),anyString());
                release.countDown();int winners=0;
                for(var future:futures)if(future.get(15,TimeUnit.SECONDS))winners++;
                assertThat(winners).isEqualTo(1);
                var committed=service.answer(owner,id,1,request,"fixture"); // The first response is deliberately discarded.
                var retries=new java.util.ArrayList<Future<JsonNode>>();
                for(int i=0;i<n;i++)retries.add(pool.submit(()->service.answer(owner,id,1,request,"fixture")));
                for(var retry:retries)assertThat(retry.get(15,TimeUnit.SECONDS)).isEqualTo(committed);
                verify(ai,times(1)).completeJson(anyString(),anyString());
                var stored=json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",String.class,id));
                assertThat(stored.path("turns").path(0).path("attempts").size()).isEqualTo(1);
                assertThatThrownBy(()->service.answer(owner,id,1,request,"changed payload"))
                        .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
                assertThatThrownBy(()->service.answer(Long.MAX_VALUE,id,1,request,"fixture"))
                        .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
            }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
        }
    }

    @Test void expiredGenerationCannotCommitEvenBeforeAReplacementClaims() {
        String id=service.create(owner,"cafe","A1").path("id").asText();
        when(ai.completeJson(startsWith("You design"),anyString(),anyString())).thenAnswer(call->{
            db.update("UPDATE ai_conversation_sessions SET busy_until=? WHERE id=?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),id);
            return prompt("ten");
        });
        assertThatThrownBy(()->service.next(owner,id,0)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure->assertThat(failure.getStatusCode().value()).isEqualTo(409));
        assertThat(service.get(owner,id).path("turnNumber").asInt()).isZero();
    }

    @Test void committedAnswerReplaysAfterServiceRecreationWithoutCallingAiAgain() {
        String id=start();UUID request=UUID.randomUUID();
        when(ai.completeJson(anyString(),anyString())).thenReturn(grade("CAPTURED","CAPTURED","quán"));
        JsonNode original=service.answer(owner,id,1,request,"quán");
        var isolatedMetrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var replacement=new ConversationService(db,json,ai,tts,config,manager,isolatedMetrics,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.dictalab.service.AiBackgroundJobs.class),budget);
        clearInvocations(ai);
        try{
            assertThat(replacement.answer(owner,id,1,request,"quán")).isEqualTo(original);
            verify(ai,never()).completeJson(anyString(),anyString());
            verify(ai,never()).completeJson(anyString(),anyString(),anyString());
            assertThatThrownBy(()->replacement.answer(owner,id,1,request,"different body"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,failure->assertThat(failure.getStatusCode().value()).isEqualTo(409));
            assertThat(replacement.get(owner,id).path("turns").path(0).path("attempts").size()).isEqualTo(1);
        }finally{org.springframework.test.util.ReflectionTestUtils.invokeMethod(replacement,"stopAudioWorkers");isolatedMetrics.close();}
    }

    @Test void newTransferCannotRepeatOriginalAndExtraJsonFieldsAreRejected() {
        var invalid = (tools.jackson.databind.node.ObjectNode) prompt("ten"); invalid.put("answerKey", "leak");
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(invalid);
        String id = service.create(owner, "tickets", "B1").path("id").asText();
        assertThatThrownBy(() -> service.next(owner, id, 0)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turnNumber").asInt()).isZero();
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("ten")); service.next(owner, id, 0);
        for (int n=1; n<=3; n++) {
            when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
            service.answer(owner, id, n, UUID.randomUUID(), "quán");
            when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt(n < 3 ? "time" + n : "ten"));
            if (n<3) service.next(owner, id, n);
        }
        assertThatThrownBy(() -> service.next(owner, id, 3)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turnNumber").asInt()).isEqualTo(3);
    }

    @Test void nextGeneratesReviewedTaskOnceAndCachedAudioIsReused() throws Exception {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("eleven"));
        UUID request = UUID.randomUUID();
        var result = service.answer(owner, id, 1, request, "quán");
        assertThat(result.has("nextPrompt")).isFalse();
        assertThat(result.path("turnNumber").asInt()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("eleven", "session_id");
        assertThat(json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",
                String.class, id)).has("nextPrompt")).isFalse();
        service.answer(owner, id, 1, request, "quán");
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
        service.next(owner, id, 1);
        verify(ai, times(1)).completeJson(anyString(), anyString());
        verify(ai, times(2)).completeJson(startsWith("You design"), anyString(), eq("gemini-2.5-flash"));
        verify(ai, times(2)).completeJson(startsWith("Audit a listening task"), anyString(), eq("gemini-2.5-flash"));
        service.play(owner, id, 2, "original", false);
        verify(tts, times(1)).synthesize(contains("eleven"));
    }

    @Test void failedNextGenerationKeepsCommittedGradeAndCanRetry() {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("CAPTURED", "CAPTURED", "quán"));
        assertThat(service.answer(owner, id, 1, UUID.randomUUID(), "quán").path("turns").path(0).path("done").asBoolean()).isTrue();
        assertThat(json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",
                String.class, id)).has("nextPrompt")).isFalse();
        assertThatThrownBy(() -> service.next(owner, id, 1)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, id).path("turns").path(0).path("attempts").path(0).has("evaluation")).isTrue();
        when(ai.completeJson(startsWith("You design"), anyString(), anyString())).thenReturn(prompt("eleven"));
        assertThat(service.next(owner, id, 1).path("turnNumber").asInt()).isEqualTo(2);
    }

    @Test void prematureNextTaskCannotAdvanceAnUnfinishedTurn() {
        String id = start();
        when(ai.completeJson(anyString(), anyString())).thenReturn(grade("UNCERTAIN", "MISSED", ""));
        service.answer(owner, id, 1, UUID.randomUUID(), "Chưa rõ");
        assertThatThrownBy(() -> service.next(owner, id, 1)).isInstanceOf(ResponseStatusException.class);
        assertThat(json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",
                String.class, id)).has("nextPrompt")).isFalse();
        verify(ai, times(1)).completeJson(startsWith("You design"), anyString(), anyString()); // Only the initial task.
    }

    @Test void playbackJoinsBackgroundAudioAndFailureCanRetry() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(tts.synthesize(anyString())).thenAnswer(invocation -> {
            entered.countDown(); if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return new byte[]{1,2,3};
        });
        String id = start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); // TTS starts before clicking Play.
        var executor = Executors.newSingleThreadExecutor();
        try {
            var playing = executor.submit(() -> service.play(owner, id, 1, "original", false));
            finish.countDown(); playing.get(10, TimeUnit.SECONDS);
            service.play(owner, id, 1, "original", true);
            verify(tts, times(1)).synthesize(anyString());
        } finally { finish.countDown(); executor.shutdownNow(); }

        when(tts.synthesize(anyString())).thenThrow(new IllegalStateException("offline"));
        String retryId = start();
        assertThatThrownBy(() -> service.play(owner, retryId, 1, "original", false)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.get(owner, retryId).path("turnNumber").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?", String.class, retryId)).isNull();
        doReturn(new byte[]{4,5,6}).when(tts).synthesize(anyString());
        service.play(owner, retryId, 1, "original", false);
        assertThat(service.audio(owner, retryId, 1, "original")).containsExactly(4,5,6);
    }

    @Test void slowAudioLookupDoesNotBlockAnotherSessionPreparingAudio() throws Exception {
        JdbcTemplate delayedDb = spy(db);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var isolated = new ConversationService(delayedDb, json, ai, tts, config, manager, metrics,
                io.micrometer.observation.ObservationRegistry.NOOP,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.dictalab.service.AiBackgroundJobs.class), budget);
        String first = isolated.create(owner, "cafe", "A1").path("id").asText();
        String second = isolated.create(owner, "cafe", "A1").path("id").asText();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(delayedDb).queryForObject(
                eq("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=? AND turn_number=? AND variant=?"),
                eq(Integer.class), eq(first), eq(1), eq("original"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var blocked = executor.submit(() -> isolated.next(owner, first, 0));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var unrelated = executor.submit(() -> isolated.next(owner, second, 0));
            assertThat(unrelated.get(5, TimeUnit.SECONDS).path("turnNumber").asInt()).isEqualTo(1);
            assertThat(blocked.isDone()).isFalse();
            release.countDown(); blocked.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown(); executor.shutdownNow();
            var stop = ConversationService.class.getDeclaredMethod("stopAudioWorkers");
            stop.setAccessible(true); stop.invoke(isolated); metrics.close();
        }
    }

    @Test void deletedAccountCannotReceiveLatePrefetchedAudio() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var returned = new CountDownLatch(1);
        when(tts.synthesize(anyString())).thenAnswer(invocation -> {
            entered.countDown(); if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            returned.countDown(); return new byte[]{1,2,3};
        });
        String id = start();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            db.update("DELETE FROM app_users WHERE id=?", owner);
            finish.countDown(); assertThat(returned.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_conversation_audio WHERE session_id=?", Integer.class, id)).isZero();
        } finally { finish.countDown(); }
    }
}
