package com.dictalab.service;

import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Optional thread-scoped accounting; ordinary requests allocate no observation state. */
final class AiCallObservation implements AutoCloseable {
    private static final ThreadLocal<AiCallObservation> CURRENT = new ThreadLocal<>();
    private final Consumer<Map<String, Object>> sink;
    private final Runnable admission;
    private Map<String, Object> call;
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private long started;

    AiCallObservation(Runnable admission, Consumer<Map<String, Object>> sink) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested AI observation");
        this.admission = admission; this.sink = sink; CURRENT.set(this);
    }
    static boolean active() { return CURRENT.get() != null; }
    static void begin(String model) {
        var scope = CURRENT.get();
        if (scope == null) return;
        scope.admission.run();
        scope.call = new LinkedHashMap<>();
        scope.attempts.clear();
        scope.call.put("model", model); scope.call.put("httpAttempts", 0);
        scope.started = System.nanoTime();
    }
    static void http(String source, String model, int status) {
        var scope = CURRENT.get();
        if (scope == null || scope.call == null) return;
        scope.call.put("httpAttempts", ((Number)scope.call.get("httpAttempts")).intValue() + 1);
        scope.call.put("source", source == null ? "configured" : source);
        scope.call.put("httpStatus", status);
        var attempt = new LinkedHashMap<String, Object>();
        attempt.put("source", source == null ? "configured" : source); attempt.put("model", model); attempt.put("httpStatus", status);
        scope.attempts.add(attempt);
    }
    static void usage(JsonNode envelope) {
        var scope = CURRENT.get();
        if (scope == null || scope.call == null) return;
        for (String field : List.of("prompt_tokens", "completion_tokens"))
            if (envelope.path("usage").path(field).isIntegralNumber()
                    && envelope.path("usage").path(field).asLong() >= 0)
                if (!scope.attempts.isEmpty()) scope.attempts.get(scope.attempts.size() - 1).put(field, envelope.path("usage").path(field).asLong());
    }
    static void status(int status) {
        var scope = CURRENT.get();
        if (scope != null && scope.call != null) {
            scope.call.put("httpStatus", status);
            if (!scope.attempts.isEmpty()) scope.attempts.get(scope.attempts.size() - 1).put("httpStatus", status);
        }
    }
    static void remoteStarted() {
        var scope = CURRENT.get();
        if (scope != null && scope.call != null) {
            scope.call.put("transport", "python"); scope.call.put("httpAttempts", null);
        }
    }
    static void remote(JsonNode envelope) {
        var scope = CURRENT.get();
        if (scope == null || scope.call == null) return;
        scope.call.put("transport", "python"); scope.call.put("httpAttempts", null);
        var observation = envelope.path("observation");
        if (observation.isObject()) {
            scope.call.put("attempts", observation.path("attempts"));
            if (observation.path("httpAttempts").isIntegralNumber())
                scope.call.put("httpAttempts", observation.path("httpAttempts").asInt());
            for (String field : List.of("prompt_tokens", "completion_tokens"))
                if (observation.path(field).isIntegralNumber() && observation.path(field).asLong() >= 0)
                    scope.call.put(field, observation.path(field).asLong());
        }
    }
    static void end(String outcome) {
        var scope = CURRENT.get();
        if (scope == null || scope.call == null) return;
        if (!scope.attempts.isEmpty()) {
            scope.call.put("attempts", List.copyOf(scope.attempts));
            for (String field : List.of("prompt_tokens", "completion_tokens"))
                if (scope.attempts.stream().allMatch(a -> a.containsKey(field)))
                    scope.call.put(field, scope.attempts.stream().mapToLong(a -> ((Number)a.get(field)).longValue()).sum());
        }
        scope.call.put("outcome", outcome); scope.call.put("seconds", (System.nanoTime() - scope.started) / 1e9);
        scope.sink.accept(Collections.unmodifiableMap(scope.call)); scope.call = null;
    }
    @Override public void close() { CURRENT.remove(); }
}
