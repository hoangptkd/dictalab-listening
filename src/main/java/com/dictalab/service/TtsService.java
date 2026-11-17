package com.dictalab.service;

import com.dictalab.config.TtsProperties;
import java.util.List;
import org.springframework.stereotype.Service;

/** Shared entry point for any feature needing speech, independent of conversation. */
@Service
public class TtsService {
    private final TtsProperties config;
    private final TtsProvider selected;

    public TtsService(TtsProperties config, List<TtsProvider> providers) {
        this.config = config;
        var matches = providers.stream().filter(p -> config.provider().equals(p.provider())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("TTS requires exactly one selected provider");
        selected = matches.get(0);
    }
    public boolean enabled() { return config.enabled() && selected.enabled(); }
    public byte[] synthesize(String text) {
        if (!enabled() || text == null || text.isBlank() || text.length() > 1200)
            throw new IllegalStateException("Audio unavailable");
        return selected.synthesize(text);
    }
}
