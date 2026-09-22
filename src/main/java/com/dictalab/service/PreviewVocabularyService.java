package com.dictalab.service;

import com.dictalab.domain.Lesson;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonPreviewWordRepository;
import com.dictalab.repository.LessonSentenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PreviewVocabularyService {

    private final LessonPreviewWordRepository previewWordRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final TranscriptTokenizer tokenizer;

    public PreviewVocabularyService(LessonPreviewWordRepository previewWordRepository,
                                    LessonSentenceRepository sentenceRepository,
                                    TranscriptTokenizer tokenizer) {
        this.previewWordRepository = previewWordRepository;
        this.sentenceRepository = sentenceRepository;
        this.tokenizer = tokenizer;
    }

    public List<ApiDtos.PreviewWord> wordsFor(Lesson lesson) {
        List<ApiDtos.PreviewWord> stored = previewWordRepository
                .findByLessonIdOrderByPositionAsc(lesson.getId()).stream()
                .map(word -> new ApiDtos.PreviewWord(
                        word.getWord(), word.getAudioStartSeconds(), word.getAudioEndSeconds()
                )).toList();
        if (!stored.isEmpty()) {
            return stored;
        }

        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> displayWords = new LinkedHashMap<>();
        sentenceRepository.findByLessonIdOrderByPositionAsc(lesson.getId()).stream()
                .flatMap(sentence -> tokenizer.words(sentence.getTranscript()).stream())
                .forEach(word -> {
                    String normalized = tokenizer.normalize(word);
                    counts.merge(normalized, 1, Integer::sum);
                    displayWords.putIfAbsent(normalized, word.toLowerCase(Locale.ROOT));
                });
        int target = Math.min(5, lesson.getLevel().ordinal() + 2);
        int minimumLength = lesson.getLevel().ordinal() + 6;
        List<ApiDtos.PreviewWord> candidates = displayWords.entrySet().stream()
                .filter(entry -> entry.getKey().length() >= minimumLength)
                .filter(entry -> !tokenizer.isFunctionWord(entry.getKey()))
                .sorted(Comparator.<Map.Entry<String, String>>comparingInt(entry ->
                        entry.getKey().length() * 2 + (counts.get(entry.getKey()) == 1 ? 8 : 0)
                                + (entry.getKey().length() >= minimumLength ? 12 : 0)
                ).reversed())
                .limit(target)
                .map(entry -> new ApiDtos.PreviewWord(entry.getValue(), null, null))
                .toList();
        return candidates.size() < 2 ? List.of() : candidates;
    }

    public Set<String> normalizedWordsFor(Lesson lesson) {
        return wordsFor(lesson).stream().map(ApiDtos.PreviewWord::word)
                .map(tokenizer::normalize).collect(Collectors.toSet());
    }
}
