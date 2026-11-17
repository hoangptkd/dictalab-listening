package com.dictalab.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.dictalab.config.AiTextProperties.ModelProfile;
import java.net.URI;
import java.util.Map;

@Component
public class OpenAiCompatibleTextProvider implements AiTextProvider {
    public String id() { return "openai_compatible"; }
    protected boolean prefixJsonInstruction() { return true; }
    protected String expectedHost() { return null; }
    public void validateTarget(Target target) {
        AiTextProvider.super.validateTarget(target);
        var uri = URI.create(target.url());
        if (!uri.getPath().endsWith("/chat/completions")) throw new IllegalArgumentException("Invalid chat endpoint");
        if (expectedHost() != null && !java.util.Set.of(expectedHost(), "localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))
            throw new IllegalArgumentException("AI endpoint does not match provider");
    }
    public JsonNode payload(ObjectMapper json, Target target, AiTextService.Request request, ModelProfile profile) {
        String mode = request.outputMode();
        if (!profile.jsonModes().contains(mode)) throw new AiTextService.Failure("UNSUPPORTED_CAPABILITY", "Unsupported JSON mode");
        var result = json.createObjectNode().put("model", target.model()).put(profile.tokenField(), request.maxOutputTokens());
        if (profile.temperaturePolicy().equals("send") && request.temperature() != null) result.put("temperature", request.temperature());
        var messages = result.putArray("messages");
        if (mode.equals("json_schema") && profile.schemaMode().equals("prompt"))
            messages.addObject().put("role", "system").put("content", "Return one JSON object matching this JSON schema: " + request.schema());
        else if (prefixJsonInstruction() && request.messages().stream().noneMatch(m -> m.role().equals("system")))
            messages.addObject().put("role", "system").put("content", "Return one JSON object.");
        for (var message : request.messages()) {
            String content = message.content();
            if (prefixJsonInstruction() && message.role().equals("system")) content = "Return one JSON object. " + content;
            messages.addObject().put("role", message.role()).put("content", content);
        }
        result.set("response_format", mode.equals("json_schema") && profile.schemaMode().equals("native")
                ? json.valueToTree(Map.of("type", mode, "json_schema", Map.of("name", request.purpose(), "strict", true, "schema", request.schema())))
                : json.valueToTree(Map.of("type", "json_object")));
        return result;
    }
    public AiTextService.Result decode(ObjectMapper json, Target target, AiTextService.Request request, JsonNode envelope) {
        var choice = envelope.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText()))
            throw new AiTextService.Failure("OUTPUT_TRUNCATED", "AI output was truncated");
        var value = parse(json, choice.path("message").path("content"), 12000, true);
        return new AiTextService.Result(request.requestId(), value, id(), request.model(), target.model(),
                reported(envelope), target.sourceId(), java.util.List.of(), envelope.path("usage"));
    }
    static String reported(JsonNode envelope) {
        String model = envelope.path("model").asText();
        return model.matches("[a-zA-Z0-9_.:/-]{1,128}") ? model : null;
    }
    static JsonNode parse(ObjectMapper json, JsonNode answer, int maximum, boolean legacy) {
        if (!answer.isTextual() || answer.asText().isBlank() || answer.asText().length() > maximum)
            throw new AiTextService.Failure("INVALID_CONTENT", "Invalid AI content");
        String content = answer.asText().trim();
        if (legacy) {
            if (content.startsWith("```")) {
                int first = content.indexOf('\n'), last = content.lastIndexOf("```");
                if (first >= 0 && last > first) content = content.substring(first + 1, last).trim();
            }
            int first = content.indexOf('{'), last = content.lastIndexOf('}');
            if (first >= 0 && last >= first) content = content.substring(first, last + 1);
        }
        var value = json.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
        if (!value.isObject()) throw new AiTextService.Failure("INVALID_JSON", "AI object missing");
        return value;
    }
}
