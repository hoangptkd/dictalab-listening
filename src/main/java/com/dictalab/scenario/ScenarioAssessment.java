package com.dictalab.scenario;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.dictalab.scenario.ScenarioData.*;
import static com.dictalab.scenario.ScenarioContent.*;

/** Finite meaning evidence projection and deterministic server-owned question selection. */
final class ScenarioAssessment {
    static final String POLICY="scenario-meaning-assessment.v1";
    static final String PARTS_POLICY="scenario-meaning-assessment.v2";
    static final String PARTS_PROMPT_HASH="b9be440e03b9b31163f02c75e685092277fb42fb1ecc0156d871d030efa94503";
    static final String PARTS_V4_1_PROMPT_HASH="ce0a72c6c7616ebfaab979bf3404379d5534ca9e21b4fbf8e4344a0bcea02714";
    static final String PARTS_V4_3_PROMPT_HASH="139a7d7789923e10af8412d5f23f6fec7577116e4d1c97eb6ce907230a56f0aa";
    static final String PARTS_V4_4_PROMPT_HASH="09b222b0c140c0a3b07164639ff020cb272779319e6f79758eac55b058ce13bc";
    static final String LEGACY_PROMPT_HASH="ff50fd266eb277d973b8daed92dbf5b7ccb1beb17c28ad6c353fcaaf86c58630";
    static final String WIRE_V2_PROMPT_HASH="4693e3c73b820a6eaea53311eaa5d8beaa7269cb9a71089fca6af98c112d31eb";
    static final String PROMPT_HASH="650193db73e2fb03c4f94a7ccb8f29fc9bc1c3f77c8391fabc0b0e07803f287e";
    static boolean releaseMatches(String hash,String version) {
        return LEGACY_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V1".equals(version)
                || WIRE_V2_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V2".equals(version)
                || PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V3".equals(version)
                || PARTS_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V4".equals(version)
                || PARTS_V4_1_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V4_1".equals(version)
                || PARTS_V4_3_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V4_3".equals(version)
                || PARTS_V4_4_PROMPT_HASH.equals(hash) && "SCENARIO_MEANING_ASSESSMENT_V4_4".equals(version);
    }
    static String policyFor(String hash) { return Set.of(PARTS_PROMPT_HASH,PARTS_V4_1_PROMPT_HASH,PARTS_V4_3_PROMPT_HASH,PARTS_V4_4_PROMPT_HASH).contains(hash) ? PARTS_POLICY : POLICY; }
    static boolean enabled(JsonNode activity) { return activity.has("assessment"); }
    static ObjectNode initial() {
        var state=object().put("recapSkipped",false).put("checksRound",0); state.putArray("pendingQuestionIds"); return state;
    }
    static ObjectNode state(Map<String,Object> activity) {
        return activity.get("assessment_state_json")==null ? initial() : (ObjectNode)tree((String)activity.get("assessment_state_json"));
    }
    static boolean assisted(JsonNode support) {
        return support.path("transcriptOpened").asBoolean() || support.path("hintOpened").asBoolean() || support.path("feedbackExposed").asBoolean();
    }
    record Evidence(Map<String,String> independent,Map<String,String> latest,Map<String,String> assisted,Set<String> asked,boolean recap,boolean checks,boolean pending) {}
    static Evidence project(List<Map<String,Object>> attempts) {
        var independent=new LinkedHashMap<String,String>(); var latest=new LinkedHashMap<String,String>(); var supported=new LinkedHashMap<String,String>(); var asked=new HashSet<String>();
        boolean recap=false,checks=false,pending=false;
        for (var attempt : attempts) {
            var support=tree((String)attempt.get("support_json"));
            support.path("selectedQuestionIds").forEach(q -> asked.add(q.asText()));
            pending |= Set.of("PENDING","UNKNOWN").contains(attempt.get("status"));
            if (!"GRADED".equals(attempt.get("status")) || attempt.get("result_json")==null) continue;
            recap |= support.path("mode").asText().equals("RECAP"); checks |= support.path("mode").asText().equals("CHECKS");
            for (var meaning : tree((String)attempt.get("result_json")).path("meanings")) {
                String id=meaning.path("meaningId").asText(),status=meaning.path("status").asText();
                if (status.equals("UNASSESSED")) continue;
                latest.put(id,status);
                if (assisted(support)) supported.put(id,status); else independent.putIfAbsent(id,status);
            }
        }
        return new Evidence(independent,latest,supported,asked,recap,checks,pending);
    }
    static List<String> select(JsonNode activity,Evidence evidence,int limit) {
        var unresolved=new LinkedHashSet<String>();
        activity.path("assessment").path("meanings").forEach(m -> { if (!"CAPTURED".equals(evidence.latest().get(m.path("id").asText()))) unresolved.add(m.path("id").asText()); });
        var selected=new ArrayList<String>();
        while (selected.size()<limit && !unresolved.isEmpty()) {
            JsonNode best=null; int bestNew=-1,bestOther=-1;
            for (var question : activity.path("questions")) {
                String id=question.path("id").asText(); if (evidence.asked().contains(id) || selected.contains(id)) continue;
                int fresh=0,other=0;
                for (var meaning : question.path("meaningIds")) if (unresolved.contains(meaning.asText())) {
                    if (!evidence.latest().containsKey(meaning.asText())) fresh++; else other++;
                }
                if (fresh+other>0 && (fresh>bestNew || fresh==bestNew && other>bestOther)) { best=question; bestNew=fresh; bestOther=other; }
            }
            if (best==null) break;
            selected.add(best.path("id").asText()); best.path("meaningIds").forEach(m -> unresolved.remove(m.asText()));
        }
        return selected;
    }
    static void advance(ObjectNode state,JsonNode activity,List<Map<String,Object>> attempts) {
        var evidence=project(attempts); int round=state.path("checksRound").asInt();
        if (round>=2 || evidence.pending()) return;
        var selected=select(activity,evidence,round==0 ? 4 : 2);
        state.set("pendingQuestionIds",ScenarioJson.MAPPER.valueToTree(selected));
        state.put("checksRound",selected.isEmpty() ? 2 : round+1);
    }
    static boolean done(ObjectNode state,Evidence e) {
        return (state.path("recapSkipped").asBoolean() || e.recap() || e.checks()) && state.path("pendingQuestionIds").isEmpty() && !e.pending();
    }
    static boolean canComplete(ObjectNode state,Evidence e) { return !e.pending() && (done(state,e) || e.checks()); }
    static ObjectNode coverage(JsonNode activity,Evidence e,boolean disclose) {
        int total=activity.path("assessment").path("meanings").size();
        var result=object().put("total",total).put("assessed",e.latest().size());
        if (!disclose) return result;
        counts(result,e.latest(),total); counts(result.putObject("independent"),e.independent(),total); counts(result.putObject("assisted"),e.assisted(),total);
        var meanings=result.putArray("meanings");
        for (var m : activity.path("assessment").path("meanings")) {
            String id=m.path("id").asText(); var item=meanings.addObject().put("meaningId",id).put("labelEn",m.path("meaningEn").asText()).put("skill",m.path("skill").asText()).put("status",e.latest().getOrDefault(id,"UNASSESSED"));
            item.put("independentStatus",e.independent().get(id)); item.put("assistedStatus",e.assisted().get(id));
        }
        return result;
    }
    private static void counts(ObjectNode result,Map<String,String> values,int total) {
        result.put("understood",Collections.frequency(new ArrayList<>(values.values()),"CAPTURED"))
                .put("partial",Collections.frequency(new ArrayList<>(values.values()),"PARTIAL"))
                .put("misunderstood",Collections.frequency(new ArrayList<>(values.values()),"INCORRECT"))
                .put("missed",Collections.frequency(new ArrayList<>(values.values()),"MISSED"))
                .put("uncertain",Collections.frequency(new ArrayList<>(values.values()),"UNCERTAIN")).put("unassessed",total-values.size());
    }
    static void grade(JsonNode result,JsonNode submission,JsonNode activity) {
        fields(result,"meanings"); var expected=new HashSet<String>(); submission.path("meaningIds").forEach(m -> check(expected.add(m.asText())));
        list(result.path("meanings"),expected.size(),expected.size()); var seen=new HashSet<String>(); var responses=new HashMap<String,String>();
        boolean recap=submission.path("mode").asText().equals("RECAP");
        if (recap) responses.put("recap",submission.path("recapText").asText());
        else submission.path("answers").forEach(a -> responses.put(a.path("questionId").asText(),a.path("textVi").asText()));
        boolean parts=PARTS_POLICY.equals(activity.path("assessment").path("policyVersion").asText());
        for (var m : result.path("meanings")) {
            if (parts) fields(m,"meaningId","status","evidence","parts"); else fields(m,"meaningId","status","evidence");
            String id=id(m,"meaningId"),status=text(m,"status",16);
            check(expected.contains(id) && seen.add(id) && Set.of("CAPTURED","PARTIAL","INCORRECT","MISSED","UNCERTAIN","UNASSESSED").contains(status));
            check(!recap || !status.equals("MISSED")); check(recap || !status.equals("UNASSESSED"));
            learnerEvidence(m.path("evidence"),status,id,responses,activity,recap,parts ? 24 : 6);
            if (parts) {
                var source=java.util.stream.StreamSupport.stream(activity.path("assessment").path("meanings").spliterator(),false)
                        .filter(meaning -> meaning.path("id").asText().equals(id)).findFirst().orElseThrow(IllegalArgumentException::new);
                var required=new HashSet<String>(); source.path("requiredParts").forEach(p -> check(required.add(p.path("id").asText())));
                check(!required.isEmpty()); list(m.path("parts"),required.size(),required.size());
                var actual=new HashSet<String>(); var statuses=new ArrayList<String>(); var union=new HashSet<JsonNode>();
                for (var part : m.path("parts")) {
                    fields(part,"partId","status","evidence"); check(required.contains(id(part,"partId")) && actual.add(part.path("partId").asText()));
                    String partStatus=text(part,"status",16); check(Set.of("CAPTURED","PARTIAL","INCORRECT","UNCERTAIN","ABSENT").contains(partStatus));
                    statuses.add(partStatus); learnerEvidence(part.path("evidence"),partStatus,id,responses,activity,recap,6);
                    check(partStatus.equals("ABSENT") == part.path("evidence").isEmpty());
                    part.path("evidence").forEach(union::add);
                }
                check(actual.equals(required) && status.equals(aggregateParts(statuses,recap)));
                var aggregated=new HashSet<JsonNode>(); m.path("evidence").forEach(aggregated::add); check(aggregated.equals(union));
            }
        }
        check(seen.equals(expected));
    }
    private static void learnerEvidence(JsonNode evidence,String status,String meaning,Map<String,String> responses,JsonNode activity,boolean recap,int max) {
        list(evidence,0,max); var seen=new HashSet<String>();
        for (var ev : evidence) {
            fields(ev,"responseId","quote"); String response=text(ev,"responseId",16),quote=text(ev,"quote",1500);
            check(responses.containsKey(response) && responses.get(response).contains(quote) && seen.add(response+"\u0000"+quote));
            if (!recap) check(java.util.stream.StreamSupport.stream(activity.path("questions").spliterator(),false).anyMatch(q -> q.path("id").asText().equals(response)
                    && java.util.stream.StreamSupport.stream(q.path("meaningIds").spliterator(),false).anyMatch(mid -> mid.asText().equals(meaning))));
        }
        if (Set.of("CAPTURED","PARTIAL","INCORRECT").contains(status)) check(!evidence.isEmpty());
        if (Set.of("UNASSESSED","MISSED","ABSENT").contains(status)) check(evidence.isEmpty());
    }
    static String aggregateParts(List<String> statuses,boolean recap) {
        check(!statuses.isEmpty());
        if (statuses.stream().allMatch("CAPTURED"::equals)) return "CAPTURED";
        if (statuses.contains("UNCERTAIN")) return "UNCERTAIN";
        if (statuses.contains("CAPTURED") || statuses.contains("PARTIAL")) return "PARTIAL";
        if (statuses.contains("INCORRECT")) return "INCORRECT";
        return recap ? "UNASSESSED" : "MISSED";
    }
}
