package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final LessonRepository lessonRepository;
    private final PreviewVocabularyService previewVocabularyService;
    private final io.micrometer.core.instrument.Timer summaryRead;
    private final io.micrometer.core.instrument.Timer previewRead;

    public CatalogService(LessonRepository lessonRepository,
                          PreviewVocabularyService previewVocabularyService,
                          io.micrometer.core.instrument.MeterRegistry metrics) {
        this.lessonRepository = lessonRepository;
        this.previewVocabularyService = previewVocabularyService;
        summaryRead = metrics.timer("dictalab.catalog.read", "part", "summary");
        previewRead = metrics.timer("dictalab.catalog.read", "part", "preview");
    }

    public List<ApiDtos.LevelOption> levels() {
        return List.of(
                new ApiDtos.LevelOption(CefrLevel.A1, "A1 · Starter", "Câu ngắn, từ quen thuộc, tốc độ chậm"),
                new ApiDtos.LevelOption(CefrLevel.A2, "A2 · Everyday", "Tình huống hằng ngày và liên kết câu đơn giản"),
                new ApiDtos.LevelOption(CefrLevel.B1, "B1 · Independent", "Ý chính, chi tiết và cách diễn đạt tự nhiên"),
                new ApiDtos.LevelOption(CefrLevel.B2, "B2 · Confident", "Câu dài, ý trừu tượng và tốc độ gần tự nhiên")
        );
    }

    public List<ApiDtos.LessonSummary> lessons(Long userId, CefrLevel level) {
        return lessons(userId, level, false);
    }

    public List<ApiDtos.LessonSummary> lessons(Long userId, CefrLevel level, boolean administrator) {
        var lessons = summaryRead.record(() -> lessonRepository.catalog(userId, level == null ? "ALL" : level.name(), administrator));
        var ids = lessons.stream().map(LessonRepository.CatalogLesson::getId).toList();
        var preview = previewRead.record(() -> previewVocabularyService.wordsForLessons(ids));
        return lessons.stream()
                .map(lesson -> toSummary(lesson, preview.getOrDefault(lesson.getId(), List.of())))
                .toList();
    }

    private ApiDtos.LessonSummary toSummary(LessonRepository.CatalogLesson lesson, List<ApiDtos.PreviewWord> preview) {
        int sentenceCount = Math.toIntExact(lesson.getSentenceCount());
        boolean completed = lesson.getCompleted();
        Integer activePosition = lesson.getActivePosition();
        int completedSentences = activePosition == null
                ? (completed ? sentenceCount : 0)
                : Math.max(0, Math.min(sentenceCount, activePosition - 1));
        int progressPercent = sentenceCount == 0
                ? 0
                : (int) Math.round(completedSentences * 100.0 / sentenceCount);
        return new ApiDtos.LessonSummary(
                lesson.getId(),
                lesson.getSlug(),
                lesson.getTitle(),
                lesson.getDescription(),
                CefrLevel.valueOf(lesson.getLevel()),
                lesson.getTopic(),
                lesson.getEstimatedMinutes(),
                sentenceCount,
                progressPercent,
                activePosition != null,
                completed,
                lesson.getAudioUrl(),
                preview
        );
    }
}
