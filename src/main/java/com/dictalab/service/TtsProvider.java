package com.dictalab.service;

/** Providers return validated WAV PCM16 mono 24 kHz; callers own authorization and admission. */
public interface TtsProvider {
    String provider();
    boolean enabled();
    byte[] synthesize(String text);
}
