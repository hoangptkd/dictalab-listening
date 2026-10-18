package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationGenerationPromptTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void eachLevelGetsOnlyItsGroundedExample() {
        for (String level : List.of("A1", "A2", "B1")) {
            var example = json.readTree(ConversationGenerationPrompt.example(level));
            String speech = example.path("utteranceEn").asText();
            var bounds = switch (level) {
                case "A1" -> Map.of("minWords", 8, "maxWords", 35);
                case "A2" -> Map.of("minWords", 18, "maxWords", 55);
                default -> Map.of("minWords", 30, "maxWords", 90);
            };
            assertTrue(speech.split("\\s+").length >= bounds.get("minWords"));
            assertTrue(speech.split("\\s+").length <= bounds.get("maxWords"));
            assertEquals(2, example.path("facts").size());
            assertTrue(ConversationContent.SKILLS.containsValue(example.path("skill").asText()));
            assertEquals(level, ConversationGenerationPrompt.exampleInput(level).get("difficulty"));
            example.path("facts").forEach(f -> assertTrue(speech.contains(f.path("evidenceEn").asText())));
            String instruction = ConversationGenerationPrompt.instruction(level);
            assertTrue(instruction.contains(ConversationGenerationPrompt.example(level)));
            for (String other : List.of("A1", "A2", "B1")) if (!level.equals(other))
                assertFalse(instruction.contains(ConversationGenerationPrompt.example(other)));
        }
    }

    @Test void contractDefinesInputsAtomicSlotsAndUnambiguousProgression() {
        String instruction = ConversationGenerationPrompt.instruction("A1");
        for (String field : List.of("scenario", "difficulty", "turnNumber", "levelProfile", "listeningSkills",
                "scenarioJourney", "history", "practiceFocus", "sessionBlueprint", "recentPractice",
                "validationError", "previousInvalidPrompt")) assertTrue(instruction.contains(field));
        assertTrue(instruction.contains("One fact = exactly one slot"));
        assertTrue(instruction.contains("Turn 4 is a NEW encounter within the SAME scenario category"));
        assertTrue(instruction.contains("Both taskVi and questionVi are shown BEFORE listening"));
        assertFalse(instruction.contains("Count utteranceEn words before returning"));
        assertThrows(IllegalArgumentException.class, () -> ConversationGenerationPrompt.instruction("B2"));
    }

    @Test void copiedFewShotOutputCannotBePublishedAsANewTask() throws Exception {
        var service=org.mockito.Mockito.mock(ConversationService.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"json",json);
        var rowClass=Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor=rowClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        for (String level : List.of("A1","A2","B1")) {
            var state=json.createObjectNode().put("curatedStarter",true); state.putArray("turns");
            var row=constructor.newInstance("synthetic",0L,"hotel",level,state,0L);
            var failure=assertThrows(IllegalStateException.class, () -> org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(service,"validateNextPrompt",row,json.readTree(ConversationGenerationPrompt.example(level))));
            assertEquals("Copied generation example",failure.getMessage());
        }
    }
}
