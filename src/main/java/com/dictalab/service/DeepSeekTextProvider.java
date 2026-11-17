package com.dictalab.service;

@org.springframework.stereotype.Component
public class DeepSeekTextProvider extends OpenAiCompatibleTextProvider {
    @Override public String id() { return "deepseek"; }
    @Override protected String expectedHost() { return "api.deepseek.com"; }
    @Override public tools.jackson.databind.JsonNode payload(tools.jackson.databind.ObjectMapper json, Target target,
            AiTextService.Request request, com.dictalab.config.AiTextProperties.ModelProfile profile) {
        var result = (tools.jackson.databind.node.ObjectNode) super.payload(json, target, request, profile);
        result.putObject("thinking").put("type", "disabled");
        return result;
    }
}
