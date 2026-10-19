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
              "slots":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                "kind":{"type":"string","minLength":1,"maxLength":64},
                "askVi":{"type":"string","minLength":1,"maxLength":100}},
                "required":["kind","askVi"],"additionalProperties":false}},
              "utteranceEn":{"type":"string","maxLength":1200},
              "facts":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                "textVi":{"type":"string","maxLength":160},"evidenceEn":{"type":"string","maxLength":300}},
                "required":["textVi","evidenceEn"],"additionalProperties":false}},
              "questionVi":{"type":"string","maxLength":180},
              "translationVi":{"type":"string","maxLength":1200},
              "taskVi":{"type":"string","maxLength":180},
              "skill":{"type":"string","enum":["Nắm thông tin cụ thể","Nắm ý chính","Hiểu lý do và mục đích","Theo dõi thay đổi","Theo dõi hướng dẫn","Hiểu điều kiện"]}},
              "required":["slots","utteranceEn","facts","questionVi","translationVi","taskVi","skill"],"additionalProperties":false}
            """;

    public record GeneratedTask(JsonNode raw) {
        JsonNode prompt() { return normalizeOutput(raw); }
        JsonNode slots() { return raw.path("slots"); }
    }

    static JsonNode normalizeOutput(JsonNode raw) {
        if (!raw.has("slots")) return raw; // Legacy/cloud responses still use the public contract.
        var slots = raw.path("slots"); var facts = raw.path("facts");
        if (!slots.isArray() || !facts.isArray() || slots.size() != facts.size()
                || slots.size() < 2 || slots.size() > 3)
            throw new IllegalStateException("Slot/fact count mismatch");
        for (var slot : slots) {
            if (!slot.isObject() || !new HashSet<>(slot.propertyNames()).equals(Set.of("kind", "askVi")))
                throw new IllegalStateException("Invalid slot fields");
            for (String field : List.of("kind", "askVi")) {
                var value = slot.path(field);
                if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > (field.equals("kind") ? 64 : 100))
                    throw new IllegalStateException("Invalid slot label");
            }
        }
        var result = (tools.jackson.databind.node.ObjectNode) raw.deepCopy();
        result.remove("slots");
        for (int i = 0; i < facts.size(); i++) {
            if (!facts.path(i).isObject() || facts.path(i).has("id"))
                throw new IllegalStateException("Unexpected generated fact ID");
            ((tools.jackson.databind.node.ObjectNode) result.path("facts").path(i)).put("id", "f" + (i + 1));
        }
        return result;
    }

    static JsonNode schema(String level) {
        if (!Set.of("A1", "A2", "B1").contains(level)) throw new IllegalArgumentException("Unsupported conversation level");
        var schema = new ObjectMapper().readTree(SCHEMA);
        if (level.equals("A1")) for (String field : List.of("slots", "facts"))
            ((tools.jackson.databind.node.ObjectNode) schema.path("properties").path(field)).put("maxItems", 2);
        return schema;
    }
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
        return completeTask(level, input, remaining).prompt();
    }

    public GeneratedTask completeTask(String level, String input, Duration remaining) {
        Duration timeout = Duration.ofMillis(Math.min(config.localTimeoutSeconds() * 1000L, remaining.toMillis()));
        if (timeout.toMillis() < 1) throw new IllegalStateException("Conversation local deadline exhausted");
        byte[] bytes = requestBody(level, input).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 64_000) throw new IllegalArgumentException("Conversation exceeds local request limit");
        return new GeneratedTask(send(bytes, timeout));
    }

    // Shared with the opt-in benchmark: measure the actual production request, not a Python reimplementation.
    String requestBody(String level, String input) {
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64_000)
            throw new IllegalArgumentException("Conversation exceeds local request limit");
        var context = ConversationGenerationPrompt.context(json, json.readTree(input));
        input = json.writeValueAsString(context);
        // shortcut: fixed 8K context; add token-aware history budgeting before large-history production rollout.
        return json.writeValueAsString(Map.of("model", config.localModel(), "stream", false, "think", false,
                "format", schema(level), "keep_alive", "5m",
                "options", Map.of("temperature", 0.3, "num_ctx", 8192, "num_predict", 1000),
                "messages", List.of(Map.of("role", "system", "content", ConversationGenerationPrompt.INSTRUCTION
                                + "\nThe first user/assistant pair is an example, not the current task. Generate for the LAST user only."),
                        Map.of("role", "user", "content", json.writeValueAsString(ConversationGenerationPrompt.exampleInput(level, context.path("turnNumber").asInt(2)))),
                        Map.of("role", "assistant", "content", ConversationGenerationPrompt.wireExample(level, context.path("turnNumber").asInt(2))),
                        Map.of("role", "user", "content", "Create the NEXT task for this input, not the example.\n<data>" + input + "</data>"
                                + ConversationGenerationPrompt.turnBrief(context)))));
    }

    private JsonNode send(byte[] bytes, Duration timeout) {
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
