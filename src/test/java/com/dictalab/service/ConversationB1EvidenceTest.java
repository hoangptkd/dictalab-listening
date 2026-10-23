package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationB1EvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode inferred() {
        return (ObjectNode) json.readTree("""
                {"taskVi":"Nghe và suy ra kế hoạch.","questionVi":"Người nói có thể làm gì tiếp theo và cần mang gì?",
                 "skill":"Suy luận và dự đoán có căn cứ",
                 "utteranceEn":"My morning train has been cancelled. The next train leaves at noon, and my ticket is valid for that service too. I still need to reach the city today. Please bring your notebook for our meeting.",
                 "translationVi":"Chuyến tàu sáng của tôi đã bị hủy. Chuyến tiếp theo khởi hành lúc trưa và vé của tôi cũng dùng được cho chuyến đó. Tôi vẫn cần đến thành phố hôm nay. Xin mang sổ cho cuộc họp.",
                 "facts":[{"id":"f1","textVi":"Có thể đi chuyến tàu lúc trưa","evidenceEn":"My morning train has been cancelled.",
                    "taskType":"inference_prediction","reasoningEvidence":{
                    "segmentsEn":["My morning train has been cancelled.","The next train leaves at noon, and my ticket is valid for that service too.","I still need to reach the city today."],
                    "rationaleEn":"The cancelled morning service, valid ticket for the next train and need to arrive today support taking the noon service."}},
                    {"id":"f2","textVi":"Sổ ghi chép","evidenceEn":"your notebook","taskType":"direct_detail"}]}
                """);
    }

    private void validate(JsonNode task, String level) throws Exception {
        var service = mock(ConversationService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "json", json);
        var state = json.createObjectNode().put("curatedStarter", true);
        state.putArray("turns").addObject().set("prompt", ConversationContent.starter(json, "hotel", level));
        var rowClass = Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor = rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object row = constructor.newInstance("synthetic", 0L, "hotel", level, state, 0L);
        ReflectionTestUtils.invokeMethod(service, "validateNextPrompt", row, task);
        ((ObjectNode) state.path("turns").path(0)).set("prompt", task);
        var view = (JsonNode) ReflectionTestUtils.invokeMethod(service, "view", row);
        assertFalse(view.toString().contains("reasoningEvidence"));
    }

    @Test void groundedB1ConclusionSurvivesTranslationAndDoesNotHaveToBeVerbatim() throws Exception {
        var task = inferred();
        validate(task, "B1");
        var english = (ObjectNode) task.deepCopy();
        english.remove("translationVi");
        int index = 0;
        for (var fact : english.path("facts")) {
            var entry = (ObjectNode) fact;
            entry.remove("id"); entry.remove("textVi"); entry.put("textEn", index++ == 0 ? "Take the noon train" : "Your notebook");
        }
        var translated = json.readTree("""
                {"translationVi":"Bản dịch.","facts":[{"textVi":"Có thể đi chuyến tàu trưa"},{"textVi":"Sổ ghi chép"}]}
                """);
        var merged = ConversationLocalClient.combine(english, translated, json);
        assertEquals(task.path("facts").path(0).path("reasoningEvidence"), merged.path("facts").path(0).path("reasoningEvidence"));
        assertFalse(merged.toString().contains("textEn"));
        validate(ConversationLocalClient.normalizeOutput(withSlots(merged)), "B1");
        assertFalse(task.path("utteranceEn").asText().contains("Take the noon train"));
    }

    private JsonNode withSlots(JsonNode task) {
        var result = (ObjectNode) task.deepCopy();
        var slots = result.putArray("slots");
        slots.addObject().put("kind", "next_action").put("askVi", "Việc tiếp theo");
        slots.addObject().put("kind", "bring_item").put("askVi", "Vật cần mang");
        return result;
    }

    @Test void fabricatedMissingDuplicateAndUnlinkedEvidenceAreRejected() {
        for (String defect : List.of("missing", "fabricated", "duplicate", "empty", "rationale", "primary")) {
            var task = inferred(); var fact = (ObjectNode) task.path("facts").path(0);
            var reasoning = (ObjectNode) fact.path("reasoningEvidence");
            switch (defect) {
                case "missing" -> fact.remove("reasoningEvidence");
                case "fabricated" -> reasoning.withArray("segmentsEn").add("The speaker bought a bus ticket.");
                case "duplicate" -> reasoning.withArray("segmentsEn").add(reasoning.path("segmentsEn").path(0));
                case "empty" -> reasoning.putArray("segmentsEn");
                case "rationale" -> reasoning.put("rationaleEn", " ");
                case "primary" -> reasoning.withArray("segmentsEn").remove(0);
            }
            assertThrows(IllegalStateException.class, () -> validate(task, "B1"), defect);
        }
    }

    @Test void oneSegmentMayContainMultipleCuesAndLowerLevelsCannotUseInference() throws Exception {
        var task = inferred(); var fact = (ObjectNode) task.path("facts").path(0);
        fact.put("evidenceEn", "morning train has been cancelled");
        validate(task, "B1");
        String quote = task.path("utteranceEn").asText();
        fact.put("evidenceEn", quote);
        ((ObjectNode) fact.path("reasoningEvidence")).putArray("segmentsEn").add(quote);
        validate(task, "B1");
        for (String level : List.of("A1", "A2")) assertThrows(IllegalStateException.class, () -> validate(task, level));
    }

    @Test void gistAndClearlyExpressedAttitudeAreNotForcedIntoDirectFacts() throws Exception {
        for (String type : List.of("gist", "opinion_attitude")) {
            var task = inferred(); task.put("skill", ConversationContent.SKILLS.get(type.equals("gist") ? "gist" : "attitude"));
            task.put("utteranceEn", "I am delighted with the new train service. The seats are comfortable and I can work during the journey. I think it is a great improvement. Please bring your notebook for our meeting in the city.");
            task.put("questionVi", type.equals("gist") ? "Thông điệp chính là gì và cần mang gì?" : "Người nói nghĩ gì về chuyến tàu mới và cần mang gì?");
            var fact = (ObjectNode) task.path("facts").path(0);
            fact.put("taskType", type); fact.remove("reasoningEvidence");
            fact.put("evidenceEn", "I am delighted with the new train service.");
            fact.put("textVi", type.equals("gist") ? "Khen dịch vụ tàu mới đã cải thiện trải nghiệm" : "Hài lòng với dịch vụ tàu mới");
            validate(task, "B1");
        }
    }

    @Test void b1AllowsAtMostOneInferenceTarget() {
        var task = inferred(); var second = (ObjectNode) task.path("facts").path(1);
        second.put("taskType", "inference_prediction");
        second.putObject("reasoningEvidence").put("rationaleEn", "Bring the notebook.")
                .putArray("segmentsEn").add("your notebook");
        var error = assertThrows(IllegalStateException.class, () -> validate(task, "B1"));
        assertEquals("At most one B1 inference target", error.getMessage());
    }

    @Test void registeredCefrCandidateMatchesTheJavaGenerationContract() throws Exception {
        var profiles = new LinkedHashMap<String, Object>();
        var examples = new ArrayList<Object>();
        var schemas = new LinkedHashMap<String, Object>();
        for (String level : ConversationLevelProfile.SUPPORTED_LEVELS) {
            var profile = ConversationLevelProfile.require(level);
            profiles.put(level, Map.of("wordBounds", profile.wordBounds(), "minSlots", profile.minSlots(),
                    "maxSlots", profile.maxSlots(), "taskTypes", profile.taskTypes(), "skills", profile.skills()));
            schemas.put(level, ConversationLocalClient.schemaEnglish(level));
            for (int turn : List.of(2, 4)) examples.add(Map.of("input", ConversationGenerationPrompt.exampleInput(level, turn),
                    "output", json.readTree(ConversationGenerationPrompt.wireExampleEnglish(level, turn))));
        }
        var contract = Map.of("instruction", ConversationGenerationPrompt.INSTRUCTION_ENGLISH,
                "contentSchema", ConversationLocalClient.schemaEnglish("B1"), "translationSchema", json.readTree(ConversationLocalClient.SCHEMA_TRANSLATE),
                "contentSchemas", schemas, "translationSystem", ConversationGenerationPrompt.TRANSLATE,
                "fewShotExamples", examples, "levelProfiles", profiles);
        if (Boolean.getBoolean("conversation.cefrExport")) {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/conversation-cefr-java-contract.json"), json.writeValueAsString(contract), StandardCharsets.UTF_8);
        } else {
            var artifact = json.readTree(Files.readString(Path.of("artifacts/prompts/conversation-cefr-v8-20261022.json"), StandardCharsets.UTF_8));
            for (var key : contract.keySet()) assertEquals(json.valueToTree(contract.get(key)), artifact.path(key), key);
        }
    }
}
