package com.dictalab.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationShadowContractTest {
    @Test void shadowAndPlannedLevelsNeverEnterLearnerGeneration() {
        assertEquals(List.of("A1", "A2", "B1"), ConversationLevelProfile.SUPPORTED_LEVELS);
        for (String level : List.of("B2", "C1", "C2")) {
            assertThrows(IllegalArgumentException.class, () -> ConversationLevelProfile.require(level));
            assertThrows(IllegalArgumentException.class, () -> ConversationLocalClient.schemaEnglish(level));
            assertThrows(IllegalArgumentException.class, () -> ConversationGenerationPrompt.exampleInput(level));
        }
        assertThrows(IllegalArgumentException.class, () -> ConversationLevelProfile.requireShadow("C2"));
        assertThrows(IllegalArgumentException.class, () -> ConversationLevelProfile.requireShadow(null));
        assertThrows(IllegalArgumentException.class, () -> ConversationLocalClient.shadowSchemaEnglish("C2"));
        assertThrows(IllegalStateException.class, () -> ConversationLevelProfile.C2.wordBounds());
        assertEquals(ConversationLevelProfile.Status.PLANNED, ConversationLevelProfile.C2.status());
    }

    @Test void frozenShadowArtifactMatchesJavaProfilesSchemasAndRubric() throws Exception {
        var json = new ObjectMapper();
        var profiles = new LinkedHashMap<String, Object>();
        var schemas = new LinkedHashMap<String, Object>();
        for (String level : List.of("B2", "C1")) {
            var profile = ConversationLevelProfile.requireShadow(level);
            profiles.put(level, Map.of("status", profile.status().name(), "wordBounds", profile.wordBounds(),
                    "wordBoundsStatus", "experimental_compact_b1_comparison", "minSlots", profile.minSlots(),
                    "maxSlots", profile.maxSlots(), "taskTypes", profile.taskTypes(), "skills", profile.skills(),
                    "maxInferenceDepth", 2, "multiSpeakerAllowed", false));
            schemas.put(level, ConversationLocalClient.shadowSchemaEnglish(level));
        }
        profiles.put("C2", Map.of("status", "PLANNED", "generationAllowed", false,
                "taskTypes", ConversationLevelProfile.C2.taskTypes(), "audioCoverage", "deferred"));
        var contract = Map.of("instruction", ConversationGenerationPrompt.SHADOW_INSTRUCTION_ENGLISH,
                "reviewInstruction", ConversationGenerationPrompt.SHADOW_REVIEW_INSTRUCTION,
                "levelProfiles", profiles, "contentSchemas", schemas);
        if (Boolean.getBoolean("conversation.shadowExport")) {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/conversation-shadow-java-contract.json"), json.writeValueAsString(contract), StandardCharsets.UTF_8);
        } else {
            var artifact = json.readTree(Files.readString(Path.of("artifacts/prompts/conversation-cefr-shadow-v1-20261021.json"), StandardCharsets.UTF_8));
            for (var key : contract.keySet()) assertEquals(json.valueToTree(contract.get(key)), artifact.path(key), key);
        }
    }
}
