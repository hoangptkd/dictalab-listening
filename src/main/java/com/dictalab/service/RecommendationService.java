package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class RecommendationService {

    private final LessonRepository lessonRepository;
    private final PracticeSessionRepository sessionRepository;

    public RecommendationService(LessonRepository lessonRepository,
                                 PracticeSessionRepository sessionRepository) {
        this.lessonRepository = lessonRepository;
        this.sessionRepository = sessionRepository;
    }

    public ApiDtos.Recommendation recommend(Long userId) {
        List<PracticeSession> completed = sessionRepository
                .findByUserIdAndStatusAndRoomIdIsNullOrderByCompletedAtDescIdDesc(
                        userId, SessionStatus.COMPLETED, PageRequest.of(0, 1));
        if (completed.isEmpty()) {
            return lessonRepository.findByLevelAndActiveTrueOrderByDisplayOrder(CefrLevel.A1).stream()
                    .findFirst()
                    .map(lesson -> toRecommendation(
                            lesson,
                            "Bắt đầu ở A1 để hệ thống đo first-attempt accuracy của bạn."
                    ))
                    .orElse(null);
        }

        PracticeSession latest = completed.get(0);
        CefrLevel currentLevel = latest.getLesson().getLevel();
        List<PracticeSession> recentAtLevel = sessionRepository
                .findByUserIdAndStatusAndRoomIdIsNullAndLessonLevelOrderByCompletedAtDescIdDesc(
                        userId, SessionStatus.COMPLETED, currentLevel, PageRequest.of(0, 3));
        double recentFirstAccuracy = recentAtLevel.stream()
                .mapToDouble(PracticeSession::getFirstAttemptAccuracy)
                .average()
                .orElse(0);

        CefrLevel targetLevel = currentLevel;
        String reason;
        if (recentFirstAccuracy >= 85 && currentLevel != CefrLevel.B2) {
            targetLevel = currentLevel.harder();
            reason = "First-attempt accuracy gần đây đạt " + round(recentFirstAccuracy)
                    + "%, phù hợp để tăng lên " + targetLevel + ".";
        } else if (recentFirstAccuracy < 55 && currentLevel != CefrLevel.A1) {
            targetLevel = currentLevel.easier();
            reason = "First-attempt accuracy gần đây là " + round(recentFirstAccuracy)
                    + "%; một bài " + targetLevel + " sẽ giúp củng cố khả năng nhận âm.";
        } else {
            reason = "Duy trì " + currentLevel + " để đưa first-attempt accuracy lên trên 85%.";
        }

        Set<Long> completedLessonIds = new HashSet<>(sessionRepository.completedSoloLessonIds(
                userId, SessionStatus.COMPLETED, targetLevel));
        List<Lesson> candidates = lessonRepository.findByLevelAndActiveTrueOrderByDisplayOrder(targetLevel);
        Lesson selected = candidates.stream()
                .filter(lesson -> !completedLessonIds.contains(lesson.getId()))
                .findFirst()
                .orElseGet(() -> candidates.isEmpty() ? null : candidates.get(0));
        return selected == null ? null : toRecommendation(selected, reason);
    }

    private ApiDtos.Recommendation toRecommendation(Lesson lesson, String reason) {
        return new ApiDtos.Recommendation(lesson.getId(), lesson.getTitle(), lesson.getLevel(), reason);
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
