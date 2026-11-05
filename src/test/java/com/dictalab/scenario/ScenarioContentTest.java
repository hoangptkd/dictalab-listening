package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioContentTest {
    @Test void voiceRecipeRequiresTwoDistinctConfiguredVoicesAndKeepsLegacyDefaultDisabled() {
        var legacy=new ScenarioEngineProperties(true,1,"","",false,java.util.Set.of());
        assertThat(legacy.ttsRecipe()).isNull();
        String recipe="{\"id\":\"pair-v1\",\"model\":\"sonic-3.6\",\"apiVersion\":\"2026-08-14\",\"language\":\"en\",\"outputFormat\":\"wav-pcm16-mono-24000\",\"voices\":[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000002\"]}";
        assertThat(new ScenarioEngineProperties(true,1,"","",false,java.util.Set.of(),java.util.Map.of(),recipe).ttsRecipe().path("id").asText()).isEqualTo("pair-v1");
        for (String invalid : java.util.List.of(recipe.replace("000000000002","000000000001"),recipe.replace("\"en\"","\"vi\""),recipe.replace("sonic-3.6","invalid model")))
            assertThatThrownBy(() -> new ScenarioEngineProperties(true,1,"","",false,java.util.Set.of(),java.util.Map.of(),invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explorerReusesAllLegacySuggestionsInSixGroups() {
        var suggestions=com.dictalab.service.ConversationService.scenarioSuggestions();
        assertThat(suggestions).hasSize(24);
        assertThat(suggestions.stream().map(s -> s.get("id")).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(com.dictalab.service.ConversationService.SCENARIOS.keySet());
        assertThat(suggestions.stream().map(s -> s.get("groupId")).distinct()).hasSize(6);
    }
    JsonNode fixture() throws Exception {
        try (var in=getClass().getResourceAsStream("/scenario/workflow-example.json")) { return ScenarioJson.MAPPER.readTree(in.readAllBytes()); }
    }
    @Test void sharedFixturePassesIndependentCoreValidation() throws Exception {
        var f=fixture(); ScenarioContent.specification(f.path("specification")); ScenarioContent.content(f.path("content"),f.path("specification"),f.path("profile"));
        ScenarioContent.grade(f.path("grade"),f.path("answers"));
    }
    @Test void rejectsForgedEvidencePrivateFieldsAndOutOfBoundsContent() throws Exception {
        for (String fault : java.util.List.of("quote","translation","words","unicodeWords","quality","extra","oneSpeaker","segmentCount")) {
            var f=fixture(); var content=f.path("content");
            switch (fault) {
                case "quote" -> ((ObjectNode)content.path("activities").get(0).path("questions").get(0).path("evidence").get(0)).put("quoteEn","invented");
                case "translation" -> ((ObjectNode)content.path("translations").get(0)).put("segmentId","missing");
                case "words" -> ((ObjectNode)content.path("activities").get(0).path("segments").get(0)).put("textEn","word ".repeat(70));
                case "unicodeWords" -> ((ObjectNode)content.path("activities").get(0).path("segments").get(0)).put("textEn","word ".repeat(14)+"word\u00a0".repeat(70));
                case "quality" -> ((ObjectNode)content).put("qualityPassed",false);
                case "extra" -> ((ObjectNode)content.path("activities").get(0)).put("publicUrl","https://untrusted.test");
                case "oneSpeaker" -> ((ObjectNode)content.path("activities").get(0).path("segments").get(1)).put("speakerId","customer");
                case "segmentCount" -> ((tools.jackson.databind.node.ArrayNode)content.path("activities").get(0).path("segments")).remove(1);
            }
            assertThatThrownBy(() -> ScenarioContent.content(content,f.path("specification"),f.path("profile"))).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsInventedLearnerEvidenceInsteadOfAwardingCredit() throws Exception {
        var f=fixture(); ((ObjectNode)f.path("grade").path("facts").get(0)).put("evidenceVi","This was not submitted");
        assertThatThrownBy(() -> ScenarioContent.grade(f.path("grade"),f.path("answers"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void draftProgramsCannotBeEnabledBySettingFlagsAndHashesAlone() throws Exception {
        var registry=new ScenarioProgramRegistry(); var ai=mock(com.dictalab.service.AiPlatformClient.class); when(ai.textConfigured()).thenReturn(true);
        String hash; try (var in=getClass().getResourceAsStream("/scenario/workflow-v1.sha256")) { hash=new String(in.readAllBytes()).trim(); }
        var release=new ScenarioRelease(new ScenarioProperties(true),new ScenarioEngineProperties(true,1,registry.require("general-communication",1).definitionHash(),hash,false,java.util.Set.of()),registry,ai);
        assertThat(release.available()).isFalse();
        assertThatThrownBy(release::require).isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.status).isEqualTo(503));
    }
    @Test void normalizesUnicodeAndRedactsContactDataBeforeAiAdmission() {
        String clean=ScenarioEngine.safeInput("Tôi cần đặt bánh user@example.com +84912345678",2000);
        assertThat(clean).startsWith("Tôi").doesNotContain("user@example.com","84912345678");
        assertThatThrownBy(() -> ScenarioEngine.safeInput(" ",2000)).isInstanceOf(ScenarioFailure.class);
    }
    @Test void draftPreviewRequiresLocalProfileLoopbackBindingNamedUserAndBothPins() throws Exception {
        var registry=new ScenarioProgramRegistry(); var ai=mock(com.dictalab.service.AiPlatformClient.class); when(ai.textConfigured()).thenReturn(true);
        String hash; try (var in=getClass().getResourceAsStream("/scenario/workflow-v1.sha256")) { hash=new String(in.readAllBytes()).trim(); }
        var properties=new ScenarioEngineProperties(true,1,registry.require("general-communication",1).definitionHash(),hash,true,java.util.Set.of(3L));
        var env=new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("local"); env.setProperty("server.address","127.0.0.1");
        var release=new ScenarioRelease(new ScenarioProperties(true),properties,registry,ai,env);
        assertThat(release.available()).isFalse();
        assertThat(release.available(3)).isTrue(); assertThat(release.generationMode(3)).isEqualTo("LOCAL_PREVIEW");
        assertThat(release.available(4)).isFalse(); assertThatThrownBy(() -> release.require(4)).isInstanceOf(ScenarioFailure.class);
        env.setProperty("server.address","0.0.0.0"); assertThat(release.available(3)).isFalse();
        env.setProperty("server.address","127.0.0.1"); env.setActiveProfiles("production","local"); assertThat(release.available(3)).isFalse();
        env.setActiveProfiles("local");
        var wrongPin=new ScenarioEngineProperties(true,1,"0".repeat(64),hash,true,java.util.Set.of(3L));
        assertThat(new ScenarioRelease(new ScenarioProperties(true),wrongPin,registry,ai,env).available(3)).isFalse();
    }
    @Test void personalizedPreviewRequiresExactProgramAndPromptPinsAndLocalAdmission() throws Exception {
        var registry=new ScenarioProgramRegistry(); var program=registry.require("toeic-listening",2);
        var ai=mock(com.dictalab.service.AiPlatformClient.class); when(ai.textConfigured()).thenReturn(true);
        String hash; try (var in=getClass().getResourceAsStream("/scenario/workflow-v2.sha256")) { hash=new String(in.readAllBytes()).trim(); }
        var properties=new ScenarioEngineProperties(true,1,"",hash,true,java.util.Set.of(3L),java.util.Map.of("toeic-listening:2",program.definitionHash()));
        var env=new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("local"); env.setProperty("server.address","127.0.0.1");
        var release=new ScenarioRelease(new ScenarioProperties(true),properties,registry,ai,env);
        assertThat(release.require(3,program.code(),2)).isEqualTo(program);
        assertThat(release.available(4,program)).isFalse();
        assertThat(release.available(3,registry.require("ielts-listening",2))).isFalse();
        assertThat(release.available()).isFalse();
        env.setProperty("server.address","0.0.0.0"); assertThat(release.available(3,program)).isFalse();
        env.setProperty("server.address","127.0.0.1"); env.setActiveProfiles("production","local"); assertThat(release.available(3,program)).isFalse();
        env.setActiveProfiles("local");
        var wrongPin=new ScenarioEngineProperties(true,1,"","0".repeat(64),true,java.util.Set.of(3L),properties.approvedPrograms());
        assertThat(new ScenarioRelease(new ScenarioProperties(true),wrongPin,registry,ai,env).available(3,program)).isFalse();
    }

    @Test void configurationBindsExactVersionedProgramMapKeys() {
        var env=new org.springframework.mock.env.MockEnvironment()
                .withProperty("dictalab.scenario.engine.approved-programs.[toeic-listening:2]","a".repeat(64));
        var bound=org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("dictalab.scenario.engine",org.springframework.boot.context.properties.bind.Bindable.of(ScenarioEngineProperties.class)).get();
        assertThat(bound.approvedPrograms()).containsExactlyEntriesOf(java.util.Map.of("toeic-listening:2","a".repeat(64)));
    }

    @Test void coreRejectsMissingFocusAndInferenceOutsideProfileEvenWhenWorkerSaysQualityPassed() throws Exception {
        var fixture=fixture();
        var program=new ScenarioProgramRegistry().require("general-communication",2);
        var context=ScenarioPersonalization.context(program,new ScenarioGoalRequest.Target("practice-support","LISTENING","supported"),null,"detail");
        var content=fixture.path("content");
        for (var activity : content.path("activities")) for (var q : activity.path("questions"))
            ((ObjectNode)q).put("supportKind","direct").put("inferenceReasonEn","");
        ScenarioContent.content(content,fixture.path("specification"),context.path("profile"),context);
        context.put("focusSkill","reason");
        assertThatThrownBy(() -> ScenarioContent.content(content,fixture.path("specification"),context.path("profile"),context)).isInstanceOf(IllegalArgumentException.class);
        context.put("focusSkill","detail");
        ((ObjectNode)content.path("activities").get(0).path("questions").get(0)).put("supportKind","inference").put("skill","supported-inference").put("inferenceReasonEn","Claimed inference");
        assertThatThrownBy(() -> ScenarioContent.content(content,fixture.path("specification"),context.path("profile"),context)).isInstanceOf(IllegalArgumentException.class);
    }
}
