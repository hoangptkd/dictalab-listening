package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SessionStatus;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final PracticeSessionRepository sessionRepository;
    private final RecommendationService recommendationService;

    public DashboardService(PracticeSessionRepository sessionRepository,
                            RecommendationService recommendationService) {
        this.sessionRepository = sessionRepository;
        this.recommendationService = recommendationService;
    }

    public ApiDtos.DashboardResponse dashboard(Long userId) {
        List<PracticeSession> sessions = sessionRepository
                .findByUserIdAndStatusOrderByCompletedAtAsc(userId, SessionStatus.COMPLETED);

        ApiDtos.DashboardSummary summary = new ApiDtos.DashboardSummary(
                sessions.size(),
                (int) Math.round(sessions.stream().mapToInt(PracticeSession::getTotalPracticeSeconds).sum() / 60.0),
                average(sessions, Metric.FIRST),
                average(sessions, Metric.FINAL),
                average(sessions, Metric.SCORE)
        );

        List<ApiDtos.TrendPoint> trend = sessions.stream()
                .map(session -> new ApiDtos.TrendPoint(
                        session.getCompletedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        session.getLesson().getLevel(),
                        session.getLesson().getTitle(),
                        session.getFirstAttemptAccuracy(),
                        session.getFinalAccuracy(),
                        session.getOverallScore()
                ))
                .toList();

        List<ApiDtos.LevelProgress> byLevel = Arrays.stream(CefrLevel.values())
                .map(level -> {
                    List<PracticeSession> levelSessions = sessions.stream()
                            .filter(session -> session.getLesson().getLevel() == level)
                            .toList();
                    return new ApiDtos.LevelProgress(
                            level,
                            levelSessions.size(),
                            average(levelSessions, Metric.FIRST),
                            average(levelSessions, Metric.SCORE)
                    );
                })
                .toList();

        ApiDtos.ErrorBreakdown errors = new ApiDtos.ErrorBreakdown(
                sessions.stream().mapToInt(PracticeSession::getKnownMissedCount).sum(),
                sessions.stream().mapToInt(PracticeSession::getUnknownCount).sum()
        );
        return new ApiDtos.DashboardResponse(
                summary, trend, byLevel, errors, recommendationService.recommend(userId)
        );
    }

    private double average(List<PracticeSession> sessions, Metric metric) {
        if (sessions.isEmpty()) {
            return 0;
        }
        double value = sessions.stream().mapToDouble(session -> switch (metric) {
            case FIRST -> session.getFirstAttemptAccuracy();
            case FINAL -> session.getFinalAccuracy();
            case SCORE -> session.getOverallScore();
        }).average().orElse(0);
        return Math.round(value * 10.0) / 10.0;
    }

    private enum Metric {
        FIRST,
        FINAL,
        SCORE
    }
}
