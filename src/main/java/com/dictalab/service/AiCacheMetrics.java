package com.dictalab.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Measured provider attempts only. Prices are an explicit estimate, never an invoice. */
final class AiCacheMetrics {
    static final String PRICING = "google-standard-text-2026-11-19.v1";
    private static final Map<String, double[]> PRICES = Map.of(
            "gemini-3.5-flash-lite", new double[]{.30, .03, 2.50},
            "gemini-3.1-flash-lite", new double[]{.25, .025, 1.50});
    private AiCacheMetrics() {}

    static boolean measured(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
    }
    static boolean money(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0;
    }
    static JsonNode value(JsonNode attempt, String primary, String alternate) {
        return attempt.has(primary) ? attempt.path(primary) : attempt.path(alternate);
    }
    static void compatibleUsage(JsonNode envelope, Map<String, Object> target) {
        JsonNode usage = envelope.path("usage");
        for (String field : List.of("prompt_tokens", "completion_tokens"))
            if (measured(usage.path(field))) target.put(field, usage.path(field).asLong());
        var details = usage.path("prompt_tokens_details");
        var cached = details.has("cached_tokens") ? details.path("cached_tokens") : usage.path("prompt_cache_hit_tokens");
        target.put("cache_usage_status", "not-reported");
        if (details.has("cached_tokens") || usage.has("prompt_cache_hit_tokens")) {
            target.put("cache_usage_status", "invalid");
            if (measured(cached) && measured(usage.path("prompt_tokens")) && cached.asLong() <= usage.path("prompt_tokens").asLong()) {
                target.put("cached_input_tokens", cached.asLong());
                target.put("uncached_input_tokens", usage.path("prompt_tokens").asLong() - cached.asLong());
                target.put("cache_usage_status", cached.asLong() > 0 ? "hit" : "zero");
            }
        }
        var reasoning = usage.path("completion_tokens_details").path("reasoning_tokens");
        if (measured(reasoning) && measured(usage.path("completion_tokens")) && reasoning.asLong() <= usage.path("completion_tokens").asLong())
            target.put("reasoning_tokens", reasoning.asLong());
        String model = envelope.path("model").asText("");
        if (model.matches("[a-zA-Z0-9_.:/-]{1,128}")) target.put("response_model", model);
    }

    static ObjectNode summarize(ObjectMapper json, JsonNode run) {
        record Attempt(JsonNode data, String scope) {}
        var attempts = new ArrayList<Attempt>();
        var scopeCounts = new HashMap<String, Integer>(); var scopePriced = new HashMap<String, Integer>();
        var scopeUnknown = new HashSet<String>(); var scopeCost = new HashMap<String, Double>();
        long unobservedCalls = 0;
        for (var row : run.path("cases")) for (var call : row.path("calls")) {
            String scope = call.path("step").asText().startsWith("judge-") ? "judge" : "generation";
            // Old aggregate-only records stay unknown; do not pretend a logical call is one HTTP attempt.
            if (!call.path("attempts").isArray() || call.path("attempts").isEmpty()
                    || !measured(call.path("httpAttempts")) || call.path("httpAttempts").asLong() != call.path("attempts").size()) {
                unobservedCalls++; scopeUnknown.add(scope);
            }
            for (var attempt : call.path("attempts")) attempts.add(new Attempt(attempt,scope));
        }
        long known = 0, hits = 0, input = 0, cached = 0, priced = 0;
        var prefixes = new HashSet<String>();
        long prefixAttempts = 0, repeats = 0;
        var firstTimes = new ArrayList<Double>(); var repeatTimes = new ArrayList<Double>();
        double cost = 0, saved = 0;
        for (var observed : attempts) {
            var attempt = observed.data();
            scopeCounts.merge(observed.scope(),1,Integer::sum);
            if (attempt.path("prefixHash").asText().matches("[a-f0-9]{64}")) {
                prefixAttempts++;
                String key = attempt.path("source").asText("unknown") + "/" + attempt.path("model").asText("unknown") + "/" + attempt.path("prefixHash").asText();
                boolean repeated = !prefixes.add(key);
                if (repeated) repeats++;
                var seconds = attempt.path("seconds");
                if (seconds.isNumber() && Double.isFinite(seconds.asDouble()) && seconds.asDouble() >= 0)
                    (repeated ? repeatTimes : firstTimes).add(seconds.asDouble());
            }
            var total = value(attempt, "input_tokens", "prompt_tokens");
            var output = value(attempt, "output_tokens", "completion_tokens");
            var cache = attempt.path("cached_input_tokens");
            boolean valid = measured(total) && measured(cache) && cache.asLong() <= total.asLong();
            if (valid) { known++; input += total.asLong(); cached += cache.asLong(); if (cache.asLong() > 0) hits++; }
            // A different returned model must never inherit the requested model's price.
            String model = attempt.path("response_model").asText(attempt.path("model").asText()).replaceFirst("^models/", "");
            double[] price = PRICES.get(model);
            if (valid && measured(output) && price != null) {
                priced++;
                double amount = ((total.asLong() - cache.asLong()) * price[0] + cache.asLong() * price[1] + output.asLong() * price[2]) / 1e6;
                cost += amount;
                scopePriced.merge(observed.scope(),1,Integer::sum); scopeCost.merge(observed.scope(),amount,Double::sum);
                saved += cache.asLong() * (price[0] - price[1]) / 1e6;
            }
        }
        var result = json.createObjectNode().put("pricingVersion", PRICING)
                .put("pricingSource", "https://aistudio.google.com/docs/pricing")
                .put("scope", "recorded-attempts-standard-text-estimate-not-invoice")
                .put("attempts", attempts.size()).put("unobservedCalls", unobservedCalls)
                .put("measuredCacheAttempts", known).put("cacheHits", hits)
                .put("measuredInputTokens", input).put("cachedInputTokens", cached).put("uncachedInputTokens", input - cached)
                .put("pricedAttempts", priced).put("measuredCostUsd", cost).put("measuredSavingsUsd", saved);
        result.put("prefixMeasuredAttempts", prefixAttempts).put("distinctPrefixes", prefixes.size()).put("repeatedPrefixAttempts", repeats);
        // First-seen and repeated are local observations, never claims that Google's cache is cold/warm.
        for (var group : Map.of("firstSeen", firstTimes, "repeatedPrefix", repeatTimes).entrySet()) {
            var times = group.getValue().stream().mapToDouble(Double::doubleValue).sorted().toArray();
            if (times.length > 0) result.put(group.getKey()+"P95", times[(int)Math.ceil(times.length*.95)-1]);
            else result.putNull(group.getKey()+"P95");
        }
        if (known > 0) result.put("hitRate", (double)hits / known); else result.putNull("hitRate");
        if (known == 0) for (String field : List.of("measuredInputTokens", "cachedInputTokens", "uncachedInputTokens")) result.putNull(field);
        if (priced == 0) result.putNull("measuredCostUsd").putNull("measuredSavingsUsd");
        if (input > 0) result.put("cachedInputRatio", (double)cached / input); else result.putNull("cachedInputRatio");
        boolean complete = !attempts.isEmpty() && priced == attempts.size() && unobservedCalls == 0;
        result.put("costComplete", complete);
        for (String scope : List.of("generation", "judge")) {
            int count = scopeCounts.getOrDefault(scope,0), covered = scopePriced.getOrDefault(scope,0);
            boolean scopeComplete = !scopeUnknown.contains(scope) && count == covered && (count > 0 || scope.equals("judge"));
            result.put(scope+"Attempts",count).put(scope+"PricedAttempts",covered).put(scope+"CostComplete",scopeComplete);
            if (scopeComplete) result.put(scope+"EstimatedCostUsd",scopeCost.getOrDefault(scope,0.0));
            else result.putNull(scope+"EstimatedCostUsd");
        }
        if (complete) result.put("estimatedCostUsd", cost); else result.putNull("estimatedCostUsd");
        long passed = run.path("cases").valueStream().filter(r -> "PASS".equals(r.path("after").path("status").asText())).count();
        if (complete && passed > 0) result.put("costPerJudgedPassUsd", cost / passed); else result.putNull("costPerJudgedPassUsd");
        var latency = attempts.stream().map(a -> a.data().path("seconds")).filter(n -> n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble() >= 0)
                .mapToDouble(JsonNode::asDouble).sorted().toArray();
        result.put("latencyMeasuredAttempts", latency.length);
        for (int percentile : List.of(50, 95)) {
            if (latency.length > 0) result.put("providerP" + percentile, latency[(int)Math.ceil(latency.length * percentile / 100.0) - 1]);
            else result.putNull("providerP" + percentile);
        }
        return result;
    }
}
