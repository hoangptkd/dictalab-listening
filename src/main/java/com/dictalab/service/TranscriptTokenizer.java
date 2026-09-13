package com.dictalab.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TranscriptTokenizer {

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
}
