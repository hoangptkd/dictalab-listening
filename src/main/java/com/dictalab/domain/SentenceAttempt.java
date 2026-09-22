package com.dictalab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "sentence_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_attempt_session_sentence", columnNames = {"practice_session_id", "sentence_id"})
})
public class SentenceAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practice_session_id", nullable = false)
    private PracticeSession practiceSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sentence_id", nullable = false)
    private LessonSentence sentence;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "time_spent_seconds", nullable = false)
    private int timeSpentSeconds;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "first_letter_hint_count", nullable = false)
    private int firstLetterHintCount;

    @Column(name = "word_reveal_count", nullable = false)
    private int wordRevealCount;

    @Column(name = "full_reveal_used", nullable = false)
    private boolean fullRevealUsed;

    @Column(name = "replayed_after_reveal", nullable = false)
    private boolean replayedAfterReveal;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "playback_event_count", nullable = false)
    private int playbackEventCount;

    @Column(name = "slow_playback_count", nullable = false)
    private int slowPlaybackCount;

    @Column(name = "playback_speed_sum", nullable = false)
    private double playbackSpeedSum;

    protected SentenceAttempt() {
    }

    public SentenceAttempt(PracticeSession practiceSession, LessonSentence sentence, Instant startedAt) {
        this.practiceSession = practiceSession;
        this.sentence = sentence;
        this.startedAt = startedAt;
    }

    public Long getId() {
        return id;
    }

    public PracticeSession getPracticeSession() {
        return practiceSession;
    }

    public LessonSentence getSentence() {
        return sentence;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getTimeSpentSeconds() {
        return timeSpentSeconds;
    }

    public void setTimeSpentSeconds(int timeSpentSeconds) {
        this.timeSpentSeconds = timeSpentSeconds;
    }

    public int getReplayCount() {
        return replayCount;
    }

    public void incrementReplayCount() {
        this.replayCount++;
    }

    public int getFirstLetterHintCount() {
        return firstLetterHintCount;
    }

    public void incrementFirstLetterHintCount() {
        this.firstLetterHintCount++;
    }

    public int getWordRevealCount() {
        return wordRevealCount;
    }

    public void incrementWordRevealCount() {
        this.wordRevealCount++;
    }

    public boolean isFullRevealUsed() {
        return fullRevealUsed;
    }

    public void markFullRevealUsed() {
        this.fullRevealUsed = true;
        this.replayedAfterReveal = false;
    }

    public boolean isReplayedAfterReveal() {
        return replayedAfterReveal;
    }

    public void requireReplayAfterReveal() {
        this.replayedAfterReveal = false;
    }

    public void markReplayCompleted() {
        this.replayedAfterReveal = true;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }

    public boolean hasAnswerReveal() {
        return fullRevealUsed || wordRevealCount > 0;
    }

    public void recordPlayback(double speed) {
        playbackEventCount++;
        playbackSpeedSum += speed;
        if (speed < 0.99) {
            slowPlaybackCount++;
        }
    }

    public int getPlaybackEventCount() {
        return playbackEventCount;
    }

    public int getSlowPlaybackCount() {
        return slowPlaybackCount;
    }

    public double getAveragePlaybackSpeed() {
        return playbackEventCount == 0 ? 1.0 : playbackSpeedSum / playbackEventCount;
    }
}
