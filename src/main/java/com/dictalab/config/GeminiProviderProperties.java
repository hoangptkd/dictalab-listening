package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validated provider-pool configuration. Routing is enabled in a later rollout step. */
@ConfigurationProperties("dictalab.gemini")
public record GeminiProviderProperties(List<Provider> providers, int globalRpmLimit, int globalRpdLimit,
                                       boolean quotaEnabled, String quotaBackend) {
    public GeminiProviderProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
        if (globalRpmLimit < 0 || globalRpdLimit < 0) throw new IllegalArgumentException("Invalid Gemini global quota");
        quotaBackend = quotaBackend == null || quotaBackend.isBlank()
                ? "redis" : quotaBackend.toLowerCase(Locale.ROOT);
        if (!Set.of("memory", "redis").contains(quotaBackend)) throw new IllegalArgumentException("Invalid Gemini quota backend");

        var ids = new java.util.HashSet<String>();
        for (Provider provider : providers) {
            if (provider == null) throw new IllegalArgumentException("Missing Gemini provider");
            if (provider.id() == null || provider.id().isBlank() || !ids.add(provider.id()))
                throw new IllegalArgumentException("Gemini provider IDs must be unique and non-blank");
        }
    }

    public record Provider(String id, String projectId, String apiKey, String model, String url,
                           int rpmLimit, int tpmLimit, int rpdLimit, int operatingRpm,
                           boolean enabled, int priority) {
        public Provider {
            if (projectId == null) projectId = "";
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "gemini-3.5-flash-lite";
            if (url == null || url.isBlank()) url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            if (rpmLimit < 0 || tpmLimit < 0 || rpdLimit < 0 || operatingRpm < 0 || priority < 0)
                throw new IllegalArgumentException("Invalid Gemini provider quota");
            if (operatingRpm > rpmLimit && rpmLimit > 0)
                throw new IllegalArgumentException("Gemini operating RPM exceeds provider RPM");
            if (enabled && apiKey.isBlank()) throw new IllegalArgumentException("Enabled Gemini provider requires an API key");
            if (enabled && projectId.isBlank()) throw new IllegalArgumentException("Enabled Gemini provider requires a project ID");
        }

        public int effectiveRpm() {
            return operatingRpm > 0 ? operatingRpm : rpmLimit;
        }
    }

    public List<Provider> enabledProviders() {
        return providers.stream().filter(Provider::enabled).toList();
    }

    @Override
    public String toString() {
        return "GeminiProviderProperties[providers=" + providers.stream().map(Provider::id).toList()
                + ", globalRpmLimit=" + globalRpmLimit + ", globalRpdLimit=" + globalRpdLimit
                + ", quotaEnabled=" + quotaEnabled + ", quotaBackend=" + quotaBackend + "]";
    }
}
