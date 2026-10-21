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
                System.getProperty("ai.platform.smoke.url"), "smoke-only-token", 10);
        var client = new ConversationPythonClient(new ObjectMapper(), config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        var task = client.completeTask("A1", "{\"turnNumber\":1,\"scenario\":\"hotel\"}", Duration.ofSeconds(10));
        assertEquals("Meet at nine in the lobby.", task.raw().path("utteranceEn").asText());
        assertEquals("at nine", task.raw().path("facts").path(0).path("evidenceEn").asText());
        assertEquals("nine", task.raw().path("facts").path(0).path("textVi").asText());
    }
}
