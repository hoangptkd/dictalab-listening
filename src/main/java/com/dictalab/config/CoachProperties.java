package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.coach")
public record CoachProperties(boolean enabled, String provider, String localBaseUrl, String localModel,
                              int localTimeoutSeconds) {
    public CoachProperties {
        if (provider == null || provider.isBlank()) provider = "gemini";
        if (!java.util.Set.of("gemini", "ollama").contains(provider))
            throw new IllegalArgumentException("Unsupported Coach provider");
        if (localBaseUrl == null || localBaseUrl.isBlank()) localBaseUrl = "http://127.0.0.1:11434";
        var uri = java.net.URI.create(localBaseUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme())
                || !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Coach local URL must use loopback without credentials or a path");
        localBaseUrl = localBaseUrl.replaceAll("/$", "");
        if (localModel == null || localModel.isBlank()) localModel = "qwen3.5:4b";
        if (localModel.length() > 120 || localModel.contains(":cloud"))
            throw new IllegalArgumentException("Invalid Coach local model");
        if (localTimeoutSeconds == 0) localTimeoutSeconds = 60;
        // The queue lease is 180 seconds; leave time for evidence and the fenced commit.
        if (localTimeoutSeconds < 1 || localTimeoutSeconds > 90)
            throw new IllegalArgumentException("Coach local timeout must be between 1 and 90 seconds");
    }

    public boolean local() { return "ollama".equals(provider); }
}
