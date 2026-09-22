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

    @Test
    void keepsTranscriptPunctuationAttachedToTheCorrectWord() {
        var tokens = tokenizer.tokens(
                "So, today's expert teacher is Gabriella, a university English teacher from Leeds."
        );

        assertThat(tokens.get(0))
                .isEqualTo(new TranscriptTokenizer.TranscriptToken("So", "", ","));
        assertThat(tokens.get(5))
                .isEqualTo(new TranscriptTokenizer.TranscriptToken("Gabriella", "", ","));
        assertThat(tokens.get(11))
                .isEqualTo(new TranscriptTokenizer.TranscriptToken("Leeds", "", "."));
    }
}
