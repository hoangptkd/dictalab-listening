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
    static boolean answerLeaked(String prompt, String answer) {
        String question=prompt.toLowerCase(Locale.ROOT).strip(), key=answer.toLowerCase(Locale.ROOT).replaceAll("^[ .!?]+|[ .!?]+$", "");
        boolean polarity=Set.of("có","không","được","không được","có thể","không thể").contains(key);
        boolean yesNo=java.util.regex.Pattern.compile("(?U)(?:\\bcó\\b.*\\bkhông|\\bcó được\\b.*|\\bliệu\\b.*|\\bđã\\b.*\\bchưa)\\s*\\?+$").matcher(question).find();
        return question.contains(key) && !(polarity && yesNo);
    }
    static void specification(JsonNode s) {
        fields(s,"allowed","titleVi","settingVi","learnerRoleVi","goalVi","characters","constraintsVi","assumptionsVi","topicIds");
        check(s.path("allowed").isBoolean()); text(s,"titleVi",120); text(s,"settingVi",400); text(s,"learnerRoleVi",100); text(s,"goalVi",300);
        list(s.path("characters"),2,2); var speakers = new HashSet<String>(); var names = new HashSet<String>();
        for (var c : s.path("characters")) { fields(c,"id","name","roleVi"); check(speakers.add(id(c,"id"))); check(names.add(text(c,"name",40).toLowerCase(Locale.ROOT))); text(c,"roleVi",100); }
        for (String field : List.of("constraintsVi","assumptionsVi")) {
            list(s.path(field),0,field.equals("constraintsVi") ? 6 : 4);
            for (var value : s.path(field)) check(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 200);
        }
        var constraints=new HashSet<String>(); s.path("constraintsVi").forEach(v -> constraints.add(v.asText().toLowerCase(Locale.ROOT).strip()));
        s.path("assumptionsVi").forEach(v -> check(!constraints.contains(v.asText().toLowerCase(Locale.ROOT).strip())));
        list(s.path("topicIds"),0,2);
        for (var topic : s.path("topicIds")) check(Set.of("food","transport","shopping","workstudy","services","social").contains(topic.asText()));
    }
    static void content(JsonNode task, JsonNode spec, JsonNode profile) {
        fields(task,"activities","translations","qualityPassed"); check(task.path("qualityPassed").isBoolean() && task.path("qualityPassed").asBoolean());
        list(task.path("activities"),3,3); list(task.path("translations"),6,18);
        var speakers = new HashSet<String>(); spec.path("characters").forEach(c -> speakers.add(c.path("id").asText()));
        var expected = new HashSet<String>(); var answerMeanings=new HashSet<String>(); int total = 0, ordinal = 0;
        for (var a : task.path("activities")) {
            fields(a,"id","segments","questions"); check(text(a,"id",2).equals("a" + ++ordinal));
            list(a.path("segments"),2,6); list(a.path("questions"),2,2);
            var segments = new HashMap<String,String>(); int words = 0;
            for (var segment : a.path("segments")) {
                fields(segment,"id","speakerId","textEn"); String sid = id(segment,"id");
                check(speakers.contains(id(segment,"speakerId"))); String en = text(segment,"textEn",600);
                check(segments.put(sid,en) == null); words += (int)java.util.regex.Pattern.compile("(?U)\\S+").matcher(en).results().count();
                expected.add(a.path("id").asText() + "/" + sid);
            }
            check(words >= profile.path("minWordsPerActivity").asInt() && words <= profile.path("maxWordsPerActivity").asInt()); total += words;
            var heardSpeakers=new HashSet<String>(); a.path("segments").forEach(s -> heardSpeakers.add(s.path("speakerId").asText()));
            check(heardSpeakers.equals(speakers));
            var ids = new HashSet<String>();
            for (var q : a.path("questions")) {
                fields(q,"id","type","skill","promptVi","answerVi","evidence"); check(ids.add(id(q,"id")));
                check("MEANING_RECALL_VI".equals(text(q,"type",30)) && Set.of("detail","main-idea").contains(text(q,"skill",30)));
                String prompt = text(q,"promptVi",300), answer = text(q,"answerVi",200);
                String meaning=java.text.Normalizer.normalize(answer,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\W+"," ").strip();
                check(!meaning.isBlank() && answerMeanings.add(meaning));
                check(!answerLeaked(prompt,answer));
                String transcript=String.join(" ",segments.values());
                for (var c : spec.path("characters")) {
                    String name="(?U)(?<!\\w)"+java.util.regex.Pattern.quote(c.path("name").asText())+"(?!\\w)";
                    if (java.util.regex.Pattern.compile(name).matcher(answer).find())
                        check(java.util.regex.Pattern.compile(name).matcher(transcript).find());
                }
                list(q.path("evidence"),1,3);
                for (var evidence : q.path("evidence")) {
                    fields(evidence,"segmentId","quoteEn"); String sid = id(evidence,"segmentId"), quote = text(evidence,"quoteEn",400);
                    check(segments.containsKey(sid) && segments.get(sid).contains(quote));
                }
            }
        }
        check(total <= profile.path("maxWordsPerSession").asInt()); var actual = new HashSet<String>();
        for (var t : task.path("translations")) {
            fields(t,"activityId","segmentId","textVi"); text(t,"textVi",800);
            check(actual.add(id(t,"activityId") + "/" + id(t,"segmentId")));
        }
        check(actual.equals(expected));
        var translated=new HashMap<String,String>(); task.path("translations").forEach(t -> translated.put(t.path("activityId").asText()+"/"+t.path("segmentId").asText(),t.path("textVi").asText()));
        var digits=java.util.regex.Pattern.compile("[0-9]+");
        for (var a : task.path("activities")) for (var s : a.path("segments")) {
            String en=s.path("textEn").asText(), vi=translated.get(a.path("id").asText()+"/"+s.path("id").asText());
            var remaining=new ArrayList<>(digits.matcher(vi).results().map(java.util.regex.MatchResult::group).toList());
            for (var number : digits.matcher(en).results().map(java.util.regex.MatchResult::group).toList()) check(remaining.remove(number));
            for (var c : spec.path("characters")) {
                var name=java.util.regex.Pattern.compile("(?U)(?<!\\w)"+java.util.regex.Pattern.quote(c.path("name").asText())+"(?!\\w)");
                if (name.matcher(en).find()) check(name.matcher(vi).find());
            }
        }
    }
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
