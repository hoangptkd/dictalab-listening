package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ConversationLocalClient {
    static final String SCHEMA = """
            {"type":"object","properties":{
              "taskVi":{"type":"string","maxLength":180},
              "questionVi":{"type":"string","maxLength":180},
              "utteranceEn":{"type":"string","maxLength":1200},
              "translationVi":{"type":"string","maxLength":1200},
              "skill":{"type":"string","enum":["Nắm thông tin cụ thể","Nắm ý chính","Hiểu lý do và mục đích","Theo dõi thay đổi","Theo dõi hướng dẫn","Hiểu điều kiện"]},
              "facts":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                "id":{"type":"string","enum":["f1","f2","f3"]},
                "textVi":{"type":"string","maxLength":160},"evidenceEn":{"type":"string","maxLength":300}},
                "required":["id","textVi","evidenceEn"],"additionalProperties":false}}},
              "required":["taskVi","questionVi","utteranceEn","translationVi","skill","facts"],"additionalProperties":false}
            """;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final ConversationGenerationProperties config;
    private final ProviderGuard guard;
    private final MeterRegistry metrics;

    public ConversationLocalClient(ObjectMapper json, ConversationGenerationProperties config, MeterRegistry metrics,
                                   io.micrometer.observation.ObservationRegistry observations) {
        this.json = json; this.config = config; this.metrics = metrics;
        guard = new ProviderGuard("conversation-local", 1, metrics, observations, 5, Duration.ofSeconds(30));
    }

    public boolean enabled() { return config.local(); }

    public int flowTimeoutSeconds() { return config.localFlowTimeoutSeconds(); }

    public JsonNode completeJson(String level, String input, Duration remaining) {
        Duration timeout = Duration.ofMillis(Math.min(config.localTimeoutSeconds() * 1000L, remaining.toMillis()));
        if (timeout.toMillis() < 1) throw new IllegalStateException("Conversation local deadline exhausted");
        // shortcut: fixed 8K context; add token-aware history budgeting before large-history production rollout.
        String body = json.writeValueAsString(Map.of("model", config.localModel(), "stream", false, "think", false,
                "format", json.readTree(SCHEMA), "keep_alive", "5m",
                "options", Map.of("temperature", 0.7, "num_ctx", 8192, "num_predict", 1000),
                "messages", List.of(Map.of("role", "system", "content", ConversationGenerationPrompt.INSTRUCTION
                                + "\nThe first user/assistant pair is an example, not the current task. Generate for the LAST user only."),
                        Map.of("role", "user", "content", json.writeValueAsString(ConversationGenerationPrompt.exampleInput(level))),
                        Map.of("role", "assistant", "content", ConversationGenerationPrompt.example(level)),
                        Map.of("role", "user", "content", "Create the NEXT task for this input, not the example.\n<data>" + input + "</data>"))));
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 64_000) throw new IllegalArgumentException("Conversation exceeds local request limit");
        try (var permit = guard.enter()) {
            var request = HttpRequest.newBuilder(URI.create(config.localBaseUrl() + "/api/chat"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            var pending = http.sendAsync(request, ignored -> BoundedProviderBody.bytes(24_000));
            try {
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response.statusCode() != 200) throw new IllegalStateException("Local conversation HTTP " + response.statusCode());
                var envelope = json.readTree(response.body());
                var content = envelope.path("message").path("content");
                if (!envelope.path("done").asBoolean() || !"stop".equals(envelope.path("done_reason").asText())
                        || !content.isTextual() || content.asText().isBlank())
                    throw new IllegalStateException("Incomplete local conversation response");
                var result = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(content.asText());
                if (!result.isObject()) throw new IllegalStateException("Local conversation object missing");
                metrics.summary("dictalab.conversation.local.tokens", "kind", "input").record(envelope.path("prompt_eval_count").asLong());
                metrics.summary("dictalab.conversation.local.tokens", "kind", "output").record(envelope.path("eval_count").asLong());
                permit.success();
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local conversation interrupted");
            } catch (ExecutionException | TimeoutException failed) {
                permit.failure();
                throw new IllegalStateException("Local conversation transport or deadline failure");
            } catch (RuntimeException failed) {
                permit.failure(); throw failed;
            } finally {
                pending.cancel(true);
            }
        }
    }
}
