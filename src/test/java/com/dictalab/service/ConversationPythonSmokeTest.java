package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "ai.platform.smoke.url", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class ConversationPythonSmokeTest {
    @Test void javaCallsRealPythonServiceAndFakeOllama() {
        var config = new ConversationGenerationProperties("python", null, null, 10,
                System.getProperty("ai.platform.smoke.url"), "smoke-only-token-at-least-32-characters", 10);
        var client = new ConversationPythonClient(new ObjectMapper(), config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        var task = client.completeTask("A1", "{\"turnNumber\":1,\"scenario\":\"hotel\"}", Duration.ofSeconds(10));
        assertEquals("Please meet at nine in the main lobby.", task.raw().path("utteranceEn").asText());
        assertEquals("at nine", task.raw().path("facts").path(0).path("evidenceEn").asText());
        assertEquals("nine", task.raw().path("facts").path(0).path("textVi").asText());
    }

    @Test void coreDelegatesGenericTextWithoutCoreRegistryAccess() {
        var json = new ObjectMapper();
        var config = new com.dictalab.config.AiPlatformProperties(System.getProperty("ai.platform.smoke.url"),
                "smoke-only-token-at-least-32-characters", "ollama", 10,
                "legacy", "legacy", "legacy", java.util.Set.of());
        var platform = new AiPlatformClient(json, config, new SimpleMeterRegistry());
        var core = new GeminiClient(json, "", "unused", "http://127.0.0.1:1");
        var registry = org.mockito.Mockito.mock(AiProviderRegistry.class);
        core.configureRegistry(registry);
        core.configureLocal("ollama", "http://127.0.0.1:1", "qwen3.5:9b");
        core.configureTextTransport(platform, "python", "qwen3.5:9b");
        assertTrue(core.completeJson("Return JSON only.", "Synthetic evidence").path("ok").asBoolean());
        org.mockito.Mockito.verifyNoInteractions(registry);
    }
}
