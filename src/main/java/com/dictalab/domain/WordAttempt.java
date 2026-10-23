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

@Entity
@Table(name = "word_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_word_attempt_index", columnNames = {"sentence_attempt_id", "word_index"})
})
public class WordAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sentence_attempt_id", nullable = false)
    private SentenceAttempt sentenceAttempt;

    @Column(name = "word_index", nullable = false)
    private int wordIndex;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "first_attempt_correct")
    private Boolean firstAttemptCorrect;

    @Column(name = "first_attempt_assisted", nullable = false)
    private boolean firstAttemptAssisted;

    @Column(name = "final_correct", nullable = false)
    private boolean finalCorrect;

    @Column(name = "first_letter_hint", nullable = false)
    private boolean firstLetterHint;

    @Column(nullable = false)
    private boolean revealed;

    @Column(name = "last_answer", length = 200)
    private String lastAnswer;

    @Column(name = "first_answer", length = 200)
    private String firstAnswer;

    @Enumerated(EnumType.STRING)
    @Column(name = "reveal_type", length = 20)
    private HintType revealType;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 30)
    private LearnerErrorType errorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "learner_reported_error_type", length = 30)
    private LearnerErrorType learnerReportedErrorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "inferred_error_category", length = 30)
    private ListeningErrorCategory inferredErrorCategory;

    protected WordAttempt() {
    }

    public WordAttempt(SentenceAttempt sentenceAttempt, int wordIndex) {
        this.sentenceAttempt = sentenceAttempt;
        this.wordIndex = wordIndex;
    }

    public Long getId() {
        return id;
    }

    public SentenceAttempt getSentenceAttempt() {
        return sentenceAttempt;
    }

    public int getWordIndex() {
        return wordIndex;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Boolean getFirstAttemptCorrect() {
        return firstAttemptCorrect;
    }

    public boolean isFinalCorrect() {
        return finalCorrect;
    }

    public boolean hasFirstLetterHint() {
        return firstLetterHint;
    }

    public boolean isRevealed() {
        return revealed;
    }

    public String getLastAnswer() {
        return lastAnswer;
    }

    public String getFirstAnswer() {
        return firstAnswer;
    }

    public HintType getRevealType() {
        return revealType;
    }

    public LearnerErrorType getErrorType() {
        return errorType;
    }

    public LearnerErrorType getLearnerReportedErrorType() {
        return learnerReportedErrorType;
    }

    public ListeningErrorCategory getInferredErrorCategory() {
        return inferredErrorCategory;
    }

    public void recordAnswer(String answer, boolean correct, boolean contextualAssistance) {
        this.attemptCount++;
        this.lastAnswer = answer;
        if (firstAttemptCorrect == null) {
            firstAnswer = answer;
            firstAttemptCorrect = correct;
            firstAttemptAssisted = contextualAssistance || firstLetterHint || revealed;
            if (!correct) {
                errorType = LearnerErrorType.KNOWN_BUT_MISSED;
            }
        }
        if (!revealed) {
            this.finalCorrect = correct;
        }
    }

    public boolean markFirstLetterHint() {
        if (firstLetterHint || finalCorrect || revealed) {
            return false;
        }
        firstLetterHint = true;
        return true;
    }

    public boolean reveal(HintType type) {
        if (revealed || finalCorrect) {
            return false;
        }
        if (type != HintType.REVEAL_WORD && type != HintType.REVEAL_SENTENCE) {
            throw new IllegalArgumentException("Loại reveal không hợp lệ.");
        }
        revealed = true;
        revealType = type;
        finalCorrect = false;
        if (firstAttemptCorrect == null) {
            firstAttemptCorrect = false;
            firstAttemptAssisted = true;
            errorType = LearnerErrorType.UNKNOWN;
            inferredErrorCategory = ListeningErrorCategory.OMISSION;
        }
        return true;
    }

    public WordLearningOutcome learningOutcome() {
        if (revealType == HintType.REVEAL_SENTENCE) {
            return WordLearningOutcome.SENTENCE_REVEAL;
        }
        if (revealType == HintType.REVEAL_WORD || revealed) {
            return WordLearningOutcome.WORD_REVEAL;
        }
        if (firstLetterHint) {
            return WordLearningOutcome.FIRST_LETTER_HINT;
        }
        if (attemptCount <= 1) {
            return WordLearningOutcome.FIRST_ATTEMPT;
        }
        if (attemptCount == 2) {
            return WordLearningOutcome.SECOND_ATTEMPT;
        }
        if (attemptCount <= 6) {
            return WordLearningOutcome.ATTEMPTS_3_TO_6;
        }
        return WordLearningOutcome.ATTEMPTS_7_PLUS;
    }

    public void classify(LearnerErrorType type) {
        this.errorType = type;
        this.learnerReportedErrorType = type;
    }

    public void recordInferredError(ListeningErrorCategory category) {
        if (category != null) {
            this.inferredErrorCategory = category;
        }
    }

    public boolean isFirstAttemptAssisted() {
        return firstAttemptAssisted;
    }
}
