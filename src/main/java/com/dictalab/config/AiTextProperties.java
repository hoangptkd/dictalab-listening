package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties("dictalab.ai.text")
public record AiTextProperties(String modelProfilesJson) {
    public AiTextProperties {
        if (modelProfilesJson == null || modelProfilesJson.isBlank()) modelProfilesJson = "{}";
        parse(modelProfilesJson);
    }
    public record ModelProfile(String tokenField, String temperaturePolicy, Set<String> jsonModes, String schemaMode) {
        public ModelProfile(String tokenField, String temperaturePolicy, Set<String> jsonModes) {
            this(tokenField, temperaturePolicy, jsonModes, null);
        }
        public ModelProfile {
            if (schemaMode == null) schemaMode = "native";
            if (tokenField == null) tokenField = "max_tokens";
            if (temperaturePolicy == null) temperaturePolicy = "send";
            if (jsonModes == null) jsonModes = Set.of("json_object", "json_schema");
            jsonModes = Set.copyOf(jsonModes);
            if (!Set.of("max_tokens", "max_completion_tokens").contains(tokenField)
                    || !Set.of("send", "omit").contains(temperaturePolicy)
                    || jsonModes.isEmpty() || !Set.of("json_object", "json_schema").containsAll(jsonModes)
                    || !Set.of("native", "prompt").contains(schemaMode))
                throw new IllegalArgumentException("Invalid AI model capability profile");
        }
    }
    private static Map<String, ModelProfile> parse(String value) {
        try {
            var result = new ObjectMapper().readValue(value, new tools.jackson.core.type.TypeReference<Map<String, ModelProfile>>() {});
            if (result == null || result.size() > 100 || result.entrySet().stream().anyMatch(e -> e.getValue() == null
                    || !e.getKey().matches("[a-z][a-z0-9_]{0,31}/[a-zA-Z0-9_.:/-]{1,128}"))) throw new IllegalArgumentException();
            return Map.copyOf(result);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid AI model profiles"); }
    }
    public ModelProfile profile(String provider, String model) {
        return parse(modelProfilesJson).getOrDefault(provider + "/" + model, new ModelProfile(null, null, null));
    }
}
