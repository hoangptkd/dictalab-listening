package com.dictalab.scenario;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Independent deterministic validation before Core accepts any private AI result. */
final class ScenarioContent {
    private ScenarioContent() {}
    static void check(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid scenario result"); }
    static void fields(JsonNode value, String... names) {
        var allowed = Set.of(names);
        check(value.isObject() && value.size() == allowed.size());
        value.properties().forEach(e -> check(allowed.contains(e.getKey())));
    }
    static String text(JsonNode n, String name, int max) {
        var v = n.path(name); check(v.isTextual() && !v.asText().isBlank() && v.asText().length() <= max); return v.asText();
    }
    static String id(JsonNode n, String name) {
        String id = text(n,name,16); check(id.matches("[a-z][a-z0-9-]{0,15}")); return id;
    }
    static void list(JsonNode n, int min, int max) { check(n.isArray() && n.size() >= min && n.size() <= max); }
    static void specification(JsonNode s) {
        specification(s,false);
    }
    static void specification(JsonNode s,boolean personalized) {
        if (personalized) {
            fields(s,"allowed","titleVi","settingVi","learnerRoleVi","goalVi","characters","constraintsVi","assumptionsVi","topicIds","learnerMode","genre");
            check(Set.of("listener","participant").contains(text(s,"learnerMode",16)));
            check(Set.of("information-exchange","discussion","explanation","experience","interview","problem-solving","transaction").contains(text(s,"genre",30)));
            if (s.path("learnerMode").asText().equals("listener")) check(s.path("learnerRoleVi").asText().equals("Người nghe"));
        } else fields(s,"allowed","titleVi","settingVi","learnerRoleVi","goalVi","characters","constraintsVi","assumptionsVi","topicIds");
        check(s.path("allowed").isBoolean()); text(s,"titleVi",120); text(s,"settingVi",400); text(s,"learnerRoleVi",100); text(s,"goalVi",300);
        list(s.path("characters"),2,2); var speakers = new HashSet<String>();
        for (var c : s.path("characters")) { fields(c,"id","name","roleVi"); check(speakers.add(id(c,"id"))); text(c,"name",40); text(c,"roleVi",100); }
        for (String field : List.of("constraintsVi","assumptionsVi")) {
            list(s.path(field),0,field.equals("constraintsVi") ? 6 : 4);
            for (var value : s.path(field)) check(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 200);
        }
        list(s.path("topicIds"),0,2);
        for (var topic : s.path("topicIds")) check(Set.of("food","transport","shopping","workstudy","services","social").contains(topic.asText()));
    }
    static void content(JsonNode task, JsonNode spec, JsonNode profile) {
        content(task,spec,profile,null);
    }
    static void content(JsonNode task, JsonNode spec, JsonNode profile,JsonNode context) {
        content(task,spec,profile,context,false);
    }
    static void content(JsonNode task, JsonNode spec, JsonNode profile,JsonNode context,boolean flexible) {
        content(task,spec,profile,context,flexible,false);
    }
    static void content(JsonNode task, JsonNode spec, JsonNode profile,JsonNode context,boolean flexible,boolean advisory) {
        boolean meaning=context!=null && context.path("contractVersion").asText().equals("scenario-learning-context.v2");
        fields(task,"activities","translations","qualityPassed"); check(task.path("qualityPassed").isBoolean() && (advisory || task.path("qualityPassed").asBoolean()));
        list(task.path("activities"),3,3); list(task.path("translations"),6,18);
        var speakers = new HashSet<String>(); spec.path("characters").forEach(c -> speakers.add(c.path("id").asText()));
        var expected = new HashSet<String>(); int total = 0, ordinal = 0,questionCount=0; boolean focusCovered=false,focusMeaningCovered=false;
        for (var a : task.path("activities")) {
            if (meaning) fields(a,"id","segments","questions","assessment"); else fields(a,"id","segments","questions");
            check(text(a,"id",2).equals("a" + ++ordinal));
            list(a.path("segments"),2,6); list(a.path("questions"),meaning ? 3 : 2,meaning ? 6 : 2);
            var segments = new HashMap<String,String>(); var heardSpeakers = new HashSet<String>(); int words = 0;
            for (var segment : a.path("segments")) {
                fields(segment,"id","speakerId","textEn"); String sid = id(segment,"id");
                check(speakers.contains(id(segment,"speakerId"))); String en = text(segment,"textEn",600);
                heardSpeakers.add(segment.path("speakerId").asText());
                check(segments.put(sid,en) == null); words += (int)java.util.regex.Pattern.compile("(?U)\\S+").matcher(en).results().count();
                expected.add(a.path("id").asText() + "/" + sid);
            }
            check(heardSpeakers.equals(speakers));
            if (!flexible) check(words >= profile.path("minWordsPerActivity").asInt() && words <= profile.path("maxWordsPerActivity").asInt()); total += words;
            if (meaning) {
                meanings(a,segments,context); questionCount+=a.path("questions").size();
                for (var m : a.path("assessment").path("meanings")) focusMeaningCovered |= m.path("skill").asText().equals(context.path("focusSkill").asText());
            }
            var ids = new HashSet<String>(); var prompts=new HashSet<String>(); int questionOrdinal=0;
            for (var q : a.path("questions")) {
                if (context==null) fields(q,"id","type","skill","promptVi","answerVi","evidence");
                else {
                    if (meaning) fields(q,"id","type","skill","promptVi","answerVi","evidence","supportKind","inferenceReasonEn","meaningIds");
                    else fields(q,"id","type","skill","promptVi","answerVi","evidence","supportKind","inferenceReasonEn");
                    String support=text(q,"supportKind",16); check(Set.of("direct","synthesis","inference").contains(support));
                    check(q.path("inferenceReasonEn").isTextual() && q.path("inferenceReasonEn").asText().length()<=400);
                    boolean inferred=support.equals("inference");
                    check(inferred == q.path("skill").asText().equals("supported-inference"));
                    check(inferred ? !q.path("inferenceReasonEn").asText().isBlank()
                            && context.path("profile").path("inferencePolicy").asText().equals("SUPPORTED_INFERENCE")
                            : q.path("inferenceReasonEn").asText().isEmpty());
                    check(java.util.stream.StreamSupport.stream(context.path("profile").path("skills").spliterator(),false).anyMatch(s -> s.asText().equals(q.path("skill").asText())));
                    focusCovered |= context.path("focusSkill").asText().equals(q.path("skill").asText());
                }
                check(ids.add(id(q,"id")));
                if (meaning) check(q.path("id").asText().equals("q"+ ++questionOrdinal));
                check("MEANING_RECALL_VI".equals(text(q,"type",30)) && (context!=null ? ScenarioPersonalization.SKILLS : Set.of("detail","main-idea")).contains(text(q,"skill",30)));
                String prompt = text(q,"promptVi",300), answer = text(q,"answerVi",200);
                if (meaning) check(!normalize(prompt).isEmpty() && prompts.add(normalize(prompt)));
                check(!prompt.toLowerCase(Locale.ROOT).contains(answer.toLowerCase(Locale.ROOT)));
                list(q.path("evidence"),1,3);
                var evidenceSet=new HashSet<String>();
                for (var evidence : q.path("evidence")) {
                    fields(evidence,"segmentId","quoteEn"); String sid = id(evidence,"segmentId"), quote = text(evidence,"quoteEn",400);
                    check(segments.containsKey(sid) && segments.get(sid).contains(quote));
                    if (meaning) check(evidenceSet.add(sid+"\u0000"+quote));
                }
            }
            check(ids.equals(java.util.stream.IntStream.rangeClosed(1,a.path("questions").size()).mapToObj(i -> "q"+i).collect(java.util.stream.Collectors.toSet())));
        }
        check(context==null || focusCovered);
        if (meaning) check(focusMeaningCovered && questionCount<=context.path("profile").path("maxQuestionsPerSession").asInt());
        if (!flexible) check(total <= profile.path("maxWordsPerSession").asInt()); var actual = new HashSet<String>();
        for (var t : task.path("translations")) {
            fields(t,"activityId","segmentId","textVi"); text(t,"textVi",800);
            check(actual.add(id(t,"activityId") + "/" + id(t,"segmentId")));
        }
        check(actual.equals(expected));
    }
    private static void meanings(JsonNode activity,Map<String,String> segments,JsonNode context) {
        var assessment=activity.path("assessment"); fields(assessment,"policyVersion","meanings");
        check(text(assessment,"policyVersion",40).equals(ScenarioAssessment.POLICY)); list(assessment.path("meanings"),3,6);
        var meanings=new LinkedHashMap<String,JsonNode>(); var labels=new HashSet<String>(); int ordinal=0;
        for (var meaning : assessment.path("meanings")) {
            fields(meaning,"id","meaningEn","skill","supportKind","inferenceReasonEn","evidence");
            String id=id(meaning,"id"); check(id.equals("m"+ ++ordinal) && meanings.put(id,meaning)==null);
            String label=normalize(text(meaning,"meaningEn",300)); check(!label.isEmpty() && labels.add(label));
            String skill=text(meaning,"skill",30),support=text(meaning,"supportKind",16);
            check(Set.of("direct","synthesis","inference").contains(support));
            check(java.util.stream.StreamSupport.stream(context.path("profile").path("skills").spliterator(),false).anyMatch(s -> s.asText().equals(skill)));
            var reason=meaning.path("inferenceReasonEn"); check(reason.isTextual() && reason.asText().length()<=400);
            boolean inference=support.equals("inference"); check(inference==skill.equals("supported-inference"));
            check(inference ? !reason.asText().isBlank() && context.path("profile").path("inferencePolicy").asText().equals("SUPPORTED_INFERENCE") : reason.asText().isEmpty());
            list(meaning.path("evidence"),1,3);
            var evidenceSet=new HashSet<String>();
            for (var evidence : meaning.path("evidence")) {
                fields(evidence,"segmentId","quoteEn"); String sid=id(evidence,"segmentId"),quote=text(evidence,"quoteEn",400);
                check(segments.containsKey(sid) && segments.get(sid).contains(quote));
                check(evidenceSet.add(sid+"\u0000"+quote));
            }
        }
        var covered=new HashSet<String>();
        for (var q : activity.path("questions")) {
            list(q.path("meaningIds"),1,3); var ids=new HashSet<String>();
            for (var mid : q.path("meaningIds")) {
                check(mid.isTextual() && meanings.containsKey(mid.asText()) && ids.add(mid.asText())); covered.add(mid.asText());
                for (var e : meanings.get(mid.asText()).path("evidence")) check(java.util.stream.StreamSupport.stream(q.path("evidence").spliterator(),false).anyMatch(qe -> qe.path("segmentId").equals(e.path("segmentId")) && qe.path("quoteEn").asText().contains(e.path("quoteEn").asText())));
            }
        }
        check(covered.equals(meanings.keySet()));
    }
    private static String normalize(String text) { return text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+"," ").strip(); }
    static void grade(JsonNode result, JsonNode answers) {
        fields(result,"facts"); list(result.path("facts"),2,2);
        var byId = new HashMap<String,String>(); answers.forEach(a -> byId.put(a.path("questionId").asText(),a.path("textVi").asText()));
        var seen = new HashSet<String>();
        for (var fact : result.path("facts")) {
            fields(fact,"questionId","status","evidenceVi"); String id = id(fact,"questionId");
            check(byId.containsKey(id) && seen.add(id)); String status = text(fact,"status",16);
            check(Set.of("CAPTURED","PARTIAL","MISSED","INCORRECT","UNCERTAIN").contains(status));
            var evidence = fact.path("evidenceVi"); check(evidence.isTextual() && evidence.asText().length() <= 500);
            check(evidence.asText().isEmpty() || byId.get(id).contains(evidence.asText()));
            if (Set.of("CAPTURED","PARTIAL","INCORRECT").contains(status)) check(!evidence.asText().isBlank());
        }
        check(seen.equals(byId.keySet()));
    }
}
