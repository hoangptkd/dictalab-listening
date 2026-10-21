package com.dictalab.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiProviderPropertiesTest {
    private static GeminiProviderProperties.Provider provider(String id, boolean enabled) {
        return new GeminiProviderProperties.Provider(id, enabled ? "project-" + id : "", enabled ? "key-" + id : "",
                "gemini-3.5-flash-lite", "https://example.test/chat", 15, 1_000, 100, 13, enabled, 0);
    }

    @Test
    void disabledDefaultProvidersRemainSafeWithoutSecrets() {
        var properties = new GeminiProviderProperties(List.of(provider("provider-a", false)), 39, 0, false, "REDIS");

        assertThat(properties.quotaBackend()).isEqualTo("redis");
        assertThat(properties.enabledProviders()).isEmpty();
        assertThat(properties.toString()).doesNotContain("key-provider-a");
    }

    @Test
    void enabledProviderRequiresProjectAndKey() {
        assertThatThrownBy(() -> new GeminiProviderProperties.Provider("provider-a", "", "", "model", "url",
                15, 0, 0, 13, true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void duplicateProviderIdsAndUnsafeOperatingLimitAreRejected() {
        assertThatThrownBy(() -> new GeminiProviderProperties(List.of(provider("same", false), provider("same", false)),
                39, 0, false, "memory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");

        assertThatThrownBy(() -> new GeminiProviderProperties.Provider("provider-a", "project", "key", "model", "url",
                10, 0, 0, 11, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operating RPM");
    }

    @Test
    void operatingLimitDefaultsToProviderLimitWhenUnset() {
        var provider = new GeminiProviderProperties.Provider("provider-a", "", "", "model", "url",
                15, 0, 0, 0, false, 0);

        assertThat(provider.effectiveRpm()).isEqualTo(15);
    }
}
