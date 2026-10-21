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
            assertEquals("5000", exchange.getRequestHeaders().getFirst("X-AI-Timeout-Ms"));
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

    /** Helper: start a fake server that always returns a fixed HTTP status code. */
    private ConversationPythonClient clientWithStatus(int statusCode) throws Exception {
        ObjectMapper json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversation/tasks", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", 5);
        return new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    @Test void status400MapsToIllegalArgument() throws Exception {
        var client = clientWithStatus(400);
        assertThrows(IllegalArgumentException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
    }

    @Test void status401MapsToSecurityFailure() throws Exception {
        var client = clientWithStatus(401);
        var ex = assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("security") || ex.getMessage().contains("configuration"),
                "401 must map to security/configuration failure message");
    }

    @Test void status409MapsToIdempotencyConflict() throws Exception {
        var client = clientWithStatus(409);
        var ex = assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("idempotency"), "409 must mention idempotency");
    }

    @Test void status503MapsToUnavailable() throws Exception {
        var client = clientWithStatus(503);
        var ex = assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("unavailable"), "503 must map to unavailable");
    }

    @Test void status504MapsToUnavailable() throws Exception {
        var client = clientWithStatus(504);
        assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
    }

    @Test void missingTaskObjectInResponseIsRejected() throws Exception {
        ObjectMapper json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversation/tasks", exchange -> {
            byte[] body = "{\"task\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var config = new ConversationGenerationProperties("python", "http://127.0.0.1:11434", "qwen3.5:9b", 1,
                "http://127.0.0.1:" + server.getAddress().getPort(), "", 5);
        var client = new ConversationPythonClient(json, config, new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        assertThrows(IllegalStateException.class,
                () -> client.completeTask("A1", "{\"turnNumber\":1}", Duration.ofSeconds(5)));
    }
}
