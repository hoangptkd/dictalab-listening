package com.dictalab.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.conversation.generation.rollout")
public record ConversationRolloutProperties(boolean canaryEnabled, Set<Long> canaryUsers,
        Set<String> canarySessions, double shadowSampleRate, int shadowMaxRequestsPerHour,
        int shadowTimeoutSeconds) {
    public ConversationRolloutProperties {
        canaryUsers = canaryUsers == null ? Set.of() : Set.copyOf(canaryUsers);
        canarySessions = canarySessions == null ? Set.of() : Set.copyOf(canarySessions);
        if (canaryEnabled && canaryUsers.isEmpty() && canarySessions.isEmpty())
            throw new IllegalArgumentException("Canary requires an explicit user or session allowlist");
        if (!Double.isFinite(shadowSampleRate) || shadowSampleRate < 0 || shadowSampleRate > 1)
            throw new IllegalArgumentException("Shadow sample rate must be between 0 and 1");
        if (shadowMaxRequestsPerHour == 0) shadowMaxRequestsPerHour = 10;
        if (shadowMaxRequestsPerHour < 1 || shadowMaxRequestsPerHour > 1000)
            throw new IllegalArgumentException("Invalid shadow hourly budget");
        if (shadowTimeoutSeconds == 0) shadowTimeoutSeconds = 30;
        if (shadowTimeoutSeconds < 1 || shadowTimeoutSeconds > 180)
            throw new IllegalArgumentException("Invalid shadow timeout");
    }
}
