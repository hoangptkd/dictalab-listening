package com.dictalab.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.dictalab.scenario.ScenarioData.*;
import static org.assertj.core.api.Assertions.*;

class ScenarioAssessmentTest {
    @Test void immutableMeaningReleasesAcceptOnlyTheirExactHashVersionPair() {
        var hashes=List.of(ScenarioAssessment.LEGACY_PROMPT_HASH,ScenarioAssessment.WIRE_V2_PROMPT_HASH,ScenarioAssessment.PROMPT_HASH);
        var versions=List.of("SCENARIO_MEANING_ASSESSMENT_V1","SCENARIO_MEANING_ASSESSMENT_V2","SCENARIO_MEANING_ASSESSMENT_V3");
        for (int hash=0;hash<hashes.size();hash++) for (int version=0;version<versions.size();version++)
            assertThat(ScenarioAssessment.releaseMatches(hashes.get(hash),versions.get(version))).isEqualTo(hash==version);
        assertThat(ScenarioAssessment.releaseMatches("0".repeat(64),"SCENARIO_MEANING_ASSESSMENT_V3")).isFalse();
    }
    static JsonNode fixture() {
        try (var input=ScenarioAssessmentTest.class.getResourceAsStream("/scenario/workflow-example.json")) {
            var fixture=ScenarioJson.MAPPER.readTree(input.readAllBytes());
            for (var a : fixture.path("content").path("activities")) {
                ((ObjectNode)a.path("segments").get(0)).put("textEn","We open at nine. Bread costs six pounds. Please collect it tomorrow.");
                ((ObjectNode)a.path("segments").get(1)).put("textEn","Thank you. I will collect it tomorrow and pay six pounds.");
                var meanings=((ObjectNode)a).putObject("assessment").put("policyVersion",ScenarioAssessment.POLICY).putArray("meanings");
                var questions=((ObjectNode)a).putArray("questions");
                int i=0;
                for (String quote : List.of("open at nine","costs six pounds","collect it tomorrow")) {
                    ++i; var evidence=ScenarioJson.MAPPER.createArrayNode(); evidence.addObject().put("segmentId","s1").put("quoteEn",quote);
                    var m=meanings.addObject().put("id","m"+i).put("meaningEn",quote).put("skill","detail").put("supportKind","direct").put("inferenceReasonEn",""); m.set("evidence",evidence);
                    var q=questions.addObject().put("id","q"+i).put("type","MEANING_RECALL_VI").put("skill","detail").put("promptVi","What is detail "+i+"?").put("answerVi","Reference "+i).put("supportKind","direct").put("inferenceReasonEn","");
                    q.set("evidence",evidence.deepCopy()); q.putArray("meaningIds").add("m"+i);
                }
            }
            return fixture;
        } catch (Exception failed) { throw new IllegalStateException(failed); }
    }
    static Map<String,Object> attempt(String mode,boolean assistance,String... statuses) {
        var support=object().put("mode",mode).put("transcriptOpened",assistance).put("hintOpened",false).put("feedbackExposed",false);
        var selected=support.putArray("selectedQuestionIds"); if (mode.equals("CHECKS")) selected.add("q1");
        var result=object(); var meanings=result.putArray("meanings");
        for (int i=0;i<statuses.length;i++) meanings.addObject().put("meaningId","m"+(i+1)).put("status",statuses[i]);
        return Map.of("support_json",support.toString(),"result_json",result.toString(),"status","GRADED");
    }
    @Test void v3IsSeparateAndRejectsUngroundedMissingDuplicateAndUnsupportedMeanings() throws Exception {
        var f=fixture(); var program=new ScenarioProgramRegistry().require("general-communication",4);
        var context=ScenarioPersonalization.context(program,new ScenarioGoalRequest.Target("cefr-listening","LISTENING","a1"),null,"detail");
        ScenarioContent.content(f.path("content"),f.path("specification"),f.path("profile"),context,true,true);
        for (String fault : List.of("foreign","duplicate","quote","inference","uncovered","label","prompt","evidence","focus","length","cap")) {
            var content=f.path("content").deepCopy(); var activity=content.path("activities").get(0);
            switch (fault) {
                case "foreign" -> ((tools.jackson.databind.node.ArrayNode)activity.path("questions").get(0).path("meaningIds")).set(0,ScenarioJson.MAPPER.getNodeFactory().stringNode("m6"));
                case "duplicate" -> ((ObjectNode)activity.path("assessment").path("meanings").get(1)).put("id","m1");
                case "quote" -> ((ObjectNode)activity.path("assessment").path("meanings").get(0).path("evidence").get(0)).put("quoteEn","invented");
                case "inference" -> ((ObjectNode)activity.path("assessment").path("meanings").get(0)).put("supportKind","inference");
                case "uncovered" -> ((tools.jackson.databind.node.ArrayNode)activity.path("questions").get(2).path("meaningIds")).removeAll().add("m1");
                case "label" -> ((ObjectNode)activity.path("assessment").path("meanings").get(1)).put("meaningEn"," OPEN  AT NINE ");
                case "prompt" -> ((ObjectNode)activity.path("questions").get(1)).put("promptVi"," WHAT  IS DETAIL 1? ");
                case "evidence" -> ((tools.jackson.databind.node.ArrayNode)activity.path("assessment").path("meanings").get(0).path("evidence")).add(activity.path("assessment").path("meanings").get(0).path("evidence").get(0).deepCopy());
                case "focus" -> { for (var a : content.path("activities")) for (var m : a.path("assessment").path("meanings")) ((ObjectNode)m).put("skill","reason"); }
                case "length" -> ((ObjectNode)activity.path("assessment").path("meanings").get(0)).put("meaningEn","x".repeat(301));
                case "cap" -> ((ObjectNode)context.path("profile")).put("maxQuestionsPerSession",8);
            }
            assertThatThrownBy(() -> ScenarioContent.content(content,f.path("specification"),f.path("profile"),context,true,true)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void firstIndependentMeaningIsNeverReplacedAndUnassessedDoesNotOccupyIt() {
        var attempts=List.of(attempt("RECAP",false,"UNASSESSED","UNCERTAIN","INCORRECT"),attempt("CHECKS",false,"CAPTURED","CAPTURED","CAPTURED"),attempt("CHECKS",true,"CAPTURED","PARTIAL","CAPTURED"));
        var e=ScenarioAssessment.project(attempts);
        assertThat(e.independent()).containsEntry("m1","CAPTURED").containsEntry("m2","UNCERTAIN").containsEntry("m3","INCORRECT");
        assertThat(e.latest()).containsEntry("m2","PARTIAL"); assertThat(e.assisted()).containsEntry("m2","PARTIAL");
        var coverage=ScenarioAssessment.coverage(fixture().path("content").path("activities").get(0),e,false);
        assertThat(coverage.properties()).extracting(Map.Entry::getKey).containsExactly("total","assessed");
    }
    @Test void selectionIsStableFiniteAndNeverPadsAlreadyCapturedMeanings() {
        var activity=fixture().path("content").path("activities").get(0); var state=ScenarioAssessment.initial();
        ScenarioAssessment.advance(state,activity,List.of()); assertThat(state.path("pendingQuestionIds").toString()).isEqualTo("[\"q1\",\"q2\",\"q3\"]");
        state=ScenarioAssessment.initial(); ScenarioAssessment.advance(state,activity,List.of(attempt("RECAP",false,"CAPTURED","CAPTURED","CAPTURED")));
        assertThat(state.path("pendingQuestionIds")).isEmpty(); assertThat(ScenarioAssessment.canComplete(state,ScenarioAssessment.project(List.of(attempt("RECAP",false,"CAPTURED","CAPTURED","CAPTURED"))))).isTrue();
    }
    @Test void oneFollowupBatchPersistsAndFirstBatchCanCompleteEarly() {
        var activity=fixture().path("content").path("activities").get(0);
        var meanings=(tools.jackson.databind.node.ArrayNode)activity.path("assessment").path("meanings"); var questions=(tools.jackson.databind.node.ArrayNode)activity.path("questions");
        for (int i=4;i<=6;i++) {
            var m=((ObjectNode)meanings.get(0)).deepCopy().put("id","m"+i).put("meaningEn","Extra meaning "+i); meanings.add(m);
            var q=((ObjectNode)questions.get(0)).deepCopy().put("id","q"+i); q.putArray("meaningIds").add("m"+i); questions.add(q);
        }
        var state=ScenarioAssessment.initial().put("recapSkipped",true); ScenarioAssessment.advance(state,activity,List.of());
        assertThat(state.path("checksRound").asInt()).isEqualTo(1); assertThat(state.path("pendingQuestionIds")).hasSize(4);
        var graded=attempt("CHECKS",false,"CAPTURED","CAPTURED","CAPTURED","CAPTURED"); var selected=((ObjectNode)tree((String)graded.get("support_json"))); selected.putArray("selectedQuestionIds").add("q1").add("q2").add("q3").add("q4");
        var attempts=List.of(Map.<String,Object>of("support_json",selected.toString(),"result_json",graded.get("result_json"),"status","GRADED"));
        ScenarioAssessment.advance(state,activity,attempts); assertThat(state.path("pendingQuestionIds").toString()).isEqualTo("[\"q5\",\"q6\"]");
        assertThat(ScenarioAssessment.done(state,ScenarioAssessment.project(attempts))).isFalse(); assertThat(ScenarioAssessment.canComplete(state,ScenarioAssessment.project(attempts))).isTrue();
        var saved=state.toString(); ScenarioAssessment.advance(state,activity,attempts); assertThat(state.toString()).isEqualTo(saved);
    }
    @Test void gradeRequiresExactMeaningSetAndVerbatimRelevantLearnerEvidence() {
        var submission=object().put("mode","CHECKS").put("recapText",""); submission.putArray("answers").addObject().put("questionId","q1").put("textVi","nine"); submission.putArray("meaningIds").add("m1");
        var result=object(); var meaning=result.putArray("meanings").addObject().put("meaningId","m1").put("status","CAPTURED"); meaning.putArray("evidence").addObject().put("responseId","q1").put("quote","nine");
        var activity=fixture().path("content").path("activities").get(0); ScenarioAssessment.grade(result,submission,activity);
        ((ObjectNode)meaning.path("evidence").get(0)).put("quote","ten"); assertThatThrownBy(() -> ScenarioAssessment.grade(result,submission,activity)).isInstanceOf(IllegalArgumentException.class);
        meaning.putArray("evidence"); meaning.put("status","UNASSESSED"); assertThatThrownBy(() -> ScenarioAssessment.grade(result,submission,activity)).isInstanceOf(IllegalArgumentException.class);
        submission.put("mode","RECAP").put("recapText","nine"); submission.putArray("answers"); ScenarioAssessment.grade(result,submission,activity);
        meaning.put("status","MISSED"); assertThatThrownBy(() -> ScenarioAssessment.grade(result,submission,activity)).isInstanceOf(IllegalArgumentException.class);
    }
}
