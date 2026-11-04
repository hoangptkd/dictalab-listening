package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ScenarioHarnessTest {
    static ObjectNode transcript(int extra, String... stages) {
        var result=ScenarioData.object();
        var calls=result.putArray("calls");
        for (String stage : stages) calls.addObject().put("purpose",stage).put("provider","ollama").put("model","offline-model");
        result.putObject("qualityHarness").put("extraCalls",extra).put("policyVersion","scenario-quality-harness.v1")
                .put("policyHash","ac1a80c90c9f24149f14a9221c0c7382e653d94fccbd823b3862c55fa043bc6b");
        return result;
    }

    @Test void acceptsOnlyFiniteBaseFormatAndScopedRepairPaths() {
        var paths=List.of(
                transcript(0,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(1,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(1,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REPAIR_TRANSLATIONS","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW","REPAIR_TRANSLATIONS","REVIEW"),
                transcript(4,"GENERATE","QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REPAIR_TRANSLATIONS","REVIEW"),
                transcript(4,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW","REVIEW"));
        for (var path : paths) assertThatCode(() -> ScenarioEngine.validateHarnessCalls(path)).doesNotThrowAnyException();
    }

    @Test void rejectsReplayMissingRecheckRepeatedPatchOrUnaccountedCalls() {
        var paths=List.of(
                transcript(0,"GENERATE","QUESTIONS","TRANSLATE","REVIEW_QUESTIONS","REVIEW"),
                transcript(0,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REPAIR_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(3,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(2,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW","GENERATE"),
                transcript(2,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REVIEW_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"),
                transcript(5,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","REVIEW_QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW","REPAIR_TRANSLATIONS","REVIEW"),
                transcript(1,"GENERATE","QUESTIONS","REPAIR_QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW"));
        for (var path : paths) assertThatThrownBy(() -> ScenarioEngine.validateHarnessCalls(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsWrongPolicyHashVersionAndNonIntegerExtraCount() {
        for (String field : List.of("policyHash","policyVersion","extraCalls")) {
            var path=transcript(0,"GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW");
            ((ObjectNode)path.path("qualityHarness")).put(field,"wrong");
            assertThatThrownBy(() -> ScenarioEngine.validateHarnessCalls(path)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
