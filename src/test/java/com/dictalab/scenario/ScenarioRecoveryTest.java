package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class ScenarioRecoveryTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="dictalab.scenario.saved-acceptance-file",matches=".+")
    void savedArtifactsUseTheActualCoreAcceptanceGate() throws Exception {
        var path=java.nio.file.Path.of(System.getProperty("dictalab.scenario.saved-acceptance-file"));
        var cases=ScenarioJson.MAPPER.readTree(java.nio.file.Files.readAllBytes(path));
        for (var row : cases) {
            var input=row.path("payload");
            if (row.has("result")) ScenarioEngine.validateHarnessCalls(row.path("result"));
            ScenarioContent.content(row.path("task"),input.path("specification"),input.path("profile"),input.path("learningContext"),true,false,1);
        }
    }
    static tools.jackson.databind.JsonNode fixture() throws Exception {
        try (var stream=ScenarioRecoveryTest.class.getResourceAsStream("/scenario-v5-recovery-fixture.json")) {
            return ScenarioJson.MAPPER.readTree(stream);
        }
    }
    @Test void sparseBankAcceptsOneDirectQuestionAndDoesNotRequireInferenceCoverage() throws Exception {
        var f=fixture(); var payload=(ObjectNode)f.path("payload").deepCopy(); var result=(ObjectNode)f.path("result").deepCopy();
        result.put("promptHash",ScenarioAssessment.SPARSE_V5_2_PROMPT_HASH).put("promptVersion","SCENARIO_MEANING_ASSESSMENT_V5_2");
        ((ObjectNode)payload.path("learningContext")).put("focusSkill","supported-inference");
        for (var activity : result.path("task").path("activities")) {
            // Removing q1/m1 leaves q2/m2 stable; no renumbering of correct neighbors.
            var questions=(tools.jackson.databind.node.ArrayNode)activity.path("questions"); while (questions.size()>1) questions.remove(0);
            var meanings=(tools.jackson.databind.node.ArrayNode)activity.path("assessment").path("meanings"); while (meanings.size()>1) meanings.remove(0);
        }
        ((ObjectNode)result.path("qualityHarness")).put("contentHash",ScenarioJson.hash(ScenarioJson.canonical(result.path("task"))));
        assertThatCode(() -> ScenarioEngine.validateHarnessCalls(result)).doesNotThrowAnyException();
        assertThatCode(() -> ScenarioContent.content(result.path("task"),payload.path("specification"),payload.path("profile"),payload.path("learningContext"),true,false,1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ScenarioContent.content(result.path("task"),payload.path("specification"),payload.path("profile"),payload.path("learningContext"),true,false,2)).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode)result.path("task").path("activities").get(0).path("questions").get(0).path("evidence").get(0)).put("quoteEn","Unsupported answer.");
        assertThatThrownBy(() -> ScenarioContent.content(result.path("task"),payload.path("specification"),payload.path("profile"),payload.path("learningContext"),true,false,1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pythonRepairResultIsAcceptedByCoreWithTheSameSourceAndContent() throws Exception {
        var f=fixture(); var result=f.path("result"); var payload=f.path("payload");
        assertThat(result.path("qualityHarness").path("attempts").get(0).path("accepted").asBoolean()).isTrue();
        assertThatCode(() -> ScenarioEngine.validateHarnessCalls(result)).doesNotThrowAnyException();
        assertThatCode(() -> ScenarioContent.content(result.path("task"),payload.path("specification"),payload.path("profile"),payload.path("learningContext"),true,false,2)).doesNotThrowAnyException();
        assertThat(ScenarioOperations.callEnvelope("GENERATE",payload)).isEqualTo(17);
        assertThat(ScenarioOperations.callEnvelope("GRADE",payload)).isEqualTo(7);
    }
    @Test void unresolvedDecisionFalseQualityAndMixedSourceCannotSettle() throws Exception {
        var base=(ObjectNode)fixture().path("result");
        var unresolved=base.deepCopy(); ((ObjectNode)unresolved.path("qualityHarness")).put("decision","UNRESOLVED");
        assertThatThrownBy(() -> ScenarioRecovery.calls(unresolved,false)).isInstanceOf(IllegalArgumentException.class);
        var quality=base.deepCopy(); ((ObjectNode)quality.path("task")).put("qualityPassed",false);
        assertThatThrownBy(() -> ScenarioRecovery.calls(quality,false)).isInstanceOf(IllegalArgumentException.class);
        var mixed=base.deepCopy(); ((ObjectNode)mixed.path("qualityHarness").path("callPlan").get(3)).put("sourceHash","0".repeat(64));
        assertThatThrownBy(() -> ScenarioRecovery.calls(mixed,false)).isInstanceOf(IllegalArgumentException.class);
        var tampered=base.deepCopy(); ((ObjectNode)tampered.path("task").path("activities").get(0).path("segments").get(0)).put("textEn","A different source.");
        assertThatThrownBy(() -> ScenarioRecovery.calls(tampered,false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void gradeRepairCannotChangeSourcePromptOrOriginalFingerprint() throws Exception {
        var input=(ObjectNode)fixture().path("payload").deepCopy();
        input.set("activity",fixture().path("result").path("task").path("activities").get(0));
        var result=ScenarioData.object(); var repair=result.putObject("assessmentRepair");
        repair.put("originalHash",ScenarioJson.hash(ScenarioJson.canonical(input.path("activity")))); repair.set("activity",input.path("activity").deepCopy());
        var source=ScenarioData.object(); var act=source.putArray("activities").addObject(); act.set("id",input.path("activity").path("id")); act.set("segments",input.path("activity").path("segments"));
        result.putObject("qualityHarness").put("originalSourceHash",ScenarioJson.hash(ScenarioJson.canonical(source))).putArray("callPlan");
        assertThatCode(() -> ScenarioRecovery.gradedActivity(input,result)).doesNotThrowAnyException();
        ((ObjectNode)repair.path("activity").path("questions").get(0)).put("promptVi","A changed question");
        assertThatThrownBy(() -> ScenarioRecovery.gradedActivity(input,result)).isInstanceOf(IllegalArgumentException.class);
        repair.set("activity",input.path("activity").deepCopy()); repair.put("originalHash","0".repeat(64));
        assertThatThrownBy(() -> ScenarioRecovery.gradedActivity(input,result)).isInstanceOf(IllegalArgumentException.class);
    }
}
