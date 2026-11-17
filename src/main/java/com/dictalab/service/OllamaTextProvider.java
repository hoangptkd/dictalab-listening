package com.dictalab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.dictalab.config.AiTextProperties.ModelProfile;

@org.springframework.stereotype.Component
public class OllamaTextProvider implements AiTextProvider {
    public String id() { return "ollama"; }
    @Override public void validateTarget(Target target) {
        AiTextProvider.super.validateTarget(target);
        var uri = java.net.URI.create(target.url());
        if (!java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()) || !uri.getPath().equals("/api/chat"))
            throw new IllegalArgumentException("Direct local AI requires loopback /api/chat");
    }
    public JsonNode payload(ObjectMapper json, Target target, AiTextService.Request request, ModelProfile profile) {
        var body = json.createObjectNode().put("model", target.model()).put("stream", false).put("think", false);
        if (request.outputMode().equals("json_schema")) body.set("format", request.schema()); else body.put("format", "json");
        body.set("messages", json.valueToTree(request.messages()));
        var options = body.putObject("options").put("num_ctx", request.contextTokens()).put("num_predict", request.maxOutputTokens());
        if (request.temperature() != null) options.put("temperature", request.temperature());
        if (request.keepAlive() != null) body.put("keep_alive", request.keepAlive());
        return body;
    }
    public AiTextService.Result decode(ObjectMapper json, Target target, AiTextService.Request request, JsonNode envelope) {
        if (!envelope.path("done").asBoolean() || !"stop".equals(envelope.path("done_reason").asText()))
            throw new AiTextService.Failure("OUTPUT_TRUNCATED", "Incomplete local AI response");
        return new AiTextService.Result(request.requestId(), OpenAiCompatibleTextProvider.parse(json,
            envelope.path("message").path("content"), 12000, false), id(), request.model(), target.model(),
            OpenAiCompatibleTextProvider.reported(envelope), target.sourceId(), java.util.List.of(), envelope);
    }
}
