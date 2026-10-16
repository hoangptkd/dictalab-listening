package com.dictalab.web;

import com.dictalab.domain.AppUser;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"dictalab.conversation.enabled=true", "dictalab.coach.enabled=false"})
class ConversationUxIntegrationTest {
    @Autowired ConversationService service;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean ShopAiKeyClient ai;
    @MockitoBean CartesiaClient tts;
    long owner;
    long user() { return users.saveAndFlush(new AppUser("ux-" + UUID.randomUUID() + "@test.local", "Learner", "unused", Instant.now())).getId(); }
    @BeforeEach void setup() {
        owner=user(); when(ai.configured()).thenReturn(true); when(tts.enabled()).thenReturn(true);
        when(tts.synthesize(anyString())).thenReturn(new byte[]{1,2,3});
    }
    @Test void allCuratedOpeningsAreGroundedAndNeedNoLanguageModel() {
        for (String scenario : ConversationService.SCENARIOS.keySet()) for (String level : List.of("A1","A2","B1")) {
            long learner=user();
            String id=service.create(learner,scenario,level).path("id").asText();
            var result=service.next(learner,id,0);
            assertThat(result.path("turnNumber").asInt()).isEqualTo(1);
            assertThat(result.path("turns").path(0).path("warmup").asBoolean()).isTrue();
            assertThat(result.toString()).doesNotContain("evidenceEn", "translationVi", "utteranceEn");
            var review=service.help(learner,id,1,"review").path("turns").path(0).path("review");
            String english=review.path("utteranceEn").asText();
            assertThat(english.split("\\s+").length).isBetween(8,55);
            review.path("facts").forEach(f -> assertThat(english).contains(f.path("evidenceEn").asText()));
        }
        verify(ai,never()).completeJson(anyString(),anyString(),anyString());
        verify(ai,never()).completeJson(anyString(),anyString(),anyString(),any());
    }
    @Test void catalogHasSixGroupsAndHistoryIsPrivateAndPaginated() {
        for(int i=0;i<13;i++) service.create(owner,"cafe","A1");
        service.create(user(),"plans","B1");
        var catalog=json.valueToTree(service.catalog(owner));
        assertThat(catalog.path("scenarioCatalog").size()).isEqualTo(24);
        assertThat(catalog.path("scenarioGroups").size()).isEqualTo(6);
        assertThat(catalog.path("sessions").size()).isEqualTo(12);
        assertThat(catalog.path("hasMore").asBoolean()).isTrue();
        var page=json.valueToTree(service.history(owner,12,12));
        assertThat(page.path("sessions").size()).isEqualTo(1);
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.toString()).doesNotContain("state_json", "user_id", "plans");
        assertThatThrownBy(() -> service.history(owner,-1,12)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void reportDoesNotChangeGradeAndCrossContextPracticeRetainsLevel() {
        String id=service.create(owner,"cafe","A1").path("id").asText(); service.next(owner,id,0);
        when(ai.completeJson(anyString(),anyString())).thenReturn(json.readTree("""
                {"facts":[{"factId":"f1","status":"CAPTURED","evidenceVi":"trà"},
                {"factId":"f2","status":"CAPTURED","evidenceVi":"bánh"}],
                "feedbackVi":"Bạn đã nắm hai ý.","clarificationVi":""}
                """));
        service.answer(owner,id,1,UUID.randomUUID(),"trà và bánh");
        assertThat(service.report(owner,id,1,"Tôi muốn kiểm tra nhận xét.")).containsEntry("reported",true);
        assertThat(service.get(owner,id).path("turns").path(0).path("capturedFacts").asInt()).isEqualTo(2);
        assertThatThrownBy(() -> service.report(user(),id,1,"Kiểm tra")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        var state=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",String.class,id));
        state.put("completed",true); db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?",state.toString(),id);
        var followup=service.create(owner,"plans","A1",id);
        assertThat(followup.path("practiceFocus").asText()).isNotBlank();
        assertThatThrownBy(() -> service.create(owner,"plans","A2",id)).isInstanceOf(ResponseStatusException.class);
        var summary=service.get(owner,id).path("summary");
        assertThat(summary.path("firstAttemptFacts").asInt()).isZero();
        assertThat(summary.path("warmupFacts").asInt()).isEqualTo(2);
        service.play(owner,id,1,"original",false); service.play(owner,id,1,"original",false);
        assertThat(service.get(owner,id).path("summary").path("firstAttemptFacts").asInt()).isZero();
    }
    @Test void generatedFollowupUsesStableSkillsAndOneSharedDeadline() {
        String id=service.create(owner,"cafe","A1").path("id").asText(); service.next(owner,id,0);
        service.help(owner,id,1,"review");
        when(ai.completeJson(startsWith("You design"),anyString(),anyString(),any())).thenReturn(json.readTree("""
                {"taskVi":"Nghe nơi gặp và giờ hẹn.","questionVi":"Gặp ở đâu và lúc mấy giờ?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"Please meet me at the park at six tomorrow. Bring your notebook so we can plan the next activity.",
                "translationVi":"Gặp tôi ở công viên lúc sáu giờ ngày mai. Mang sổ để lên kế hoạch hoạt động tiếp theo.",
                "facts":[{"id":"f1","textVi":"Công viên","evidenceEn":"at the park"},
                {"id":"f2","textVi":"Sáu giờ","evidenceEn":"at six"}]}
                """));
        when(ai.completeJson(startsWith("Audit a listening task"),anyString(),anyString(),any()))
                .thenReturn(json.readTree("{\"valid\":true,\"reason\":\"\"}"));
        var result=service.next(owner,id,1);
        assertThat(result.path("turns").path(1).path("skillId").asText()).isEqualTo("details");
        var deadline=org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(ai,times(2)).completeJson(anyString(),anyString(),anyString(),deadline.capture());
        assertThat(deadline.getAllValues()).allSatisfy(d -> {
            assertThat(d).isPositive(); assertThat(d).isLessThanOrEqualTo(java.time.Duration.ofSeconds(45));
        });
        assertThat(deadline.getAllValues().get(1)).isLessThan(deadline.getAllValues().get(0));
        service.play(owner,id,2,"original",false); service.play(owner,id,2,"original",false);
        when(ai.completeJson(anyString(),anyString())).thenReturn(json.readTree("""
                {"facts":[{"factId":"f1","status":"CAPTURED","evidenceVi":"công viên"},
                {"factId":"f2","status":"CAPTURED","evidenceVi":"sáu giờ"}],
                "feedbackVi":"Bạn đã nắm hai ý.","clarificationVi":""}
                """));
        service.answer(owner,id,2,UUID.randomUUID(),"công viên lúc sáu giờ");
        var state=(tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject("SELECT state_json FROM ai_conversation_sessions WHERE id=?",String.class,id));
        state.put("completed",true); db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?",state.toString(),id);
        assertThat(service.get(owner,id).path("summary").path("replayedFacts").asInt()).isEqualTo(2);
        service.play(owner,id,2,"original",false);
        assertThat(service.get(owner,id).path("summary").path("replayedFacts").asInt()).isEqualTo(2);
        var progress=json.valueToTree(service.catalog(owner)).path("progress");
        assertThat(progress.path("totalFacts").asInt()).isEqualTo(2);
        assertThat(progress.path("firstAttemptFacts").asInt()).isZero();
    }
}
