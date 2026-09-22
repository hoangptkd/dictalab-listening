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

    public List<TranscriptToken> tokens(String transcript) {
        Matcher matcher = WORD_PATTERN.matcher(transcript);
        List<WordMatch> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(new WordMatch(matcher.group(), matcher.start(), matcher.end()));
        }
        if (matches.isEmpty()) {
            return List.of();
        }

        String[] before = new String[matches.size()];
        String[] after = new String[matches.size()];
        java.util.Arrays.fill(before, "");
        java.util.Arrays.fill(after, "");
        before[0] = withoutWhitespace(transcript.substring(0, matches.get(0).start()));
        for (int index = 0; index < matches.size() - 1; index++) {
            PunctuationGap gap = splitGap(transcript.substring(
                    matches.get(index).end(), matches.get(index + 1).start()
            ));
            after[index] = gap.afterPrevious();
            before[index + 1] = gap.beforeNext();
        }
        after[matches.size() - 1] = withoutWhitespace(
                transcript.substring(matches.get(matches.size() - 1).end())
        );

        List<TranscriptToken> result = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            result.add(new TranscriptToken(matches.get(index).word(), before[index], after[index]));
        }
        return result;
    }

    private PunctuationGap splitGap(String gap) {
        StringBuilder afterPrevious = new StringBuilder();
        StringBuilder beforeNext = new StringBuilder();
        boolean passedWhitespace = false;
        for (int offset = 0; offset < gap.length();) {
            int codePoint = gap.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                passedWhitespace = true;
            } else if (passedWhitespace) {
                beforeNext.appendCodePoint(codePoint);
            } else {
                afterPrevious.appendCodePoint(codePoint);
            }
        }
        return new PunctuationGap(afterPrevious.toString(), beforeNext.toString());
    }

    private String withoutWhitespace(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(result::appendCodePoint);
        return result.toString();
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

    public record TranscriptToken(String word, String punctuationBefore, String punctuationAfter) {
    }

    private record WordMatch(String word, int start, int end) {
    }

    private record PunctuationGap(String afterPrevious, String beforeNext) {
    }
}
