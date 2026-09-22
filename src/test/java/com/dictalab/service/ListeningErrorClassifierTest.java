package com.dictalab.service;

import com.dictalab.domain.ListeningErrorCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListeningErrorClassifierTest {

    private final ListeningErrorClassifier classifier = new ListeningErrorClassifier();

    @Test
    void classifiesObservableWordErrors() {
        assertThat(classifier.classify("market", "", List.of("market"), 0))
                .isEqualTo(ListeningErrorCategory.OMISSION);
        assertThat(classifier.classify("buys", "buy", List.of("anna", "buys"), 1))
                .isEqualTo(ListeningErrorCategory.WORD_FORM);
        assertThat(classifier.classify("market", "maret", List.of("market"), 0))
                .isEqualTo(ListeningErrorCategory.SPELLING);
        assertThat(classifier.classify("fresh", "market", List.of("fresh", "market"), 0))
                .isEqualTo(ListeningErrorCategory.WORD_ORDER);
        assertThat(classifier.classify("fresh", "cold", List.of("fresh", "apples"), 0))
                .isEqualTo(ListeningErrorCategory.SUBSTITUTION);
    }
}
