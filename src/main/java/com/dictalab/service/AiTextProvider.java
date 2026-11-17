package com.dictalab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Set;

/** Wire protocol only. Account admission, deadlines and retries belong to AiTextService. */
public interface AiTextProvider {
    String id();
    default String normalizeModel(String model) { return model; }
    default boolean rotateOn429() { return false; }
    default void validateTarget(Target target) {
        URI uri;
        try { uri = URI.create(target.url()); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new IllegalArgumentException("Invalid AI endpoint"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
            throw new IllegalArgumentException("AI endpoint requires TLS except loopback");
        if (target.model() == null || !target.model().matches("[a-zA-Z0-9_.:/-]{1,128}"))
            throw new IllegalArgumentException("Invalid AI model");
    }
    JsonNode payload(ObjectMapper json, Target target, AiTextService.Request request,
                     com.dictalab.config.AiTextProperties.ModelProfile profile);
    AiTextService.Result decode(ObjectMapper json, Target target, AiTextService.Request request, JsonNode envelope);
    default HttpRequest prepare(ObjectMapper json, Target target, AiTextService.Request request,
                                com.dictalab.config.AiTextProperties.ModelProfile profile, Duration remaining) {
        validateTarget(target);
        var builder = HttpRequest.newBuilder(URI.create(target.url())).timeout(remaining)
                .header("Content-Type", "application/json");
        if (!target.apiKey().isEmpty()) builder.header("Authorization", "Bearer " + target.apiKey());
        return builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(json, target, request, profile)))).build();
    }
    record Target(String url, String model, String apiKey, String sourceId) {
        public Target {
            if (apiKey == null || apiKey.length() > 512 || apiKey.chars().anyMatch(c -> c < 33 || c > 126))
                throw new IllegalArgumentException("Invalid AI credential");
        }
        @Override public String toString() { return "AiTextTarget[model=" + model + ", source=" + sourceId + "]"; }
    }
}
