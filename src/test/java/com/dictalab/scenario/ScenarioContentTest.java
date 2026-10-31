package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioContentTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="scenario.quality.capture",matches=".+")
    void capturedProviderResultsPassIndependentCoreValidation() throws Exception {
        var root=java.nio.file.Path.of(System.getProperty("scenario.quality.capture"));
        var manifest=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(root.resolve("manifest.json")));
        String pin; try (var in=getClass().getResourceAsStream("/scenario/workflow-v1.sha256")) { pin=new String(in.readAllBytes()).trim(); }
        assertThat(manifest.path("promptHash").asText()).isEqualTo(pin);
        assertThat(java.nio.file.Files.exists(root.resolve("finished.json"))).isTrue();
        var cases=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(root.resolve("cases.json"))).path("cases");
        assertThat(cases.size()).isPositive();
        for (var c : cases) {
            String name=c.path("id").asText(); var folder=root.resolve(name);
            var extract=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(folder.resolve("EXTRACT/outcome.json")));
            assertThat(extract.path("status").asText()).as(name+" EXTRACT").isEqualTo("SUCCEEDED");
            var spec=extract.path("result").path("task"); ScenarioContent.specification(spec);
            assertThat(spec.path("allowed").asBoolean()).as(name+" allowed").isEqualTo(c.path("expectedAllowed").asBoolean());
            if (!c.path("expectedAllowed").asBoolean()) {
                assertThat(java.nio.file.Files.exists(folder.resolve("GENERATE"))).as(name+" no unsafe generation").isFalse();
                continue;
            }
            var generation=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(folder.resolve("GENERATE/outcome.json")));
            var input=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(folder.resolve("GENERATE/input.json")));
            assertThat(generation.path("status").asText()).as(name+" GENERATE").isEqualTo("SUCCEEDED");
            ScenarioContent.content(generation.path("result").path("task"),spec,input.path("profile"));
        }
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
    @Test void allowsYesNoPolarityButStillRejectsSubstantiveDisclosures() {
        assertThat(ScenarioContent.answerLeaked("Vé cũ có được phép dùng tiếp hay không?","Có")).isFalse();
        assertThat(ScenarioContent.answerLeaked("Khách có được đổi vé không?","Không được")).isFalse();
        assertThat(ScenarioContent.answerLeaked("Khách có được đổi vé?","Có")).isFalse();
        assertThat(ScenarioContent.answerLeaked("Có mấy chuyến tàu?","Có")).isTrue();
        assertThat(ScenarioContent.answerLeaked("Khách đến sân ga số 4 phải không?","4")).isTrue();
        assertThat(ScenarioContent.answerLeaked("Khách thanh toán bằng thẻ phải không?","Bằng thẻ")).isTrue();
    }
    @Test void rejectsNamesOnlyInMetadataAndChangedTranslationDigits() throws Exception {
        var f=fixture(); var content=f.path("content");
        ((ObjectNode)content.path("activities").get(0).path("questions").get(0)).put("answerVi","Sam");
        assertThatThrownBy(() -> ScenarioContent.content(content,f.path("specification"),f.path("profile"))).isInstanceOf(IllegalArgumentException.class);
        var numbers=fixture();
        ((ObjectNode)numbers.path("content").path("activities").get(0).path("segments").get(1)).put("textEn","Yes, we open at 9. You can collect your order tomorrow morning.");
        ((ObjectNode)numbers.path("content").path("activities").get(0).path("questions").get(0).path("evidence").get(0)).put("quoteEn","we open at 9");
        ((ObjectNode)numbers.path("content").path("translations").get(1)).put("textVi","Chúng tôi mở lúc 8. Bạn có thể nhận đơn sáng mai.");
        assertThatThrownBy(() -> ScenarioContent.content(numbers.path("content"),numbers.path("specification"),numbers.path("profile"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void repeatedAnswerCannotRetestAPriorDecision() throws Exception {
        var f=fixture(); var content=f.path("content");
        ((ObjectNode)content.path("activities").get(2).path("questions").get(1)).put("answerVi","CHÍN GIỜ!");
        assertThatThrownBy(() -> ScenarioContent.content(content,f.path("specification"),f.path("profile"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsForgedEvidencePrivateFieldsAndOutOfBoundsContent() throws Exception {
        for (String fault : java.util.List.of("quote","translation","words","unicodeWords","quality","extra")) {
            var f=fixture(); var content=f.path("content");
            switch (fault) {
                case "quote" -> ((ObjectNode)content.path("activities").get(0).path("questions").get(0).path("evidence").get(0)).put("quoteEn","invented");
                case "translation" -> ((ObjectNode)content.path("translations").get(0)).put("segmentId","missing");
                case "words" -> ((ObjectNode)content.path("activities").get(0).path("segments").get(0)).put("textEn","word ".repeat(70));
                case "unicodeWords" -> ((ObjectNode)content.path("activities").get(0).path("segments").get(0)).put("textEn","word ".repeat(14)+"word\u00a0".repeat(70));
                case "quality" -> ((ObjectNode)content).put("qualityPassed",false);
                case "extra" -> ((ObjectNode)content.path("activities").get(0)).put("publicUrl","https://untrusted.test");
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
}
