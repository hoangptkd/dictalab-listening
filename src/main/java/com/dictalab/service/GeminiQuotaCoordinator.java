package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic admission for Gemini RPM, TPM and RPD budgets. It never refunds a sent request. */
@Component
public final class GeminiQuotaCoordinator {
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local rpm = redis.call('INCR', KEYS[1])
            if rpm == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            local tpm = redis.call('INCRBY', KEYS[2], ARGV[1])
            if tpm == tonumber(ARGV[1]) then redis.call('EXPIRE', KEYS[2], 60) end
            local rpd = redis.call('INCR', KEYS[3])
            if rpd == 1 then redis.call('EXPIRE', KEYS[3], 172800) end
            local globalRpm = redis.call('INCR', KEYS[4])
            if globalRpm == 1 then redis.call('EXPIRE', KEYS[4], 60) end
            local globalRpd = redis.call('INCR', KEYS[5])
            if globalRpd == 1 then redis.call('EXPIRE', KEYS[5], 172800) end
            local over = (tonumber(ARGV[2]) > 0 and rpm > tonumber(ARGV[2]))
                    or (tonumber(ARGV[3]) > 0 and tpm > tonumber(ARGV[3]))
                    or (tonumber(ARGV[4]) > 0 and rpd > tonumber(ARGV[4]))
                    or (tonumber(ARGV[5]) > 0 and globalRpm > tonumber(ARGV[5]))
                    or (tonumber(ARGV[6]) > 0 and globalRpd > tonumber(ARGV[6]))
            if over then
                redis.call('DECR', KEYS[1])
                redis.call('DECRBY', KEYS[2], ARGV[1])
                redis.call('DECR', KEYS[3])
                redis.call('DECR', KEYS[4])
                redis.call('DECR', KEYS[5])
                return 0
            end
            return 1
            """, Long.class);

    private final GeminiProviderProperties properties;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Map<String, GeminiProviderProperties.Provider> providers;
    private final ConcurrentHashMap<String, Window> memoryWindows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiQuotaCoordinator(GeminiProviderProperties properties, MeterRegistry metrics,
                                  ObjectProvider<StringRedisTemplate> redis) {
        this(properties, metrics, redis.getIfAvailable(), Clock.systemUTC());
    }

    GeminiQuotaCoordinator(GeminiProviderProperties properties, MeterRegistry metrics,
                           StringRedisTemplate redis, Clock clock) {
        this.properties = properties;
        this.redis = redis;
        this.metrics = metrics;
        this.clock = clock;
        this.providers = properties.providers().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                GeminiProviderProperties.Provider::id, provider -> provider));
    }

    public boolean tryReserve(String providerId, String model, int estimatedTokens) {
        if (estimatedTokens < 1) throw new IllegalArgumentException("Estimated Gemini tokens must be positive");
        if (!properties.quotaEnabled()) return true;
        var provider = providers.get(providerId);
        if (provider == null || !provider.enabled()) return false;
        String selectedModel = model == null || model.isBlank() ? provider.model() : model;
        boolean allowed = "redis".equals(properties.quotaBackend())
                ? reserveRedis(provider, selectedModel, estimatedTokens)
                : reserveMemory(provider, selectedModel, estimatedTokens);
        metrics.counter("dictalab.gemini.quota.reservations", "provider", providerId,
                "model", selectedModel, "outcome", allowed ? "allowed" : "rejected").increment();
        return allowed;
    }

    private boolean reserveRedis(GeminiProviderProperties.Provider provider, String model, int tokens) {
        if (redis == null) throw new ProviderGuard.Unavailable("Gemini quota Redis chưa sẵn sàng.", 5, true);
        String day = LocalDate.now(clock.withZone(PACIFIC)).toString();
        String tag = "{dictalab-gemini-quota}";
        String prefix = tag + ":provider:" + provider.id() + ":" + model;
        var result = redis.execute(RESERVE,
                List.of(prefix + ":rpm", prefix + ":tpm", prefix + ":rpd:" + day,
                        tag + ":global:rpm", tag + ":global:rpd:" + day),
                Integer.toString(tokens), Integer.toString(provider.effectiveRpm()), Integer.toString(provider.tpmLimit()),
                Integer.toString(provider.rpdLimit()), Integer.toString(properties.globalRpmLimit()),
                Integer.toString(properties.globalRpdLimit()));
        if (result == null) throw new ProviderGuard.Unavailable("Gemini quota Redis không trả kết quả.", 5, true);
        return result == 1L;
    }

    private boolean reserveMemory(GeminiProviderProperties.Provider provider, String model, int tokens) {
        String day = LocalDate.now(clock.withZone(PACIFIC)).toString();
        String key = provider.id() + ":" + model + ":" + day;
        var window = memoryWindows.computeIfAbsent(key, ignored -> new Window(nowMinute(), day));
        synchronized (window) {
            long minute = nowMinute();
            if (window.minute != minute || !window.day.equals(day)) {
                window.minute = minute;
                window.day = day;
                window.rpm = 0;
                window.tpm = 0;
                window.rpd = 0;
            }
            int globalRpm = memoryWindows.computeIfAbsent("__global__:" + minute, ignored -> new Window(minute, day)).rpm;
            int globalRpd = memoryWindows.computeIfAbsent("__global-day__:" + day, ignored -> new Window(minute, day)).rpd;
            boolean over = (provider.effectiveRpm() > 0 && window.rpm + 1 > provider.effectiveRpm())
                    || (provider.tpmLimit() > 0 && window.tpm + tokens > provider.tpmLimit())
                    || (provider.rpdLimit() > 0 && window.rpd + 1 > provider.rpdLimit())
                    || (properties.globalRpmLimit() > 0 && globalRpm + 1 > properties.globalRpmLimit())
                    || (properties.globalRpdLimit() > 0 && globalRpd + 1 > properties.globalRpdLimit());
            if (over) return false;
            window.rpm++;
            window.tpm += tokens;
            window.rpd++;
            memoryWindows.get("__global__:" + minute).rpm++;
            memoryWindows.get("__global-day__:" + day).rpd++;
            return true;
        }
    }

    private long nowMinute() {
        return clock.instant().getEpochSecond() / 60;
    }

    private static final class Window {
        private long minute;
        private String day;
        private int rpm;
        private int tpm;
        private int rpd;

        private Window(long minute, String day) {
            this.minute = minute;
            this.day = day;
        }
    }
}
