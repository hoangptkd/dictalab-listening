package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConversationPythonClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void sendsVersionedContractAndReturnsTask() throws Exception {
        ObjectMapper json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversation/tasks", exchange -> {
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            assertTrue(request.path("instruction").asText().contains("Generate only the NEXT task"));
            assertEquals("A1", request.path("level").asText());
            assertEquals(exchange.getRequestHeaders().getFirst("X-Request-Id"), request.path("requestId").asText());
            var response = json.writeValueAsString(Map.of("task", json.readTree(ConversationGenerationPrompt.example("A1")),
                    "model", "qwen3.5:9b", "modelDigest", "test", "promptVersion", ConversationGenerationPrompt.VERSION,
                    "generationId", "g1", "requestId", request.path("requestId").asText())).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", 5);
        var client = new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        var task = client.completeTask("A1", "{\"turnNumber\":1,\"scenario\":\"cafe\"}", Duration.ofSeconds(5));
        assertEquals("Nắm thông tin cụ thể", task.raw().path("skill").asText());
    }

    @Test void requestBodyDoesNotContainCredentials() {
        ObjectMapper json = new ObjectMapper();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:8091", "", 5);
        var client = new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        // Input intentionally contains credential-like keys that must not appear in the prompt instruction
        String input = "{\"turnNumber\":1,\"scenario\":\"cafe\","
                + "\"email\":\"user@example.com\","
                + "\"accessToken\":\"Bearer secret123\","
                + "\"cookie\":\"session=abc\"}";
        String body = client.requestBody("A1", input);
        assertFalse(body.contains("user@example.com"), "email must not appear in request body sent to Python");
        assertFalse(body.contains("Bearer secret123"), "accessToken must not appear in request body");
        assertFalse(body.contains("session=abc"), "cookie must not appear in request body");
        // The instruction and context must still carry the legitimate scenario value
        assertTrue(body.contains("cafe"), "legitimate context field must be present");
    }

    @Test void requestBodyExceedsLimitIsRejected() {
        ObjectMapper json = new ObjectMapper();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:8091", "", 5);
        var client = new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        String scenario = "x".repeat(70_000);
        String input = "{\"turnNumber\":1,\"scenario\":\"" + scenario + "\"}";
        assertThrows(IllegalArgumentException.class,
                () -> client.completeTask("A1", input, Duration.ofSeconds(5)));
    }

    @Test void deadlineExhaustedBeforeSendingRequest() {
        ObjectMapper json = new ObjectMapper();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:8091", "", 5);
        var client = new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ZERO));
    }
}
