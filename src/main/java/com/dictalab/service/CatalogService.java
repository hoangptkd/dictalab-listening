package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final LessonRepository lessonRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final PracticeSessionRepository sessionRepository;
    private final PreviewVocabularyService previewVocabularyService;

    public CatalogService(LessonRepository lessonRepository, LessonSentenceRepository sentenceRepository,
                          PracticeSessionRepository sessionRepository,
                          PreviewVocabularyService previewVocabularyService) {
        this.lessonRepository = lessonRepository;
        this.sentenceRepository = sentenceRepository;
        this.sessionRepository = sessionRepository;
        this.previewVocabularyService = previewVocabularyService;
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
        Map<Long, PracticeSession> activeSessions = new HashMap<>();
        Set<Long> completedLessonIds = new HashSet<>();
        if (userId != null) {
            sessionRepository.findByUserIdAndLessonLevelAndStatusOrderByStartedAtDesc(
                            userId, level, SessionStatus.IN_PROGRESS
                    ).forEach(session -> activeSessions.putIfAbsent(session.getLesson().getId(), session));
            completedLessonIds.addAll(sessionRepository.findDistinctLessonIds(
                    userId, level, SessionStatus.COMPLETED
            ));
        }
        var lessons=lessonRepository.findByLevelAndActiveTrueOrderByDisplayOrder(level).stream()
                .filter(lesson -> administrator || !lesson.isPersonalContent()).toList();
        var ids=lessons.stream().map(Lesson::getId).toList();
        Map<Long,LessonSentenceRepository.Summary> summaries=new HashMap<>();
        if(!ids.isEmpty()) sentenceRepository.summaries(ids).forEach(s -> summaries.put(s.getLessonId(),s));
        var preview=previewVocabularyService.wordsForLessons(ids);
        return lessons.stream()
                .map(lesson -> toSummary(
                        lesson,
                        activeSessions.get(lesson.getId()),
                        completedLessonIds.contains(lesson.getId()),
                        summaries.get(lesson.getId()),preview.getOrDefault(lesson.getId(),List.of())
                ))
                .toList();
    }

    private ApiDtos.LessonSummary toSummary(Lesson lesson, PracticeSession activeSession, boolean completed,
                                           LessonSentenceRepository.Summary summary,List<ApiDtos.PreviewWord> preview) {
        int sentenceCount = summary==null ? 0 : Math.toIntExact(summary.getSentenceCount());
        int completedSentences = activeSession == null
                ? (completed ? sentenceCount : 0)
                : Math.max(0, Math.min(sentenceCount, activeSession.getCurrentSentencePosition() - 1));
        int progressPercent = sentenceCount == 0
                ? 0
                : (int) Math.round(completedSentences * 100.0 / sentenceCount);
        return new ApiDtos.LessonSummary(
                lesson.getId(),
                lesson.getSlug(),
                lesson.getTitle(),
                lesson.getDescription(),
                lesson.getLevel(),
                lesson.getTopic(),
                lesson.getEstimatedMinutes(),
                sentenceCount,
                progressPercent,
                activeSession != null,
                completed,
                summary==null ? null : summary.getAudioUrl(),
                preview
        );
    }
}
