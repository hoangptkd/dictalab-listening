package com.dictalab.service;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.SessionStatus;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final PracticeSessionRepository sessionRepository;
    private final RecommendationService recommendationService;
    private final ListeningAssessmentService assessmentService;
    private final ProgressClient progress;

    public DashboardService(PracticeSessionRepository sessionRepository,
                            RecommendationService recommendationService,
                            ListeningAssessmentService assessmentService, ProgressClient progress) {
        this.sessionRepository = sessionRepository;
        this.recommendationService = recommendationService;
        this.assessmentService = assessmentService;
        this.progress = progress;
    }

    public ApiDtos.DashboardResponse dashboard(Long userId) {
        if (progress.mode().equals("remote")) {
            var projection = progress.fetch(userId);
            var recent = sessionRepository.findByUserIdAndStatusAndRoomIdIsNullOrderByCompletedAtDescIdDesc(
                    userId, SessionStatus.COMPLETED, PageRequest.of(0, 1));
            return new ApiDtos.DashboardResponse(projection.summary(), projection.trend(), projection.byLevel(),
                    projection.errors(), recommendationService.recommend(userId, recent.isEmpty() ? null : recent.get(0)),
                    assessmentService.assess(userId),
                    new ApiDtos.ProgressMetadata("progress", projection.updatedAt(), projection.lastEventAt()));
        }
        var totals = sessionRepository.dashboardTotals(userId, SessionStatus.COMPLETED);
        long count = totals.stream().mapToLong(PracticeSessionRepository.LevelTotals::getCompletedLessons).sum();
        long seconds = totals.stream().mapToLong(PracticeSessionRepository.LevelTotals::getPracticeSeconds).sum();
        var summary = new ApiDtos.DashboardSummary(
                Math.toIntExact(count), Math.toIntExact(Math.round(seconds / 60.0)),
                average(totals.stream().mapToDouble(PracticeSessionRepository.LevelTotals::getFirstAccuracySum).sum(), count),
                average(totals.stream().mapToDouble(PracticeSessionRepository.LevelTotals::getFinalAccuracySum).sum(), count),
                average(totals.stream().mapToDouble(PracticeSessionRepository.LevelTotals::getScoreSum).sum(), count));

        // The UI displays eight points; lifetime totals above still include every solo session.
        var recent = new ArrayList<>(sessionRepository
                .findByUserIdAndStatusAndRoomIdIsNullOrderByCompletedAtDescIdDesc(
                        userId, SessionStatus.COMPLETED, PageRequest.of(0, 8)));
        Collections.reverse(recent);
        var trend = recent.stream()
                .map(session -> new ApiDtos.TrendPoint(
                        session.getId(),
                        session.getCompletedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        session.getLesson().getLevel(),
                        session.getLesson().getTitle(),
                        session.getFirstAttemptAccuracy(),
                        session.getFinalAccuracy(),
                        session.getOverallScore()
                ))
                .toList();

        var totalsByLevel = new EnumMap<CefrLevel, PracticeSessionRepository.LevelTotals>(CefrLevel.class);
        totals.forEach(total -> totalsByLevel.put(total.getLevel(), total));
        var byLevel = Arrays.stream(CefrLevel.values())
                .map(level -> {
                    var total = totalsByLevel.get(level);
                    return total == null ? new ApiDtos.LevelProgress(level, 0, 0, 0)
                            : new ApiDtos.LevelProgress(level, Math.toIntExact(total.getCompletedLessons()),
                                    average(total.getFirstAccuracySum(), total.getCompletedLessons()),
                                    average(total.getScoreSum(), total.getCompletedLessons()));
                })
                .toList();

        ApiDtos.ErrorBreakdown errors = new ApiDtos.ErrorBreakdown(
                Math.toIntExact(totals.stream().mapToLong(PracticeSessionRepository.LevelTotals::getKnownMissed).sum()),
                Math.toIntExact(totals.stream().mapToLong(PracticeSessionRepository.LevelTotals::getUnknownWords).sum())
        );
        var response = new ApiDtos.DashboardResponse(
                summary, trend, byLevel, errors, recommendationService.recommend(userId,
                        recent.isEmpty() ? null : recent.get(recent.size() - 1)),
                assessmentService.assess(userId), new ApiDtos.ProgressMetadata("core", java.time.Instant.now(), null)
        );
        if (progress.mode().equals("shadow")) progress.compare(userId, response);
        return response;
    }

    private double average(double sum, long count) {
        return count == 0 ? 0 : Math.round(sum / count * 10.0) / 10.0;
    }
}
