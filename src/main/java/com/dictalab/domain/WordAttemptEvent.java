package com.dictalab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "word_attempt_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_word_attempt_event_number", columnNames = {"word_attempt_id", "attempt_number"})
})
public class WordAttemptEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "word_attempt_id", nullable = false)
    private WordAttempt wordAttempt;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "raw_answer", nullable = false, length = 200)
    private String rawAnswer;

    @Column(name = "normalized_answer", nullable = false, length = 200)
    private String normalizedAnswer;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "response_time_ms", nullable = false)
    private long responseTimeMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_category", length = 30)
    private ListeningErrorCategory errorCategory;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WordAttemptEvent() {
    }

    public WordAttemptEvent(WordAttempt wordAttempt, int attemptNumber, String rawAnswer,
                            String normalizedAnswer, boolean correct, long responseTimeMs,
                            ListeningErrorCategory errorCategory, Instant createdAt) {
        this.wordAttempt = wordAttempt;
        this.attemptNumber = attemptNumber;
        this.rawAnswer = rawAnswer;
        this.normalizedAnswer = normalizedAnswer;
        this.correct = correct;
        this.responseTimeMs = responseTimeMs;
        this.errorCategory = errorCategory;
        this.createdAt = createdAt;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public WordAttempt getWordAttempt() {
        return wordAttempt;
    }

    public String getRawAnswer() {
        return rawAnswer;
    }

    public String getNormalizedAnswer() {
        return normalizedAnswer;
    }

    public boolean isCorrect() {
        return correct;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public ListeningErrorCategory getErrorCategory() {
        return errorCategory;
    }
}
