package com.dictalab.service;

import com.dictalab.config.CoachProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatGptWebCoachClientTest {
    @Test
    void sendsBridgeCompatibleRequestAndRejectsFreeTextOrTrailingJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> content = new AtomicReference<>("{\"weaknesses\":[]}");
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = json.writeValueAsBytes(Map.of("choices", java.util.List.of(Map.of("message", Map.of("content", content.get())))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new ChatGptWebCoachClient(json, new CoachProperties(true,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"), "chatgpt-web", "", Duration.ofSeconds(2)));
            assertTrue(client.interpret(Map.of("candidates", java.util.List.of())).path("weaknesses").isArray());
            var sent = json.readTree(request.get());
            assertEquals("chatgpt-web", sent.path("model").asText());
            assertFalse(sent.path("stream").asBoolean());
            assertFalse(sent.has("conversation_id"));
            assertFalse(sent.has("response_format"));
            assertTrue(sent.path("messages").path(0).path("content").asText().contains("EVIDENCE_JSON"));
            content.set("Here is a long report...");
            assertThrows(RuntimeException.class, () -> client.interpret(Map.of()));
            content.set("{} {}");
            assertThrows(RuntimeException.class, () -> client.interpret(Map.of()));
            content.set("```json\n{}\n```");
            assertThrows(RuntimeException.class, () -> client.interpret(Map.of()));
        } finally { server.stop(0); }
    }
}
