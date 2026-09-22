package com.dictalab.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

@Component
public class TranscriptTokenizer {

    private static final Set<String> FUNCTION_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "because", "as", "than", "that",
            "this", "these", "those", "i", "you", "he", "she", "it", "we", "they", "me",
            "him", "her", "us", "them", "my", "your", "his", "its", "our", "their", "mine",
            "yours", "hers", "ours", "theirs", "am", "is", "are", "was", "were", "be", "been",
            "being", "do", "does", "did", "have", "has", "had", "can", "could", "may", "might",
            "must", "shall", "should", "will", "would", "to", "of", "in", "on", "at", "by",
            "for", "from", "with", "about", "into", "over", "after", "before", "between", "under",
            "again", "then", "there", "here", "not", "no", "nor", "so", "very", "too"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*"
    );

    public List<String> words(String transcript) {
        Matcher matcher = WORD_PATTERN.matcher(transcript);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .replace('’', '\'')
                .toLowerCase(Locale.ROOT);
    }

    public int visibleLength(String word) {
        return word.codePointCount(0, word.length());
    }

    public String mask(String word) {
        StringBuilder result = new StringBuilder();
        word.codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                result.append('_');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    public String firstLetter(String word) {
        if (word.isBlank()) {
            return "";
        }
        int first = word.codePointAt(0);
        return new String(Character.toChars(first));
    }

    public boolean isFunctionWord(String word) {
        return FUNCTION_WORDS.contains(normalize(word));
    }
}
