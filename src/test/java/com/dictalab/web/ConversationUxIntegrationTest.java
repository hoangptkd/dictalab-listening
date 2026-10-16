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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    @MockitoSpyBean AiUsageBudget budget;
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
    tools.jackson.databind.node.ObjectNode state(String id) {
        return (tools.jackson.databind.node.ObjectNode)json.readTree(db.queryForObject(
                "SELECT state_json FROM ai_conversation_sessions WHERE id=?",String.class,id));
    }
    @Test void openingsRotateBeforeRecyclingAndReloadNeverRerolls() {
        var branches=new ArrayList<String>();
        var openings=new HashSet<String>();
        for(int i=0;i<9;i++) {
            String id=service.create(owner,"hotel","A2").path("id").asText();
            var saved=state(id);
            String branch=saved.path("sessionBlueprint").path("branchId").asText();
            branches.add(branch);
            if(i<8) assertThat(openings.add(saved.path("openingPrompt").path("utteranceEn").asText())).isTrue();
            var first=service.next(owner,id,0);
            assertThat(service.next(owner,id,0)).isEqualTo(first);
            assertThat(state(id).path("sessionBlueprint")).isEqualTo(saved.path("sessionBlueprint"));
            assertThat(state(id).path("turns").path(0).path("prompt")).isEqualTo(saved.path("openingPrompt"));
            assertThat(service.get(owner,id).toString()).doesNotContain("sessionBlueprint","recentPractice","openingPrompt");
        }
        assertThat(branches.subList(0,8)).doesNotHaveDuplicates();
        assertThat(branches.get(8)).isEqualTo(branches.get(0));
        String other=service.create(user(),"hotel","A2").path("id").asText();
        assertThat(state(other).path("recentPractice")).isEmpty();
        assertThat(state(other).path("sessionBlueprint").path("branchId").asText()).isEqualTo("standard");
        String differentLevel=service.create(owner,"hotel","B1").path("id").asText();
        assertThat(state(differentLevel).path("recentPractice")).isEmpty();
    }
    @Test void concurrentStartsReserveDistinctBranches() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        var ready=new java.util.concurrent.CountDownLatch(4);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<4;i++) futures.add(executor.submit(() -> {
                ready.countDown(); start.await();
                return service.create(owner,"directions","A1").path("id").asText();
            }));
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            var branches=new HashSet<String>();
            for(var future:futures) assertThat(branches.add(state(future.get(20,java.util.concurrent.TimeUnit.SECONDS))
                    .path("sessionBlueprint").path("branchId").asText())).isTrue();
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void recentTasksArePrivateBoundedAndRejectedWithinExistingRepairDeadline() {
        String old=service.create(owner,"cafe","A1").path("id").asText(); service.next(owner,old,0);
        var previous=state(old);
        ((tools.jackson.databind.node.ArrayNode)previous.path("turns").path(0).path("attempts"))
                .addObject().put("answerVi","PRIVATE_LEARNER_REPLY");
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?",previous.toString(),old);
        service.create(user(),"cafe","A1");
        String id=service.create(owner,"cafe","A1").path("id").asText(); service.next(owner,id,0);
        service.help(owner,id,1,"review");
        var fresh=json.readTree("""
                {"taskVi":"Nghe cách đóng gói và nơi lấy hàng.","questionVi":"Đồ ăn được đựng bằng gì và lấy ở đâu?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"We will pack your salad in a paper box. Please collect it at the counter.",
                "translationVi":"Chúng tôi sẽ đựng salad trong hộp giấy. Xin lấy tại quầy.",
                "facts":[{"id":"f1","textVi":"Hộp giấy","evidenceEn":"a paper box"},
                {"id":"f2","textVi":"Tại quầy","evidenceEn":"at the counter"}]}
                """);
        when(ai.completeJson(startsWith("You design"),anyString(),anyString(),any()))
                .thenReturn(previous.path("turns").path(0).path("prompt"),fresh);
        when(ai.completeJson(startsWith("Audit a listening task"),anyString(),anyString(),any()))
                .thenReturn(json.readTree("{\"valid\":true,\"reason\":\"\"}"));
        service.next(owner,id,1);
        var inputs=org.mockito.ArgumentCaptor.forClass(String.class);
        var deadlines=org.mockito.ArgumentCaptor.forClass(java.time.Duration.class);
        verify(ai,times(3)).completeJson(anyString(),inputs.capture(),anyString(),deadlines.capture());
        var context=json.readTree(inputs.getAllValues().get(0));
        assertThat(context.path("recentPractice").size()).isEqualTo(1);
        assertThat(context.path("recentPractice").path(0).path("tasks").path(0).path("answersVi").size()).isEqualTo(2);
        assertThat(inputs.getAllValues()).allSatisfy(input -> assertThat(input).doesNotContain("PRIVATE_LEARNER_REPLY"));
        assertThat(state(id).path("recentPractice").toString()).doesNotContain("PRIVATE_LEARNER_REPLY");
        assertThat(json.readTree(inputs.getAllValues().get(1)).path("validationError").asText()).isEqualTo("Repeated recent task");
        assertThat(deadlines.getAllValues()).allSatisfy(d -> assertThat(d).isPositive().isLessThanOrEqualTo(java.time.Duration.ofSeconds(45)));
        for(int i=0;i<10;i++) service.create(owner,"cafe","A1");
        String latest=service.create(owner,"cafe","A1").path("id").asText();
        assertThat(state(latest).path("recentPractice").size()).isEqualTo(10);
    }
    @Test void legacySavedSessionWithoutBlueprintKeepsItsOriginalOpening() {
        String id=service.create(owner,"hotel","A2").path("id").asText();
        var saved=state(id); saved.remove("sessionBlueprint"); saved.remove("openingPrompt"); saved.remove("recentPractice");
        db.update("UPDATE ai_conversation_sessions SET state_json=? WHERE id=?",saved.toString(),id);
        service.next(owner,id,0);
        assertThat(state(id).path("turns").path(0).path("prompt").path("utteranceEn").asText()).contains("number eight","in the garden");
    }
    @Test void disablingDiversityAffectsOnlyNewSessions() {
        String existing=service.create(owner,"hotel","A2").path("id").asText();
        var saved=state(existing);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"sessionDiversity",false);
        try {
            String legacy=service.create(owner,"hotel","A2").path("id").asText();
            assertThat(state(legacy).has("sessionBlueprint")).isFalse();
            service.next(owner,existing,0);
            assertThat(state(existing).path("sessionBlueprint")).isEqualTo(saved.path("sessionBlueprint"));
            assertThat(state(existing).path("turns").path(0).path("prompt")).isEqualTo(saved.path("openingPrompt"));
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(service,"sessionDiversity",true); }
    }
    @Test void duplicateRulesRejectNearCopiesAndRepeatedAnswerSetsButAllowOneSharedDetail() {
        var previous=(tools.jackson.databind.node.ObjectNode)json.readTree("""
                {"utteranceEn":"Please collect the parcel at the front desk tomorrow before lunch and bring your ticket with you.",
                "facts":[{"textVi":"Quầy lễ tân","evidenceEn":"front desk"},
                {"textVi":"Trước bữa trưa","evidenceEn":"before lunch"}]}
                """);
        var near=previous.deepCopy();
        near.put("utteranceEn",previous.path("utteranceEn").asText().replace("tomorrow","today"));
        near.withArray("facts").removeAll().addObject().put("textVi","Hôm nay").put("evidenceEn","today");
        assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"repeatedTask",previous,near)).isTrue();
        var paraphrase=previous.deepCopy().put("utteranceEn","You can pick it up from reception ahead of your midday meal.");
        assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"repeatedTask",previous,paraphrase)).isTrue();
        var fresh=previous.deepCopy().put("utteranceEn","Leave your key at the front desk. The airport bus leaves at five.");
        ((tools.jackson.databind.node.ObjectNode)fresh.path("facts").path(1)).put("textVi","Năm giờ").put("evidenceEn","at five");
        assertThat((Boolean)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"repeatedTask",previous,fresh)).isFalse();
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
    @Test void exhaustedBudgetExplainsWhyAndPreservesTheSessionAndPendingAnswer() {
        String message="Đã đạt giới hạn AI trong ngày. Bạn vẫn có thể luyện dictation.";
        String id=service.create(owner,"hotel","A2").path("id").asText(); service.next(owner,id,0);
        service.help(owner,id,1,"review");
        doThrow(new AiUsageBudget.Rejected(message)).when(budget).reserve(owner,false);
        assertThatThrownBy(() -> service.next(owner,id,1)).isInstanceOfSatisfying(AiUsageBudget.Rejected.class,
                e -> assertThat(e.getMessage()).isEqualTo(message));
        assertThat(service.get(owner,id).path("turnNumber").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT lease_id FROM ai_conversation_sessions WHERE id=?",String.class,id)).isNull();
        String pending=service.create(owner,"hotel","A2").path("id").asText(); service.next(owner,pending,0);
        assertThatThrownBy(() -> service.answer(owner,pending,1,UUID.randomUUID(),"Phòng tám"))
                .isInstanceOf(AiUsageBudget.Rejected.class);
        assertThat(service.get(owner,pending).path("turns").path(0).path("attempts").path(0).path("answerVi").asText())
                .isEqualTo("Phòng tám");
        assertThatThrownBy(() -> service.help(owner,pending,1,"clarify")).isInstanceOf(AiUsageBudget.Rejected.class);
        verify(ai,never()).completeJson(anyString(),anyString());
        verify(ai,never()).completeJson(anyString(),anyString(),anyString(),any());
    }
    @Test void followupRepairsContradictionsWithoutLosingEstablishedContext() {
        String id=service.create(owner,"hotel","A2").path("id").asText(); service.next(owner,id,0);
        service.help(owner,id,1,"review");
        var contradictory=json.readTree("""
                {"taskVi":"Nghe giờ và nơi ăn sáng.","questionVi":"Ăn sáng từ mấy giờ và ở đâu?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"Breakfast is ready from seven in the morning. You can eat it on the sunny balcony upstairs today.",
                "translationVi":"Bữa sáng bắt đầu lúc bảy giờ sáng. Hôm nay bạn có thể ăn ở ban công trên lầu.",
                "facts":[{"id":"f1","textVi":"Bảy giờ sáng","evidenceEn":"seven in the morning"},
                {"id":"f2","textVi":"Ban công trên lầu","evidenceEn":"balcony upstairs"}]}
                """);
        var repaired=json.readTree("""
                {"taskVi":"Nghe giờ ăn sáng và nơi trả chìa khóa.","questionVi":"Bữa sáng bắt đầu lúc mấy giờ và trả chìa khóa ở đâu?",
                "skill":"Nắm thông tin cụ thể","utteranceEn":"Breakfast in the garden starts at seven in the morning. When you leave, please return your key at the front desk.",
                "translationVi":"Bữa sáng ngoài vườn bắt đầu lúc bảy giờ sáng. Khi rời đi, xin trả chìa khóa tại quầy lễ tân.",
                "facts":[{"id":"f1","textVi":"Bảy giờ sáng","evidenceEn":"seven in the morning"},
                {"id":"f2","textVi":"Quầy lễ tân","evidenceEn":"at the front desk"}]}
                """);
        when(ai.completeJson(startsWith("You design"),anyString(),anyString(),any())).thenReturn(contradictory,repaired);
        when(ai.completeJson(startsWith("Audit a listening task"),anyString(),anyString(),any()))
                .thenReturn(json.readTree("{\"valid\":false,\"reason\":\"Breakfast moves from garden to balcony without an announced change.\"}"),
                        json.readTree("{\"valid\":true,\"reason\":\"\"}"));
        var result=service.next(owner,id,1);
        assertThat(result.path("turnNumber").asInt()).isEqualTo(2);
        var instruction=org.mockito.ArgumentCaptor.forClass(String.class);
        var inputs=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ai,times(4)).completeJson(instruction.capture(),inputs.capture(),anyString(),any());
        assertThat(instruction.getAllValues().get(0)).contains("Preserve established arrangements in history",
                "must explicitly announce the change", "Previously established");
        var first=json.readTree(inputs.getAllValues().get(0));
        var retry=json.readTree(inputs.getAllValues().get(2));
        assertThat(first.path("history").path(0).path("utteranceEn").asText()).contains("in the garden");
        assertThat(retry.path("history")).isEqualTo(first.path("history"));
        assertThat(retry.path("previousInvalidPrompt")).isEqualTo(contradictory);
        assertThat(retry.path("validationError").asText()).contains("without an announced change");
        assertThat(service.help(owner,id,2,"review").path("turns").path(1).path("review").path("utteranceEn"))
                .isEqualTo(repaired.path("utteranceEn"));
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
