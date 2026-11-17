package com.dictalab.service;

import com.dictalab.config.TtsProperties;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Speech endpoint adapter, separate from OpenAI-compatible text generation credentials. */
@Service
public class OpenAiCompatibleTtsClient implements TtsProvider {
    private final TtsProperties.Provider config;
    private final TtsHttpTransport transport;

    public OpenAiCompatibleTtsClient(TtsProperties config, TtsHttpTransport transport) {
        this.config = config.openaiCompatible(); this.transport = transport;
    }
    public String provider() { return "openai_compatible"; }
    public boolean enabled() { return config.configured(); }
    public byte[] synthesize(String text) {
        if (!enabled() || text == null || text.isBlank() || text.length() > 1200)
            throw new IllegalStateException("Audio unavailable");
        return transport.synthesize(provider(), config, Map.of("model", config.model(), "input", text,
                "voice", config.voice(), "response_format", "wav"), Map.of());
    }
}
