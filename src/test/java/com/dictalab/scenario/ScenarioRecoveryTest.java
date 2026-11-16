package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class ScenarioRecoveryTest {
    static tools.jackson.databind.JsonNode fixture() throws Exception {
        try (var stream=ScenarioRecoveryTest.class.getResourceAsStream("/scenario-v5-recovery-fixture.json")) {
            return ScenarioJson.MAPPER.readTree(stream);
        }
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
