package com.dictalab.service;

import com.dictalab.config.TtsProperties;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CartesiaClient implements TtsProvider {
    private final TtsProperties.Provider config;
    private final TtsHttpTransport transport;

    public CartesiaClient(TtsProperties config, TtsHttpTransport transport) {
        this.config = config.cartesia(); this.transport = transport;
    }
    public String provider() { return "cartesia"; }
    public boolean enabled() { return config.configured() && !config.apiVersion().isBlank(); }
    public byte[] synthesize(String text) {
        if (!enabled() || text == null || text.isBlank() || text.length() > 1200)
            throw new IllegalStateException("Audio unavailable");
        return transport.synthesize(provider(), config, Map.of("model_id", config.model(), "transcript", text,
                "voice", Map.of("mode", "id", "id", config.voice()), "language", "en",
                "output_format", Map.of("container", "wav", "encoding", "pcm_s16le", "sample_rate", 24000)),
                Map.of("Cartesia-Version", config.apiVersion()));
    }
}
