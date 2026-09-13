package com.dictalab.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptTokenizerTest {

    private final TranscriptTokenizer tokenizer = new TranscriptTokenizer();

    @Test
    void keepsApostrophesAndHyphensInsideWords() {
        assertThat(tokenizer.words("Don't miss a well-known phrase."))
                .containsExactly("Don't", "miss", "a", "well-known", "phrase");
    }

    @Test
    void comparesWordsWithoutCaseDifferences() {
        assertThat(tokenizer.normalize("  MARKET  ")).isEqualTo("market");
        assertThat(tokenizer.mask("don't")).isEqualTo("___'_");
    }
}
