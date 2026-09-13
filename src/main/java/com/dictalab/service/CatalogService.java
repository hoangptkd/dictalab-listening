package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final LessonRepository lessonRepository;
    private final LessonSentenceRepository sentenceRepository;

    public CatalogService(LessonRepository lessonRepository, LessonSentenceRepository sentenceRepository) {
        this.lessonRepository = lessonRepository;
        this.sentenceRepository = sentenceRepository;
    }

    public List<ApiDtos.LevelOption> levels() {
        return List.of(
                new ApiDtos.LevelOption(CefrLevel.A1, "A1 · Starter", "Câu ngắn, từ quen thuộc, tốc độ chậm"),
                new ApiDtos.LevelOption(CefrLevel.A2, "A2 · Everyday", "Tình huống hằng ngày và liên kết câu đơn giản"),
                new ApiDtos.LevelOption(CefrLevel.B1, "B1 · Independent", "Ý chính, chi tiết và cách diễn đạt tự nhiên"),
                new ApiDtos.LevelOption(CefrLevel.B2, "B2 · Confident", "Câu dài, ý trừu tượng và tốc độ gần tự nhiên")
        );
    }

    public List<ApiDtos.LessonSummary> lessons(CefrLevel level) {
        return lessonRepository.findByLevelAndActiveTrueOrderByDisplayOrder(level).stream()
                .map(this::toSummary)
                .toList();
    }

    public ApiDtos.LessonSummary toSummary(Lesson lesson) {
        return new ApiDtos.LessonSummary(
                lesson.getId(),
                lesson.getSlug(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getLevel(),
                lesson.getTopic(),
                lesson.getEstimatedMinutes(),
                Math.toIntExact(sentenceRepository.countByLessonId(lesson.getId()))
        );
    }
}
