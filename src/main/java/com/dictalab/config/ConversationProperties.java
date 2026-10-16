package com.dictalab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dictalab.conversation")
public record ConversationProperties(boolean enabled, String generationModel, String cartesiaApiKey, String cartesiaVoice,
                                     String cartesiaModel, String cartesiaVersion) {
    public ConversationProperties {
        if (generationModel == null || generationModel.isBlank()) generationModel = "gemini-2.5-flash";
        if (cartesiaApiKey == null) cartesiaApiKey = "";
        if (cartesiaVoice == null) cartesiaVoice = "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4";
        if (cartesiaModel == null) cartesiaModel = "sonic-3.6";
        if (cartesiaVersion == null) cartesiaVersion = "2026-08-14";
    }

    // Records otherwise include credentials in their generated toString().
    @Override public String toString() { return "ConversationProperties[enabled=" + enabled + "]"; }
}
