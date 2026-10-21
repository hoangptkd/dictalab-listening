package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.conversation.generation")
public record ConversationGenerationProperties(String provider, String localBaseUrl, String localModel,
                                                int localTimeoutSeconds, String pythonBaseUrl,
                                                String pythonServiceToken, int pythonTimeoutSeconds) {
    public ConversationGenerationProperties(String provider, String localBaseUrl, String localModel,
                                             int localTimeoutSeconds) {
        this(provider, localBaseUrl, localModel, localTimeoutSeconds, null, null, 0);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ConversationGenerationProperties {
        // Existing cloud deployments remain compatible; local development opts into Ollama explicitly.
        if (provider == null || provider.isBlank()) provider = "shopaikey";
        if (!java.util.Set.of("shopaikey", "ollama", "python").contains(provider))
            throw new IllegalArgumentException("Unsupported conversation generation provider");
        if (localBaseUrl == null || localBaseUrl.isBlank()) localBaseUrl = "http://127.0.0.1:11434";
        var uri = java.net.URI.create(localBaseUrl);
        if (uri.getScheme() == null || uri.getHost() == null
                || !java.util.Set.of("http", "https").contains(uri.getScheme())
                || !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Conversation local URL must be loopback without credentials or a path");
        localBaseUrl = localBaseUrl.replaceAll("/$", "");
        if (localModel == null || localModel.isBlank()) localModel = "qwen3.5:9b";
        String normalizedModel = localModel.toLowerCase(java.util.Locale.ROOT);
        if (localModel.length() > 120 || normalizedModel.contains(":cloud") || normalizedModel.contains("-cloud"))
            throw new IllegalArgumentException("Invalid conversation local model");
        if (localTimeoutSeconds == 0) localTimeoutSeconds = 180;
        if (localTimeoutSeconds < 1 || localTimeoutSeconds > 600)
            throw new IllegalArgumentException("Conversation local timeout must be between 1 and 600 seconds");
        if (pythonBaseUrl == null || pythonBaseUrl.isBlank()) pythonBaseUrl = "http://127.0.0.1:8091";
        var pythonUri = java.net.URI.create(pythonBaseUrl);
        if (pythonUri.getScheme() == null || pythonUri.getHost() == null
                || !java.util.Set.of("http", "https").contains(pythonUri.getScheme())
                || pythonUri.getUserInfo() != null || pythonUri.getQuery() != null || pythonUri.getFragment() != null
                || !(pythonUri.getPath().isEmpty() || pythonUri.getPath().equals("/")))
            throw new IllegalArgumentException("Conversation Python URL must not contain credentials, query or path");
        pythonBaseUrl = pythonBaseUrl.replaceAll("/$", "");
        if (pythonServiceToken == null) pythonServiceToken = "";
        if (pythonServiceToken.length() > 256 || pythonServiceToken.indexOf('\n') >= 0 || pythonServiceToken.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid conversation Python service token");
        if (pythonTimeoutSeconds == 0) pythonTimeoutSeconds = 180;
        if (pythonTimeoutSeconds < 1 || pythonTimeoutSeconds > 600)
            throw new IllegalArgumentException("Conversation Python timeout must be between 1 and 600 seconds");
    }

    public boolean local() { return "ollama".equals(provider); }

    public boolean python() { return "python".equals(provider); }

    // Two generation attempts, up to two 30s reviews, plus validation/persistence margin.
    public int localFlowTimeoutSeconds() { return 2 * localTimeoutSeconds + 90; }

    public int pythonFlowTimeoutSeconds() { return 2 * pythonTimeoutSeconds + 90; }
}
