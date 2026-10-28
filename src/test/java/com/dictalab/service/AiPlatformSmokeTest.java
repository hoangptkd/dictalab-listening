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
        var config = new AiPlatformProperties(System.getProperty("ai.jobs.smoke.url"), "smoke-only-token-at-least-32-characters",
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
        String id=UUID.randomUUID().toString();
        String scene=System.getProperty("ai.jobs.smoke.scene", "cafe");
        var demoPayload=new ObjectMapper().valueToTree(Map.of("kind","conversation_demo","mode","compare","variant","full07",
                "allowFallback",false,"level","A1","context",Map.of("scenario",scene,"turnNumber",1)));
        var state=client.submitDemo(42,id,java.time.Instant.now().plusSeconds(60),demoPayload);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (Set.of("PENDING","RUNNING").contains(state.path("status").asText())) {
            if(System.nanoTime()>deadline) fail("Demo smoke deadline exceeded");
            try { Thread.sleep(100); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            state=client.demoStatus(42,id);
        }
        assertEquals("DONE",state.path("status").asText());
        ModelDemoService.validateResult(demoPayload,state.path("result"));
        for(var arm:state.path("result").path("arms")) {
            assertTrue(arm.path("valid").asBoolean(),arm.toString());
            assertEquals(scene.equals("delivery") ? "source-guided-r13" : "legacy",arm.path("translationGuidance").asText());
            assertEquals(scene.equals("delivery") ? 23 : 11,arm.path("wordCount").asInt());
            assertEquals(35,arm.path("wordBounds").path("maxWords").asInt());
            assertEquals("not_evaluated",arm.path("semanticReview").asText());
        }
        var repeated=client.submitDemo(42,id,java.time.Instant.now().plusSeconds(60),demoPayload);
        assertEquals(state.path("result").path("generationId"),repeated.path("result").path("generationId"));
    }
}
