package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Shared v2 protocol: rolling RPM/TPM, Pacific RPD, and project/model cooldown. */
@Component
public final class GeminiQuotaCoordinator {
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("gemini_quota.lua")); SCRIPT.setResultType(List.class); }
    private final GeminiProviderProperties properties;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Map<String, GeminiProviderProperties.Provider> providers;
    private final Map<String, Window> windows = new HashMap<>();
    private final Map<String, State> states = new HashMap<>();
    private final Window global = new Window();
    private String platform = "gemini";
    private AiProviderRegistry registry;
    private final Map<String, GeminiQuotaCoordinator> platformCoordinators = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    void configureRegistry(AiProviderRegistry registry) { this.registry = registry; }

    private synchronized GeminiQuotaCoordinator forPlatform(String selected) {
        if (registry == null || !registry.databaseEnabled()) {
            if (!selected.equals("gemini")) throw new IllegalStateException("Cloud platform requires database configuration");
            return this;
        }
        return platformCoordinators.computeIfAbsent(selected, name -> {
            var coordinator = new GeminiQuotaCoordinator(registry.properties(name), metrics, redis, clock);
            coordinator.platform = name;
            return coordinator;
        });
    }

    public Admission reserve(String platform, String providerId, String model, int tokens) {
        return forPlatform(platform).reserve(providerId,model,tokens);
    }

    public void outcome(String platform, String providerId, String model, String outcome, int seconds) {
        forPlatform(platform).outcome(providerId,model,outcome,seconds);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiQuotaCoordinator(GeminiProviderProperties properties, MeterRegistry metrics,
                                  ObjectProvider<StringRedisTemplate> redis) {
        this(properties, metrics, redis.getIfAvailable(), Clock.systemUTC());
    }

    GeminiQuotaCoordinator(GeminiProviderProperties properties, MeterRegistry metrics,
                           StringRedisTemplate redis, Clock clock) {
        this.properties = properties; this.redis = redis; this.metrics = metrics; this.clock = clock;
        this.providers = properties.providers().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                GeminiProviderProperties.Provider::id, provider -> provider));
    }

    public boolean tryReserve(String providerId, String model, int estimatedTokens) {
        return reserve(providerId, model, estimatedTokens).allowed();
    }

    public Admission reserve(String providerId, String model, int tokens) {
        if (tokens < 1) throw new IllegalArgumentException("Estimated Gemini tokens must be positive");
        if (!properties.quotaEnabled()) return new Admission(true, 0);
        var provider = providers.get(providerId);
        if (provider == null || !provider.enabled()) return new Admission(false, 60000);
        String selectedModel = normalize(model == null ? provider.model() : model);
        if (!selectedModel.equals(provider.model())) return new Admission(false, 60000);
        Admission result;
        if ("redis".equals(properties.quotaBackend())) {
            var values = execute(provider, selectedModel, tokens, "reserve", 0);
            result = new Admission(((Number) values.get(0)).longValue() == 1, ((Number) values.get(1)).longValue());
        } else result = reserveMemory(provider, selectedModel, tokens);
        metrics.counter("dictalab.gemini.quota.reservations", "provider", providerId,
                "model", selectedModel, "outcome", result.allowed() ? "allowed" : "rejected").increment();
        return result;
    }

    public void outcome(String providerId, String model, String outcome, int retrySeconds) {
        if (!properties.quotaEnabled() || providerId == null) return;
        var provider = providers.get(providerId);
        if (provider == null) return;
        if ("redis".equals(properties.quotaBackend())) execute(provider, normalize(model), 1, outcome, retrySeconds * 1000L);
        else synchronized (this) {
            var state = states.computeIfAbsent(provider.projectId() + ":" + normalize(model), ignored -> new State());
            if (outcome.equals("success")) state.failures = 0;
            else if (outcome.equals("429")) state.until = Math.max(state.until, clock.millis() + retrySeconds * 1000L);
            else if (++state.failures >= 5) state.until = Math.max(state.until, clock.millis() + 30000);
        }
    }

    private List<?> execute(GeminiProviderProperties.Provider provider, String model, int tokens, String operation, long delay) {
        try {
            if (redis == null) throw new IllegalStateException("Missing Redis client");
            Long now = redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) connection -> connection.serverCommands().time());
            if (now == null) throw new IllegalStateException("Missing Redis time");
            var day = java.time.Instant.ofEpochMilli(now).atZone(PACIFIC).toLocalDate();
            long start = day.atStartOfDay(PACIFIC).toInstant().toEpochMilli();
            long end = day.plusDays(1).atStartOfDay(PACIFIC).toInstant().toEpochMilli();
            String root = platform.equals("gemini") ? "{dictalab-gemini-quota}:v2:" : "{dictalab-ai-quota}:v2:" + platform + ":";
            String prefix = root + provider.projectId() + ":" + model;
            List<?> result = redis.execute(SCRIPT, List.of(prefix + ":requests", prefix + ":tokens", prefix + ":day:" + day,
                            root + "global:requests", root + "global:day:" + day, prefix + ":state", prefix + ":metrics"),
                    operation, Integer.toString(tokens), Integer.toString(provider.effectiveRpm()), Integer.toString(provider.tpmLimit()),
                    Integer.toString(provider.rpdLimit()), Integer.toString(properties.globalRpmLimit()), Integer.toString(properties.globalRpdLimit()),
                    UUID.randomUUID().toString(), Long.toString(start), Long.toString(end), "5", "30000", Long.toString(delay));
            if (result == null) throw new IllegalStateException("Missing quota result");
            return result;
        } catch (RuntimeException unavailable) {
            metrics.counter("dictalab.gemini.quota.redis_failures").increment();
            throw new ProviderGuard.Unavailable("Gemini quota Redis unavailable.", 5, true);
        }
    }

    private synchronized Admission reserveMemory(GeminiProviderProperties.Provider provider, String model, int tokens) {
        long now = clock.millis();
        LocalDate day = clock.instant().atZone(PACIFIC).toLocalDate();
        String key = provider.projectId() + ":" + model;
        var window = windows.computeIfAbsent(key, ignored -> new Window());
        window.clean(now, day); global.clean(now, day);
        var state = states.get(key);
        long wait = state == null ? 0 : Math.max(0, state.until - now);
        if (window.requests.size() >= provider.effectiveRpm()) wait = Math.max(wait, window.waitMs(now));
        if (provider.tpmLimit() > 0 && window.tokens() + tokens > provider.tpmLimit()) wait = Math.max(wait, 60000);
        if (properties.globalRpmLimit() > 0 && global.requests.size() >= properties.globalRpmLimit()) wait = Math.max(wait, global.waitMs(now));
        if (provider.rpdLimit() > 0 && window.daily >= provider.rpdLimit()
                || properties.globalRpdLimit() > 0 && global.daily >= properties.globalRpdLimit())
            wait = Math.max(wait, day.plusDays(1).atStartOfDay(PACIFIC).toInstant().toEpochMilli() - now);
        if (wait > 0) return new Admission(false, wait);
        window.add(now, tokens); global.add(now, tokens);
        return new Admission(true, 0);
    }

    private static String normalize(String model) { return model.replaceFirst("^models/", ""); }
    public record Admission(boolean allowed, long retryAfterMs) {}
    private record Request(long time, int tokens) {}
    private static final class State { private long until; private int failures; }
    private static final class Window {
        private final Deque<Request> requests = new ArrayDeque<>();
        private LocalDate day;
        private int daily;
        void clean(long now, LocalDate date) {
            while (!requests.isEmpty() && requests.peekFirst().time() <= now - 60000) requests.removeFirst();
            if (!date.equals(day)) { day = date; daily = 0; }
        }
        long tokens() { return requests.stream().mapToLong(Request::tokens).sum(); }
        long waitMs(long now) { return requests.isEmpty() ? 60000 : Math.max(1, requests.peekFirst().time() + 60000 - now); }
        void add(long now, int tokens) { requests.addLast(new Request(now, tokens)); daily++; }
    }
}
