package com.dictalab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "practice_sessions")
public class PracticeSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "learner_id", nullable = false, length = 80)
    private String learnerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "current_sentence_position", nullable = false)
    private int currentSentencePosition;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_replays", nullable = false)
    private int totalReplays;

    @Column(name = "hint_count", nullable = false)
    private int hintCount;

    @Column(name = "reveal_count", nullable = false)
    private int revealCount;

    @Column(name = "known_missed_count", nullable = false)
    private int knownMissedCount;

    @Column(name = "unknown_count", nullable = false)
    private int unknownCount;

    @Column(name = "total_practice_seconds", nullable = false)
    private int totalPracticeSeconds;

    @Column(name = "first_attempt_accuracy", nullable = false)
    private double firstAttemptAccuracy;

    @Column(name = "final_accuracy", nullable = false)
    private double finalAccuracy;

    @Column(name = "overall_score", nullable = false)
    private double overallScore;

    @Column(name = "first_exposure", nullable = false)
    private boolean firstExposure;

    @Version
    private long version;

    protected PracticeSession() {
    }

    public PracticeSession(AppUser user, Lesson lesson, Instant startedAt, boolean firstExposure) {
        this.id = UUID.randomUUID().toString();
        this.user = user;
        this.learnerId = user.getEmail();
        this.lesson = lesson;
        this.status = SessionStatus.IN_PROGRESS;
        this.currentSentencePosition = 1;
        this.startedAt = startedAt;
        this.firstExposure = firstExposure;
    }

    public String getId() {
        return id;
    }

    public String getLearnerId() {
        return learnerId;
    }

    public AppUser getUser() {
        return user;
    }

    public Lesson getLesson() {
        return lesson;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public int getCurrentSentencePosition() {
        return currentSentencePosition;
    }

    public void moveToSentence(int position) {
        this.currentSentencePosition = position;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getTotalReplays() {
        return totalReplays;
    }

    public void incrementReplays() {
        this.totalReplays++;
    }

    public int getHintCount() {
        return hintCount;
    }

    public void incrementHints() {
        this.hintCount++;
    }

    public int getRevealCount() {
        return revealCount;
    }

    public void incrementReveals() {
        this.revealCount++;
    }

    public int getKnownMissedCount() {
        return knownMissedCount;
    }

    public int getUnknownCount() {
        return unknownCount;
    }

    public int getTotalPracticeSeconds() {
        return totalPracticeSeconds;
    }

    public double getFirstAttemptAccuracy() {
        return firstAttemptAccuracy;
    }

    public double getFinalAccuracy() {
        return finalAccuracy;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public boolean isFirstExposure() {
        return firstExposure;
    }

    public void complete(Instant completedAt, int totalPracticeSeconds, double firstAttemptAccuracy,
                         double finalAccuracy, int knownMissedCount, int unknownCount, double overallScore) {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = completedAt;
        this.totalPracticeSeconds = totalPracticeSeconds;
        this.firstAttemptAccuracy = firstAttemptAccuracy;
        this.finalAccuracy = finalAccuracy;
        this.knownMissedCount = knownMissedCount;
        this.unknownCount = unknownCount;
        this.overallScore = overallScore;
    }
}
