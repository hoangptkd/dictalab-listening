package com.dictalab.scenario;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Private strict boundary; does not change Jackson's backward-compatible v1 configuration. */
public final class ScenarioJson {
    public static final int MAX_REQUEST_BYTES = 32 * 1024;
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    private ScenarioJson() {}

    public static <T> T read(byte[] bytes, Class<T> type) {
        if (bytes.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Scenario payload too large");
        T value = MAPPER.readValue(bytes, type);
        if (value == null) throw new IllegalArgumentException("Missing scenario payload");
        return value;
    }

    // scenario-canonical.v1: sorted ASCII keys, UTF-8 strings, integer numbers only.
    // Decimal targets/scales are strings. This is deliberately not advertised as RFC 8785.
    public static String canonical(JsonNode node) {
        if (node.isObject()) return node.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> MAPPER.writeValueAsString(e.getKey()) + ":" + canonical(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        if (node.isArray()) {
            var parts = new java.util.ArrayList<String>();
            node.forEach(item -> parts.add(canonical(item)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isFloatingPointNumber()) throw new IllegalArgumentException("Decimal must be a string");
        return MAPPER.writeValueAsString(node);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
