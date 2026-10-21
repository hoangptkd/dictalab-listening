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
        var disabled = new GeminiProviderProperties.Provider("a", "", "", "model", "url", 1, 1, 1, 1, false, 0);
        var properties = new GeminiProviderProperties(List.of(disabled), 1, 1, true, "memory");
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
}
