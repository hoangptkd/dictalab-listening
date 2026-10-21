package com.dictalab.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationLevelProfileTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConversationService service = mock(ConversationService.class, CALLS_REAL_METHODS);

    @ParameterizedTest
    @CsvSource({"A1,8,35,2", "A2,18,55,3", "B1,30,90,3"})
    void generationContextSchemasAndValidationKeepExistingLimits(String level, int min, int max, int maxSlots) throws Exception {
        ReflectionTestUtils.setField(service, "json", json);
        Object row = row(level, true, false);
        Map<String, Integer> expectedBounds = Map.of("minWords", min, "maxWords", max);
        Map<?, ?> context = ReflectionTestUtils.invokeMethod(service, "promptContext", row);
        assertEquals(expectedBounds, context.get("levelProfile"));
        assertEquals(expectedBounds, ConversationGenerationPrompt.exampleInput(level).get("levelProfile"));
        for (JsonNode schema : List.of(ConversationLocalClient.schemaEnglish(level), ConversationLocalClient.schema(level))) {
            for (String field : List.of("slots", "facts")) {
                assertEquals(2, schema.path("properties").path(field).path("minItems").asInt());
                assertEquals(maxSlots, schema.path("properties").path(field).path("maxItems").asInt());
            }
        }
        validate(row, task(min, 2));
        validate(row, task(max, maxSlots));
        assertThrows(IllegalStateException.class, () -> validate(row, task(min - 1, 2)));
        assertThrows(IllegalStateException.class, () -> validate(row, task(max + 1, 2)));
        if (level.equals("A1")) assertThrows(IllegalStateException.class, () -> validate(row, task(20, 3)));
    }

    @ParameterizedTest
    @CsvSource({"A1", "A2", "B1"})
    void legacyAndWarmupBoundsRemainIndependentOfSelectedLevel(String level) throws Exception {
        ReflectionTestUtils.setField(service, "json", json);
        Object legacy = row(level, false, false);
        Map<?, ?> context = ReflectionTestUtils.invokeMethod(service, "promptContext", legacy);
        assertEquals(Map.of("minWords", 20, "maxWords", 65), context.get("levelProfile"));
        validate(legacy, task(20, 2));
        validate(legacy, task(65, 2));
        assertThrows(IllegalStateException.class, () -> validate(legacy, task(19, 2)));
        assertThrows(IllegalStateException.class, () -> validate(legacy, task(66, 2)));
        Object warmup = row(level, true, true);
        validate(warmup, task(8, 2));
        validate(warmup, task(55, 2));
        assertThrows(IllegalStateException.class, () -> validate(warmup, task(7, 2)));
        assertThrows(IllegalStateException.class, () -> validate(warmup, task(56, 2)));
    }

    @Test void higherLevelsStayUnavailableAndFewShotOrderIsStable() {
        assertEquals(List.of("A1", "A2", "B1"), ConversationLevelProfile.SUPPORTED_LEVELS);
        for (String level : List.of("B2", "C1", "C2", "", "b1")) {
            assertThrows(IllegalArgumentException.class, () -> ConversationLevelProfile.require(level));
            assertThrows(IllegalArgumentException.class, () -> ConversationGenerationPrompt.exampleInput(level));
            assertThrows(IllegalArgumentException.class, () -> ConversationLocalClient.schema(level));
            assertThrows(IllegalArgumentException.class, () -> ConversationLocalClient.schemaEnglish(level));
        }
        assertThrows(IllegalArgumentException.class, () -> ConversationLevelProfile.require(null));
        assertEquals("{\"maxWords\":90,\"minWords\":30}", json.writeValueAsString(
                ConversationGenerationPrompt.exampleInput("B1").get("levelProfile")));
    }

    private Object row(String level, boolean curated, boolean opening) throws Exception {
        var state = json.createObjectNode().put("curatedStarter", curated);
        var turns = state.putArray("turns");
        if (!opening) turns.addObject().set("prompt", ConversationContent.starter(json, "hotel", level));
        var rowClass = Arrays.stream(ConversationService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("Row")).findFirst().orElseThrow();
        var constructor = rowClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance("synthetic", 0L, "hotel", level, state, 0L);
    }

    private ObjectNode task(int words, int facts) {
        var task = json.createObjectNode().put("taskVi", "Nghe hướng dẫn.")
                .put("questionVi", "Thanh toán bằng gì và tại đâu?")
                .put("skill", ConversationContent.SKILLS.get("details"))
                .put("utteranceEn", "Pay by card at reception today please " + "wait ".repeat(words - 7).strip())
                .put("translationVi", "Hãy thanh toán bằng thẻ tại lễ tân hôm nay.");
        var answers = task.putArray("facts");
        answers.addObject().put("id", "f1").put("textVi", "Bằng thẻ").put("evidenceEn", "by card");
        answers.addObject().put("id", "f2").put("textVi", "Lễ tân").put("evidenceEn", "at reception");
        if (facts == 3) answers.addObject().put("id", "f3").put("textVi", "Hôm nay").put("evidenceEn", "today");
        return task;
    }

    private void validate(Object row, JsonNode prompt) {
        ReflectionTestUtils.invokeMethod(service, "validateNextPrompt", row, prompt);
    }
}
