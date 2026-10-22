package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URI;

/** Validated runtime provider pool shared with the Python gateway. */
@ConfigurationProperties("dictalab.gemini")
public record GeminiProviderProperties(List<Provider> providers, int globalRpmLimit, int globalRpdLimit,
                                       boolean quotaEnabled, String quotaBackend, String providersJson) {
    public GeminiProviderProperties(List<Provider> providers, int globalRpmLimit, int globalRpdLimit,
                                    boolean quotaEnabled, String quotaBackend) {
        this(providers, globalRpmLimit, globalRpdLimit, quotaEnabled, quotaBackend, "");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GeminiProviderProperties {
        if (providersJson != null && !providersJson.isBlank()) {
            try {
                providers = new tools.jackson.databind.ObjectMapper().readValue(providersJson,
                        new tools.jackson.core.type.TypeReference<List<Provider>>() {});
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid Gemini providers JSON");
            }
        }
        providersJson = ""; // Do not retain a second copy of credentials.
        providers = providers == null ? List.of() : List.copyOf(providers);
        if (globalRpmLimit < 0 || globalRpdLimit < 0) throw new IllegalArgumentException("Invalid Gemini global quota");
        quotaBackend = quotaBackend == null || quotaBackend.isBlank()
                ? "redis" : quotaBackend.toLowerCase(Locale.ROOT);
        if (!Set.of("memory", "redis").contains(quotaBackend)) throw new IllegalArgumentException("Invalid Gemini quota backend");

        var ids = new java.util.HashSet<String>();
        var quotas = new java.util.HashSet<String>();
        for (Provider provider : providers) {
            if (provider == null) throw new IllegalArgumentException("Missing Gemini provider");
            if (provider.id() == null || provider.id().isBlank() || !ids.add(provider.id()))
                throw new IllegalArgumentException("Gemini provider IDs must be unique and non-blank");
            if (provider.enabled() && !quotas.add(provider.projectId() + ":" + provider.model()))
                throw new IllegalArgumentException("Duplicate Gemini project/model quota");
        }
        if (quotaEnabled && providers.stream().noneMatch(Provider::enabled))
            throw new IllegalArgumentException("Gemini quota requires an enabled provider");
    }

    public record Provider(String id, String projectId, String apiKey, String model, String url,
                           int rpmLimit, int tpmLimit, int rpdLimit, int operatingRpm,
                           boolean enabled, int priority) {
        public Provider {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid Gemini provider ID");
            if (projectId == null) projectId = "";
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "gemini-3.5-flash-lite";
            if (url == null || url.isBlank()) url = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            model = model.replaceFirst("^models/", "");
            if (!model.matches("[a-zA-Z0-9_.-]{1,100}") || !projectId.matches("[a-zA-Z0-9_-]{0,100}"))
                throw new IllegalArgumentException("Invalid Gemini model or project ID");
            if (apiKey.length() > 512 || apiKey.chars().anyMatch(c -> c < 33 || c > 126))
                throw new IllegalArgumentException("Invalid Gemini API key");
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
                throw new IllegalArgumentException("Gemini provider URL requires TLS except loopback");
            if (rpmLimit < 0 || tpmLimit < 0 || rpdLimit < 0 || operatingRpm < 0 || priority < 0)
                throw new IllegalArgumentException("Invalid Gemini provider quota");
            if (operatingRpm > rpmLimit && rpmLimit > 0)
                throw new IllegalArgumentException("Gemini operating RPM exceeds provider RPM");
            if (enabled && apiKey.isBlank()) throw new IllegalArgumentException("Enabled Gemini provider requires an API key");
            if (enabled && projectId.isBlank()) throw new IllegalArgumentException("Enabled Gemini provider requires a project ID");
            if (enabled && rpmLimit == 0) throw new IllegalArgumentException("Enabled Gemini provider requires RPM quota");
        }

        public int effectiveRpm() {
            return operatingRpm > 0 ? operatingRpm : rpmLimit;
        }

        @Override public String toString() {
            return "GeminiProvider[id=" + id + ", projectId=" + projectId + ", model=" + model
                    + ", enabled=" + enabled + ", apiKey=[REDACTED]]";
        }
    }

    public List<Provider> enabledProviders() {
        return providers.stream().filter(Provider::enabled).sorted(java.util.Comparator
                .comparingInt(Provider::priority).thenComparing(Provider::id)).toList();
    }

    @Override
    public String toString() {
        return "GeminiProviderProperties[providers=" + providers.stream().map(Provider::id).toList()
                + ", globalRpmLimit=" + globalRpmLimit + ", globalRpdLimit=" + globalRpdLimit
                + ", quotaEnabled=" + quotaEnabled + ", quotaBackend=" + quotaBackend + "]";
    }
}
