package com.dictalab.service;

import com.dictalab.config.ConversationGenerationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.*;

@Service
public class ConversationLocalClient implements ConversationAiClient {
    // Step 1: English-first schema — facts use textEn; no translationVi.
    static final String SCHEMA_ENGLISH = """
            {"type":"object","properties":{
              "slots":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                "kind":{"type":"string","minLength":1,"maxLength":64},
                "askVi":{"type":"string","minLength":1,"maxLength":100}},
                "required":["kind","askVi"],"additionalProperties":false}},
              "utteranceEn":{"type":"string","maxLength":1200},
              "facts":{"type":"array","minItems":2,"maxItems":3,"items":{"type":"object","properties":{
                "textEn":{"type":"string","maxLength":160},"evidenceEn":{"type":"string","maxLength":300}},
                "required":["textEn","evidenceEn"],"additionalProperties":false}},
              "questionVi":{"type":"string","maxLength":180},
              "taskVi":{"type":"string","maxLength":180},
              "skill":{"type":"string","enum":["Nắm thông tin cụ thể","Nắm ý chính","Hiểu lý do và mục đích","Theo dõi thay đổi","Theo dõi hướng dẫn","Hiểu điều kiện"]}},
              "required":["slots","utteranceEn","facts","questionVi","taskVi","skill"],"additionalProperties":false}
            """;

    // Step 2: Translation schema — receives utteranceEn + English facts, returns Vietnamese translations.
    static final String SCHEMA_TRANSLATE = """
            {"type":"object","properties":{
              "translationVi":{"type":"string","maxLength":1200},
              "facts":{"type":"array","minItems":1,"maxItems":3,"items":{"type":"object","properties":{
                "textVi":{"type":"string","maxLength":160}},
                "required":["textVi"],"additionalProperties":false}}},
              "required":["translationVi","facts"],"additionalProperties":false}
            """;

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
        var result = (ObjectNode) raw.deepCopy();
        result.remove("slots");
        for (int i = 0; i < facts.size(); i++) {
            var fact = result.path("facts").path(i);
            if (!fact.isObject() || fact.has("id"))
                throw new IllegalStateException("Unexpected generated fact ID");
            ((ObjectNode) fact).put("id", "f" + (i + 1));
        }
        return result;
    }

    static JsonNode schemaEnglish(String level) {
        return schemaFor(SCHEMA_ENGLISH, level);
    }

    static JsonNode schemaTranslate(int factCount) {
        var schema = new ObjectMapper().readTree(SCHEMA_TRANSLATE);
        ((ObjectNode) schema.path("properties").path("facts")).put("minItems", factCount).put("maxItems", factCount);
        return schema;
    }

    static JsonNode schema(String level) {
        return schemaFor(SCHEMA, level);
    }

    private static JsonNode schemaFor(String template, String level) {
        return schemaFor(template, ConversationLevelProfile.require(level));
    }

    // Offline contract export only; normal generation still rejects shadow levels.
    static JsonNode shadowSchemaEnglish(String level) {
        return schemaFor(SCHEMA_ENGLISH, ConversationLevelProfile.requireShadow(level));
    }

    private static JsonNode schemaFor(String template, ConversationLevelProfile profile) {
        var mapper = new ObjectMapper();
        var schema = mapper.readTree(template);
        for (String field : List.of("slots", "facts"))
            ((ObjectNode) schema.path("properties").path(field)).put("minItems", profile.minSlots()).put("maxItems", profile.maxSlots());
        ((ObjectNode) schema.path("properties").path("skill")).set("enum", mapper.valueToTree(profile.skills().values()));
        var fact = (ObjectNode) schema.path("properties").path("facts").path("items");
        ((ObjectNode) fact.path("properties")).set("taskType", mapper.valueToTree(Map.of("type", "string", "enum", profile.taskTypes())));
        if (profile == ConversationLevelProfile.B1 || profile.status() == ConversationLevelProfile.Status.SHADOW) {
            fact.withArray("required").add("taskType");
            ((ObjectNode) fact.path("properties")).set("reasoningEvidence", mapper.readTree("""
                    {"type":"object","properties":{
                      "segmentsEn":{"type":"array","minItems":1,"maxItems":4,"uniqueItems":true,
                        "items":{"type":"string","minLength":1,"maxLength":300}},
                      "rationaleEn":{"type":"string","minLength":1,"maxLength":300}},
                      "required":["segmentsEn","rationaleEn"],"additionalProperties":false}
                    """));
        }
        return schema;
    }

    private final AiTextService text;
    private final ObjectMapper json;
    private final ConversationGenerationProperties config;
    private final ProviderGuard guard;
    private final MeterRegistry metrics;

    public ConversationLocalClient(ObjectMapper json, ConversationGenerationProperties config, MeterRegistry metrics,
                                   io.micrometer.observation.ObservationRegistry observations) {
        this(json, config, metrics, observations, new AiTextService(json, "", "unused", "http://127.0.0.1/unused"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationLocalClient(ObjectMapper json, ConversationGenerationProperties config, MeterRegistry metrics,
                                   io.micrometer.observation.ObservationRegistry observations, AiTextService text) {
        this.text = text;
        this.json = json; this.config = config; this.metrics = metrics;
        guard = new ProviderGuard("conversation-local", 0, metrics, observations, 5, Duration.ofSeconds(30));
    }

    public boolean enabled() { return config.local(); }

    public int flowTimeoutSeconds() { return config.localFlowTimeoutSeconds(); }

    public JsonNode completeJson(String level, String input, Duration remaining) {
        return completeTask(level, input, remaining).prompt();
    }

    public GeneratedTask completeTask(String level, String input, Duration remaining) {
        long deadline = System.nanoTime() + remaining.toNanos();
        Duration timeout = Duration.ofMillis(Math.min(config.localTimeoutSeconds() * 1000L, remaining.toMillis()));
        if (timeout.toMillis() < 1) throw new IllegalStateException("Conversation local deadline exhausted");
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64_000)
            throw new IllegalArgumentException("Conversation exceeds local request limit");
        var context = json.readTree(ConversationGenerationPrompt.context(json, json.readTree(input)).toString());

        // Step 1: generate English content (utteranceEn, English facts, questionVi, taskVi, skill).
        byte[] englishBytes = requestBodyEnglish(level, input, context).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (englishBytes.length > 64_000) throw new IllegalArgumentException("Conversation exceeds local request limit");
        var englishRaw = send(englishRequest(level, input, context, timeout));

        // Step 2: translate utteranceEn and English facts into Vietnamese at lower temperature.
        Duration remaining2 = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
        Duration translateTimeout = Duration.ofMillis(Math.min(config.localTimeoutSeconds() * 1000L, remaining2.toMillis()));
        if (translateTimeout.toMillis() < 1) throw new AiTextService.Failure("TIMEOUT", "Conversation translation deadline exhausted");
        byte[] translateBytes = requestBodyTranslate(englishRaw, context).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (translateBytes.length > 64_000) throw new IllegalArgumentException("Translation exceeds local request limit");
        var translated = send(translationRequest(englishRaw, context, translateTimeout));

        // Combine: merge translationVi + facts[].textVi into the English output.
        var combined = combine(englishRaw, translated, json);
        return new GeneratedTask(combined);
    }

    /** Merge Vietnamese translations back into the English-first output. */
    static JsonNode combine(JsonNode english, JsonNode translated, ObjectMapper mapper) {
        var englishFacts = english.path("facts");
        var translatedFacts = translated.path("facts");
        if (!translatedFacts.isArray() || translatedFacts.size() != englishFacts.size())
            throw new IllegalStateException("Translation changed fact count");
        var result = (ObjectNode) english.deepCopy();
        result.put("translationVi", translated.path("translationVi").asText());
        var mergedFacts = result.putArray("facts");
        for (int i = 0; i < englishFacts.size(); i++) {
            var merged = ((ObjectNode) englishFacts.path(i)).deepCopy();
            merged.remove("textEn");
            merged.put("textVi", translatedFacts.path(i).path("textVi").asText());
            mergedFacts.add(merged);
        }
        return result;
    }

    // Benchmark exports use the same neutral requests as production.
    String requestBodyEnglish(String level, String input, JsonNode context) {
        return preview(englishRequest(level, input, context, Duration.ofSeconds(config.localTimeoutSeconds())));
    }

    private AiTextService.Request englishRequest(String level, String input, JsonNode context, Duration timeout) {
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64000)
            throw new IllegalArgumentException("Conversation exceeds local request limit");
        int turn = context.path("turnNumber").asInt(2);
        return request("conversation_content", schemaEnglish(level), 0.3, 8192, 1000, timeout, List.of(
            new AiTextService.Message("system", ConversationGenerationPrompt.INSTRUCTION_ENGLISH
                + "\nThe first user/assistant pair is an example, not the current task. Generate for the LAST user only."),
            new AiTextService.Message("user", json.writeValueAsString(ConversationGenerationPrompt.exampleInput(level, turn))),
            new AiTextService.Message("assistant", ConversationGenerationPrompt.wireExampleEnglish(level, turn)),
            new AiTextService.Message("user", "Create the NEXT task for this input, not the example.\n<data>"
                + json.writeValueAsString(context) + "</data>" + ConversationGenerationPrompt.turnBrief(context))));
    }

    String requestBody(String level, String input) {
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64000)
            throw new IllegalArgumentException("Conversation exceeds local request limit");
        var context = ConversationGenerationPrompt.context(json, json.readTree(input));
        int turn = context.path("turnNumber").asInt(2);
        return preview(request("conversation_generate", schema(level), 0.3, 8192, 1000,
            Duration.ofSeconds(config.localTimeoutSeconds()), List.of(
                new AiTextService.Message("system", ConversationGenerationPrompt.INSTRUCTION
                    + "\nThe first user/assistant pair is an example, not the current task. Generate for the LAST user only."),
                new AiTextService.Message("user", json.writeValueAsString(ConversationGenerationPrompt.exampleInput(level, turn))),
                new AiTextService.Message("assistant", ConversationGenerationPrompt.wireExample(level, turn)),
                new AiTextService.Message("user", "Create the NEXT task for this input, not the example.\n<data>"
                    + json.writeValueAsString(context) + "</data>" + ConversationGenerationPrompt.turnBrief(context)))));
    }

    String requestBodyTranslate(JsonNode english, JsonNode context) {
        return preview(translationRequest(english, context, Duration.ofSeconds(config.localTimeoutSeconds())));
    }

    private AiTextService.Request translationRequest(JsonNode english, JsonNode context, Duration timeout) {
        var data = json.createObjectNode().put("partnerRole", context.path("partnerRole").asText())
            .put("learnerRole", context.path("learnerRole").asText()).put("utteranceEn", english.path("utteranceEn").asText())
            .put("questionVi", english.path("questionVi").asText());
        var facts = data.putArray("facts");
        for (var fact : english.path("facts")) facts.addObject().put("textEn", fact.path("textEn").asText());
        return request("conversation_translation", schemaTranslate(english.path("facts").size()), 0.1, 4096, 800, timeout,
            List.of(new AiTextService.Message("system", ConversationGenerationPrompt.TRANSLATE),
                    new AiTextService.Message("user", json.writeValueAsString(data))));
    }

    private AiTextService.Request request(String purpose, JsonNode schema, double temperature, int context, int tokens,
                                          Duration timeout, List<AiTextService.Message> messages) {
        return new AiTextService.Request(UUID.randomUUID(), purpose, "conversation_local", config.localModel(), messages,
            schema, "json_schema", temperature, tokens, timeout, context, "5m");
    }

    private String preview(AiTextService.Request request) {
        return json.writeValueAsString(text.previewLocal(request, config.localBaseUrl()));
    }

    private JsonNode send(AiTextService.Request request) {
        try (var permit = guard.enter()) {
            try {
                var result = text.completeLocal(request, config.localBaseUrl());
                metrics.summary("dictalab.conversation.local.tokens", "kind", "input").record(result.usage().path("prompt_eval_count").asLong());
                metrics.summary("dictalab.conversation.local.tokens", "kind", "output").record(result.usage().path("eval_count").asLong());
                permit.success();
                return result.value();
            } catch (RuntimeException failed) { permit.failure(); throw failed; }
        }
    }
}
