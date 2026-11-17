package com.dictalab.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TTS configuration is independent of conversation and text generation. */
@ConfigurationProperties("dictalab.tts")
public record TtsProperties(@org.springframework.boot.context.properties.bind.DefaultValue("true") boolean enabled,
                            String provider, Provider cartesia, Provider openaiCompatible) {
    public TtsProperties {
        if (provider == null || provider.isBlank()) provider = "cartesia";
        if (!Set.of("cartesia", "openai_compatible").contains(provider))
            throw new IllegalArgumentException("Unsupported TTS provider");
        if (cartesia == null) cartesia = new Provider("https://api.cartesia.ai/tts/bytes", "", "sonic-3.6",
                "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4", "2026-08-14");
        if (openaiCompatible == null) openaiCompatible = new Provider("https://api.openai.com/v1/audio/speech", "", "", "", "");
    }

    public record Provider(String url, String apiKey, String model, String voice, String apiVersion) {
        public Provider {
            if (apiKey == null) apiKey = "";
            if (model == null) model = "";
            if (voice == null) voice = "";
            if (apiVersion == null) apiVersion = "";
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
                throw new IllegalArgumentException("TTS URL requires TLS except loopback");
            if (!apiKey.isEmpty() && !apiKey.matches("[!-~]{1,512}")
                    || !apiVersion.isEmpty() && !apiVersion.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("Invalid TTS credentials or API version");
            if (!model.isEmpty() && !model.matches("[a-zA-Z0-9_.:/-]{1,100}")
                    || !voice.isEmpty() && !voice.matches("[a-zA-Z0-9_.-]{1,100}"))
                throw new IllegalArgumentException("Invalid TTS model or voice");
        }
        public boolean configured() { return !apiKey.isBlank() && !model.isBlank() && !voice.isBlank(); }
        @Override public String toString() { return "TtsProvider[credentials=REDACTED]"; }
    }
}
