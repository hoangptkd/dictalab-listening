package com.dictalab.service;

import com.dictalab.config.AiPlatformProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="ai.jobs.smoke.url", matches="http://127\\.0\\.0\\.1:[0-9]+")
class AiPlatformSmokeTest {
    @Test void javaCallsRealApiRabbitWorkerAndPreservesEvidenceAndWav() {
        var config = new AiPlatformProperties(System.getProperty("ai.jobs.smoke.url"), "smoke-only-token",
                System.getProperty("ai.jobs.smoke.provider"), 30, "python", "python", "python", Set.of());
        var client = new AiPlatformClient(new ObjectMapper(), config, new SimpleMeterRegistry());
        var payload = Map.of("kind", "coach", "level", "A1", "candidates", List.of(Map.of("id", "1:0",
                "pattern", "KNOWN_MISSED", "heard", "", "expected", "nine", "sentence", "At nine.", "priority", 90, "occurrences", 1)));
        var first = client.complete("coach", 42, "smoke-coach", payload);
        var duplicate = client.complete("coach", 42, "smoke-coach", payload);
        assertEquals(first.path("generationId").asText(), duplicate.path("generationId").asText());
        assertEquals("1:0", first.path("task").path("focus").path("evidenceIds").path(0).asText());
        var review = client.complete("review", 42, "smoke-review", Map.of("kind", "review", "recall", "nine",
                "facts", List.of(Map.of("id", "F1", "text", "nine"))));
        assertEquals("F1", review.path("task").path("facts").path(0).path("factId").asText());
        assertFalse(review.path("task").has("score"));
        var audio = client.audio(client.complete("tts", 42, "smoke-tts", Map.of("kind", "tts", "text", "At nine.")));
        assertTrue(audio.length > 44);
    }
}
