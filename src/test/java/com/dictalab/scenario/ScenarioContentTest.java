package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioContentTest {
    JsonNode fixture() throws Exception {
        try (var in=getClass().getResourceAsStream("/scenario/workflow-example.json")) { return ScenarioJson.MAPPER.readTree(in.readAllBytes()); }
    }
    @Test void sharedFixturePassesIndependentCoreValidation() throws Exception {
        var f=fixture(); ScenarioContent.specification(f.path("specification")); ScenarioContent.content(f.path("content"),f.path("specification"),f.path("profile"));
        ScenarioContent.grade(f.path("grade"),f.path("answers"));
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
        var release=new ScenarioRelease(new ScenarioProperties(true),new ScenarioEngineProperties(true,1,registry.require("general-communication",1).definitionHash(),hash),registry,ai);
        assertThat(release.available()).isFalse();
        assertThatThrownBy(release::require).isInstanceOfSatisfying(ScenarioFailure.class,e -> assertThat(e.status).isEqualTo(503));
    }
    @Test void normalizesUnicodeAndRedactsContactDataBeforeAiAdmission() {
        String clean=ScenarioEngine.safeInput("Tôi cần đặt bánh user@example.com +84912345678",2000);
        assertThat(clean).startsWith("Tôi").doesNotContain("user@example.com","84912345678");
        assertThatThrownBy(() -> ScenarioEngine.safeInput(" ",2000)).isInstanceOf(ScenarioFailure.class);
    }
}
