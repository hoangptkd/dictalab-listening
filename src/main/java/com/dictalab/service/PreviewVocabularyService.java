package com.dictalab.service;

import com.dictalab.domain.Lesson;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonPreviewWordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PreviewVocabularyService {

    private final LessonPreviewWordRepository previewWordRepository;
    private final TranscriptTokenizer tokenizer;

    public PreviewVocabularyService(LessonPreviewWordRepository previewWordRepository,
                                    TranscriptTokenizer tokenizer) {
        this.previewWordRepository = previewWordRepository;
        this.tokenizer = tokenizer;
    }

    public List<ApiDtos.PreviewWord> wordsFor(Lesson lesson) {
        return previewWordRepository
                .findByLessonIdOrderByPositionAsc(lesson.getId()).stream()
                .map(word -> new ApiDtos.PreviewWord(
                        word.getWord(), word.getAudioStartSeconds(), word.getAudioEndSeconds(),
                        word.isProperName()
                )).toList();
    }

    public Set<String> normalizedWordsFor(Lesson lesson) {
        return wordsFor(lesson).stream().flatMap(word -> tokenizer.words(word.word()).stream())
                .map(tokenizer::normalize).collect(Collectors.toSet());
    }
}
