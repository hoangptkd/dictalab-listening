package com.dictalab.dto;

import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.HintType;
import com.dictalab.domain.LearnerErrorType;
import com.dictalab.domain.ReplayEventType;
import com.dictalab.domain.WordLearningOutcome;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record LevelOption(CefrLevel code, String label, String description) {
    }

    public record LessonSummary(
            Long id,
            String slug,
            String title,
            String description,
            CefrLevel level,
            String topic,
            int estimatedMinutes,
            int sentenceCount
    ) {
    }

    public record StartSessionRequest(
            @NotNull @Positive Long lessonId
    ) {
    }

    public record CheckWordRequest(
            @NotBlank @Size(max = 100) String answer
    ) {
    }

    public record HintRequest(
            @NotNull HintType type,
            @Min(0) Integer wordIndex
    ) {
    }

    public record ReplayRequest(@NotNull ReplayEventType event) {
    }

    public record ClassifyWordRequest(
            @NotNull @Min(0) Integer wordIndex,
            @NotNull LearnerErrorType errorType
    ) {
    }

    public record CompleteSentenceRequest(
            @Min(0) @Max(7200) int timeSpentSeconds
    ) {
    }

    public record WordSlot(
            int index,
            int length,
            String mask,
            String firstLetter,
            String revealedText,
            String userInput,
            boolean correct,
            boolean revealed,
            LearnerErrorType errorType
    ) {
    }

    public record SentenceState(
            Long sentenceId,
            int position,
            int totalSentences,
            List<WordSlot> words,
            String speechText,
            String audioUrl,
            Double audioStartSeconds,
            Double audioEndSeconds,
            double speechRate,
            int replayCount,
            int hintCount,
            int revealCount,
            boolean hasListened,
            boolean requiresReplayAfterReveal,
            boolean canComplete
    ) {
    }

    public record PracticeStartResponse(
            String sessionId,
            Long lessonId,
            String lessonTitle,
            CefrLevel level,
            SentenceState sentence
    ) {
    }

    public record LessonResult(
            String sessionId,
            Long lessonId,
            String lessonTitle,
            CefrLevel level,
            double firstAttemptAccuracy,
            double finalAccuracy,
            int replayCount,
            int hintCount,
            int revealCount,
            int knownButMissed,
            int unknownWords,
            int practiceSeconds,
            double overallScore,
            Instant completedAt,
            LessonReview review
    ) {
    }

    public record LessonReview(
            String sourceName,
            String sourceUrl,
            String topic,
            int totalSentences,
            int totalWords,
            List<SentenceReview> sentences
    ) {
    }

    public record SentenceReview(
            Long sentenceId,
            int position,
            String transcript,
            String audioUrl,
            Double audioStartSeconds,
            Double audioEndSeconds,
            int replayCount,
            int timeSpentSeconds,
            int firstLetterHintCount,
            int wordRevealCount,
            boolean fullRevealUsed,
            List<WordReview> words
    ) {
    }

    public record WordReview(
            int index,
            String expectedWord,
            String firstAnswer,
            String lastAnswer,
            int attemptCount,
            WordLearningOutcome outcome,
            LearnerErrorType errorType
    ) {
    }

    public record CompleteSentenceResponse(
            boolean lessonCompleted,
            SentenceState nextSentence,
            LessonResult result
    ) {
    }

    public record DashboardSummary(
            int completedLessons,
            int learningMinutes,
            double averageFirstAttemptAccuracy,
            double averageFinalAccuracy,
            double averageScore
    ) {
    }

    public record TrendPoint(
            LocalDate date,
            CefrLevel level,
            String lessonTitle,
            double firstAttemptAccuracy,
            double finalAccuracy,
            double overallScore
    ) {
    }

    public record LevelProgress(
            CefrLevel level,
            int completedLessons,
            double averageFirstAttemptAccuracy,
            double averageScore
    ) {
    }

    public record ErrorBreakdown(int knownButMissed, int unknownWords) {
    }

    public record Recommendation(
            Long lessonId,
            String lessonTitle,
            CefrLevel level,
            String reason
    ) {
    }

    public record DashboardResponse(
            DashboardSummary summary,
            List<TrendPoint> trend,
            List<LevelProgress> byLevel,
            ErrorBreakdown errors,
            Recommendation recommendation
    ) {
    }

    public record ApiError(int status, String message, String path, Instant timestamp) {
    }
}
