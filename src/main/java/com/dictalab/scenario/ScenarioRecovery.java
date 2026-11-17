package com.dictalab.scenario;

import tools.jackson.databind.JsonNode;
import java.util.*;
import static com.dictalab.scenario.ScenarioContent.*;

/** Core verifies the finite recovery transcript and binds acceptance to final artifacts. */
final class ScenarioRecovery {
    private ScenarioRecovery() {}
    static boolean enabled(String pin) { return ScenarioAssessment.RECOVERY_V5_1_PROMPT_HASH.equals(pin) || ScenarioAssessment.sparseBank(pin); }

    static void calls(JsonNode result, boolean grading) {
        var report=result.path("qualityHarness");
        check(report.path("policyVersion").asText().equals("scenario-meaning-harness.v4")
                && report.path("policyHash").asText().equals(ScenarioAssessment.RECOVERY_HARNESS_POLICY_HASH)
                && report.path("decision").asText().equals("ACCEPTED"));
        var calls=result.path("calls"); var plan=report.path("callPlan");
        list(calls,grading ? 2 : 6,grading ? 7 : 17); list(plan,calls.size(),calls.size());
        check(report.path("extraCalls").isIntegralNumber()
                && report.path("extraCalls").asInt()==Math.max(0,calls.size()-(grading ? 1 : 6)));
        int sources=0,questions=0,translations=0,qPatches=0,tPatches=0;
        boolean qReady=grading,tReady=false,qReviewed=false,tReviewed=false,scored=false,sourceReviewed=false;
        String source=null;
        for (int n=0;n<calls.size();n++) {
            String stage=calls.get(n).path("purpose").asText();
            check(stage.equals(plan.get(n).path("purpose").asText()) && !scored);
            if (!stage.equals("GENERATE")) {
                String hash=text(plan.get(n),"sourceHash",64); check(hash.matches("[a-f0-9]{64}"));
                if (source==null) source=hash; else check(source.equals(hash));
            }
            switch (stage) {
                case "GENERATE" -> {
                    check(!grading && ++sources<=2 && (n==0 || sourceReviewed || qReviewed || tReviewed || n==1));
                    qReady=tReady=qReviewed=tReviewed=sourceReviewed=false; source=null;
                }
                case "QUESTIONS" -> { check(!grading && sources>0 && sourceReviewed && !qReady && ++questions<=2); qReady=true; qReviewed=false; }
                case "REVIEW_QUESTIONS" -> { check(qReady && !tReady); qReviewed=true; }
                case "REPAIR_QUESTIONS" -> { check(qReady && !tReady && ++qPatches<=2); qReviewed=false; }
                case "TRANSLATE" -> { check(!grading && qReviewed && !tReady && ++translations<=2); tReady=true; }
                case "REVIEW" -> { check(!grading && sources>0 && (!qReady || tReady)); if (tReady) tReviewed=true; else sourceReviewed=true; }
                case "REPAIR_TRANSLATIONS" -> { check(tReady && ++tPatches<=2); tReviewed=false; }
                case "GRADE" -> { check(grading && qReviewed && n==calls.size()-1); scored=true; }
                default -> throw new IllegalArgumentException("Unknown recovery stage");
            }
        }
        check(grading ? scored : qReviewed && tReviewed);
        if (!grading) {
            check(result.path("task").path("qualityPassed").isBoolean() && result.path("task").path("qualityPassed").asBoolean());
            check(report.path("contentHash").asText().equals(ScenarioJson.hash(ScenarioJson.canonical(result.path("task")))));
            var dialogue=ScenarioData.object(); var activities=dialogue.putArray("activities");
            for (var activity : result.path("task").path("activities")) {
                var a=activities.addObject(); a.set("id",activity.path("id")); a.set("segments",activity.path("segments"));
            }
            String hash=ScenarioJson.hash(ScenarioJson.canonical(dialogue));
            check(hash.equals(source) && hash.equals(report.path("sourceHash").asText()));
        }
    }

    static JsonNode gradedActivity(JsonNode input,JsonNode result) {
        var original=input.path("activity");
        if (enabled(input.path("promptHash").asText())) {
            var source=ScenarioData.object(); var activity=source.putArray("activities").addObject();
            activity.set("id",original.path("id")); activity.set("segments",original.path("segments"));
            String hash=ScenarioJson.hash(ScenarioJson.canonical(source));
            check(hash.equals(result.path("qualityHarness").path("originalSourceHash").asText()));
            for (var entry : result.path("qualityHarness").path("callPlan")) check(hash.equals(entry.path("sourceHash").asText()));
        }
        if (!result.has("assessmentRepair")) return original;
        check(enabled(input.path("promptHash").asText()));
        var patch=result.path("assessmentRepair"); fields(patch,"originalHash","activity");
        check(patch.path("originalHash").asText().equals(ScenarioJson.hash(ScenarioJson.canonical(original))));
        var fixed=patch.path("activity"); fields(fixed,"id","segments","questions","assessment");
        check(fixed.path("id").equals(original.path("id")) && fixed.path("segments").equals(original.path("segments")));
        list(fixed.path("questions"),original.path("questions").size(),original.path("questions").size());
        for (int n=0;n<original.path("questions").size();n++) {
            var before=original.path("questions").get(n); var after=fixed.path("questions").get(n);
            for (String field : List.of("id","type","promptVi","meaningIds")) check(before.path(field).equals(after.path(field)));
            text(after,"answerVi",200); list(after.path("evidence"),1,3);
            for (var evidence : after.path("evidence")) {
                fields(evidence,"segmentId","quoteEn");
                check(java.util.stream.StreamSupport.stream(fixed.path("segments").spliterator(),false)
                        .anyMatch(s -> s.path("id").equals(evidence.path("segmentId")) && s.path("textEn").asText().contains(text(evidence,"quoteEn",400))));
            }
        }
        var segments=new HashMap<String,String>(); fixed.path("segments").forEach(s -> segments.put(s.path("id").asText(),s.path("textEn").asText()));
        ScenarioContent.meanings(fixed,segments,input.path("learningContext"),ScenarioAssessment.sparseBank(input.path("promptHash").asText()) ? 1 : 2);
        var beforeIds=new ArrayList<String>(); var afterIds=new ArrayList<String>();
        original.path("assessment").path("meanings").forEach(m -> beforeIds.add(m.path("id").asText()));
        fixed.path("assessment").path("meanings").forEach(m -> afterIds.add(m.path("id").asText())); check(beforeIds.equals(afterIds));
        return fixed;
    }
}
