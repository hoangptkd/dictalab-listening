package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dictalab.payment-operations")
public record PaymentOperationsProperties(@DefaultValue("true") boolean checkoutEnabled,
        @DefaultValue("true") boolean intakeEnabled, @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("true") boolean reconcileEnabled, @DefaultValue("false") boolean refundsEnabled,
        @DefaultValue("false") boolean partialRefundsEnabled, @DefaultValue("") String encryptionKey,
        @DefaultValue("") String bootstrapEmail, @DefaultValue("MANUAL_REVIEW_V1") String policyVersion) {
    public PaymentOperationsProperties {
        if (!encryptionKey.isBlank() && java.util.Base64.getDecoder().decode(encryptionKey).length!=32)
            throw new IllegalArgumentException("Payment encryption key must be 256 bits");
        if (!policyVersion.matches("[A-Z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid payment policy version");
    }
    @Override public String toString() { return "PaymentOperationsProperties[workerEnabled="+workerEnabled+"]"; }
}
