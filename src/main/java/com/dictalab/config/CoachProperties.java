package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("dictalab.coach")
public record CoachProperties(boolean enabled, URI url, String model, String apiKey, Duration timeout) {
    public CoachProperties {
        if (url == null) url = URI.create("http://127.0.0.1:3000/v1/chat/completions");
        if (model == null || model.isBlank()) model = "chatgpt-web";
        if (apiKey == null) apiKey = "";
        if (timeout == null) timeout = Duration.ofSeconds(75);
        if (!java.util.Set.of("http", "https").contains(url.getScheme()) || url.getHost() == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Invalid coach URL or timeout");
        }
    }
}
