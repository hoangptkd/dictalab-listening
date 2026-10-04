package com.dictalab.service;

import com.dictalab.config.ConversationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ShopAiKeyClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> content = new AtomicReference<>("{\"ok\":true}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private ShopAiKeyClient client;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content.get())))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = new ShopAiKeyClient(json, "test-only-key", "claude-sonnet-4-5",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void conversationOverrideDoesNotChangeTheAssessmentModelAndPreservesVietnamese() {
        assertThat(client.completeJson("Tạo câu nghe.", "{\"scenario\":\"Hỏi đường\"}", "gemini-2.5-flash").path("ok").asBoolean()).isTrue();
        assertThat(received.get().path("model").asText()).isEqualTo("gemini-2.5-flash");
        assertThat(received.get().path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(received.get().path("messages").path(0).path("content").asText()).isEqualTo("Tạo câu nghe.");
        assertThat(received.get().path("messages").path(1).path("content").asText()).contains("Hỏi đường");
        assertThat(received.get().path("temperature").asInt()).isZero();
        assertThat(received.get().path("max_tokens").asInt()).isEqualTo(2200);
        assertThat(authorization.get()).isEqualTo("Bearer test-only-key");
        assertThat(received.get().has("session_id")).isFalse();
        assertThat(received.get().has("conversation_id")).isFalse();
        client.completeJson("Assessment", "Transcript");
        assertThat(received.get().path("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(client.model()).isEqualTo("claude-sonnet-4-5");
    }

    @Test void fencesRemainCompatibleButEmptyOversizedOrAmbiguousOutputIsRejected() {
        content.set("```json\n{\"ok\":true}\n```");
        assertThat(client.completeJson("Instruction", "Context").path("ok").asBoolean()).isTrue();
        for (String invalid : List.of("", " ", "[]", "{\"ok\":true} {\"ok\":false}", "x".repeat(12_001))) {
            content.set(invalid);
            assertThatThrownBy(() -> client.completeJson("Instruction", "Context", "gemini-2.5-flash"))
                    .isInstanceOf(IllegalStateException.class);
        }
        status.set(429);
        assertThatThrownBy(() -> client.completeJson("Instruction", "Context"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("429");
    }

    @Test void conversationDefaultsToFlashWithoutLeakingKeys() {
        var config = new ConversationProperties(true, null, "secret-value", null, null, null);
        assertThat(config.generationModel()).isEqualTo("gemini-2.5-flash");
        assertThat(config.toString()).doesNotContain("secret-value");
    }

    @Test void missingKeyRejectsRequestsBeforeSendingAnyContext() {
        var unconfigured = new ShopAiKeyClient(json, "", "claude-sonnet-4-5",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.completeJson("Instruction", "Learner evidence"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("chưa được cấu hình");
        assertThat(received.get()).isNull();
    }

    @Test void rejectsOversizedDownloadBeforeParsingIt() {
        content.set("x".repeat(150_000));
        assertThatThrownBy(()->client.completeJson("Instruction","Context"))
                .isInstanceOf(IllegalStateException.class);
        content.set("{\"ok\":true}");
        assertThat(client.completeJson("Instruction","Context").path("ok").asBoolean()).isTrue();
    }
}
