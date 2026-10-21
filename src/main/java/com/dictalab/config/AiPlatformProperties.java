package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.util.Set;

@ConfigurationProperties("dictalab.ai.platform")
public record AiPlatformProperties(String baseUrl, String serviceToken, String textProvider, int timeoutSeconds,
                                   String tts, String coach, String review, Set<Long> canaryUsers) {
    public AiPlatformProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://127.0.0.1:8091";
        URI uri = URI.create(baseUrl);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Invalid AI Platform URL");
        baseUrl = baseUrl.replaceAll("/$", "");
        if (serviceToken == null) serviceToken = "";
        if (serviceToken.length() > 256 || serviceToken.contains("\r") || serviceToken.contains("\n"))
            throw new IllegalArgumentException("Invalid AI Platform token");
        if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                && (!"https".equals(uri.getScheme()) || serviceToken.isBlank()))
            throw new IllegalArgumentException("Remote AI Platform requires TLS and a service token");
        if (textProvider == null || textProvider.isBlank()) textProvider = "shopaikey";
        if (!Set.of("shopaikey", "ollama").contains(textProvider)) throw new IllegalArgumentException("Invalid AI text provider");
        if (timeoutSeconds == 0) timeoutSeconds = 80;
        if (timeoutSeconds < 1 || timeoutSeconds > 90) throw new IllegalArgumentException("AI job timeout must be 1-90 seconds");
        if (tts == null || tts.isBlank()) tts = "legacy";
        if (coach == null || coach.isBlank()) coach = "legacy";
        if (review == null || review.isBlank()) review = "legacy";
        for (String mode : new String[]{tts, coach, review})
            if (!Set.of("legacy", "shadow", "canary", "python").contains(mode))
                throw new IllegalArgumentException("Invalid AI capability mode");
        canaryUsers = canaryUsers == null ? Set.of() : Set.copyOf(canaryUsers);
        if ((java.util.List.of(tts, coach, review).contains("canary") || java.util.List.of(tts, coach, review).contains("shadow")) && canaryUsers.isEmpty())
            throw new IllegalArgumentException("AI capability pilot requires an allowlist");
    }

    public String mode(String kind) {
        return switch (kind) { case "tts" -> tts; case "coach" -> coach; case "review" -> review;
            default -> throw new IllegalArgumentException("Unsupported AI capability"); };
    }

    @Override public String toString() {
        return "AiPlatformProperties[baseUrl=" + baseUrl + ", textProvider=" + textProvider + ", tts=" + tts
                + ", coach=" + coach + ", review=" + review + ", serviceToken=[REDACTED]]";
    }
}
