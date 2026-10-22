package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiQuotaCoordinatorTest {
    private static GeminiProviderProperties.Provider provider(String id, int rpm, int rpd) {
        return new GeminiProviderProperties.Provider(id, "project-" + id, "key-" + id,
                "gemini-3.5-flash-lite", "https://example.test/chat", rpm, 100, rpd, rpm, true, 0);
    }

    @Test
    void memoryBackendAdmitsProviderAndGlobalLimitsAtomically() {
        var properties = new GeminiProviderProperties(List.of(provider("a", 2, 10), provider("b", 2, 10)),
                3, 10, true, "memory");
        var metrics = new SimpleMeterRegistry();
        {
            var coordinator = new GeminiQuotaCoordinator(properties, metrics, null,
                    Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC));

            assertThat(coordinator.tryReserve("a", "gemini-3.5-flash-lite", 10)).isTrue();
            assertThat(coordinator.tryReserve("b", "gemini-3.5-flash-lite", 10)).isTrue();
            assertThat(coordinator.tryReserve("a", "gemini-3.5-flash-lite", 10)).isTrue();
            assertThat(coordinator.tryReserve("b", "gemini-3.5-flash-lite", 10)).isFalse();
        }
    }

    @Test
    void disabledProviderAndInvalidTokenEstimateAreRejected() {
        var disabled = new GeminiProviderProperties.Provider("a", "", "", "model", "https://example.test/chat", 1, 1, 1, 1, false, 0);
        var properties = new GeminiProviderProperties(List.of(disabled, provider("b", 1, 1)), 1, 1, true, "memory");
        var metrics = new SimpleMeterRegistry();
        {
            var coordinator = new GeminiQuotaCoordinator(properties, metrics, null,
                    Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC));
            assertThat(coordinator.tryReserve("a", "model", 1)).isFalse();
            assertThatThrownBy(() -> coordinator.tryReserve("a", "model", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void quotaDisabledDoesNotBlockLegacyCalls() {
        var properties = new GeminiProviderProperties(List.of(), 1, 1, false, "redis");
        var metrics = new SimpleMeterRegistry();
        {
            var coordinator = new GeminiQuotaCoordinator(properties, metrics, null, Clock.systemUTC());
            assertThat(coordinator.tryReserve("legacy", "model", 1)).isTrue();
        }
    }

    @Test
    void minuteExpiryDoesNotResetRpdAndPacificMidnightDoes() {
        var clock = new MutableClock("2026-10-23T06:58:00Z");
        var properties = new GeminiProviderProperties(List.of(provider("a", 15, 1)), 15, 1, true, "memory");
        var quota = new GeminiQuotaCoordinator(properties, new SimpleMeterRegistry(), null, clock);
        assertThat(quota.tryReserve("a", "gemini-3.5-flash-lite", 1)).isTrue();
        clock.now = clock.now.plusSeconds(61);
        assertThat(quota.reserve("a", "gemini-3.5-flash-lite", 1).retryAfterMs()).isEqualTo(59000);
        clock.now = clock.now.plusSeconds(59);
        assertThat(quota.tryReserve("a", "gemini-3.5-flash-lite", 1)).isTrue();
    }

    @Test
    void globalAdmissionIsAtomicAcrossProjectsUnderConcurrency() {
        var properties = new GeminiProviderProperties(List.of(provider("a", 100, 100), provider("b", 100, 100)),
                39, 100, true, "memory");
        var quota = new GeminiQuotaCoordinator(properties, new SimpleMeterRegistry(), null, Clock.systemUTC());
        long allowed = java.util.stream.IntStream.range(0, 100).parallel()
                .filter(i -> quota.tryReserve(i % 2 == 0 ? "a" : "b", "gemini-3.5-flash-lite", 1)).count();
        assertThat(allowed).isEqualTo(39);
    }

    @Test
    void perProjectCooldownSurvivesSuccessAndDoesNotBlockOtherProjects() {
        var properties = new GeminiProviderProperties(List.of(provider("a", 15, 100), provider("b", 15, 100)),
                30, 200, true, "memory");
        var quota = new GeminiQuotaCoordinator(properties, new SimpleMeterRegistry(), null, Clock.systemUTC());
        quota.outcome("a", "gemini-3.5-flash-lite", "429", 600);
        quota.outcome("a", "gemini-3.5-flash-lite", "success", 0);
        for (int i = 0; i < 5; i++) quota.outcome("a", "gemini-3.5-flash-lite", "failure", 0);
        assertThat(quota.reserve("a", "gemini-3.5-flash-lite", 1).retryAfterMs()).isGreaterThan(590000);
        assertThat(quota.tryReserve("b", "gemini-3.5-flash-lite", 1)).isTrue();
        assertThat(quota.tryReserve("b", "unconfigured-model", 1)).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(String instant) { now = Instant.parse(instant); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
