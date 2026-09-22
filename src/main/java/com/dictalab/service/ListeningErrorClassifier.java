package com.dictalab.service;

import com.dictalab.domain.ListeningErrorCategory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ListeningErrorClassifier {

    public ListeningErrorCategory classify(String expected, String answer,
                                            List<String> sentenceWords, int wordIndex) {
        if (answer.isBlank()) {
            return ListeningErrorCategory.OMISSION;
        }
        for (int index = 0; index < sentenceWords.size(); index++) {
            if (index != wordIndex && answer.equals(sentenceWords.get(index))) {
                return ListeningErrorCategory.WORD_ORDER;
            }
        }
        if (sameWordFamily(expected, answer)) {
            return ListeningErrorCategory.WORD_FORM;
        }
        int spellingThreshold = Math.max(1, Math.min(2, expected.length() / 4));
        if (levenshtein(expected, answer) <= spellingThreshold) {
            return ListeningErrorCategory.SPELLING;
        }
        return ListeningErrorCategory.SUBSTITUTION;
    }

    private boolean sameWordFamily(String left, String right) {
        if (Math.abs(left.length() - right.length()) > 4) {
            return false;
        }
        return stems(left).stream().anyMatch(stems(right)::contains);
    }

    private List<String> stems(String word) {
        String base = word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
        String past = word.length() > 4 && word.endsWith("ed") ? word.substring(0, word.length() - 2) : word;
        String continuous = word.length() > 5 && word.endsWith("ing") ? word.substring(0, word.length() - 3) : word;
        return List.of(base, past, continuous);
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            int[] current = new int[right.length() + 1];
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int replacement = previous[rightIndex - 1]
                        + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        replacement
                );
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
