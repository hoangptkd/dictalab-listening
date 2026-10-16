package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dictalab.billing")
public record BillingProperties(boolean enabled, @DefaultValue("49000") int priceVnd,
                                @DefaultValue("30") int durationDays, @DefaultValue("3") int freeDailyLessons,
                                @DefaultValue("10") int premiumDailyAi, @DefaultValue("") String publicBaseUrl,
                                @DefaultValue("") String clientId, @DefaultValue("") String apiKey,
                                @DefaultValue("") String checksumKey) {
    public BillingProperties {
        if (priceVnd < 1000 || durationDays < 1 || durationDays > 366 || freeDailyLessons < 1 || premiumDailyAi < 1)
            throw new IllegalArgumentException("Invalid billing plan");
        if (enabled) {
            URI uri = URI.create(publicBaseUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()
                    || clientId.isBlank() || apiKey.isBlank() || checksumKey.isBlank())
                throw new IllegalArgumentException("Billing requires payOS credentials and an HTTPS public origin");
        }
    }

    @Override public String toString() { return "BillingProperties[enabled=" + enabled + "]"; }
}
