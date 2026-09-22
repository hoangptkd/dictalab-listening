package com.dictalab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "lessons")
public class Lesson {

    public static final String PERSONAL_CONTENT_ORIGIN = "BRITISH_COUNCIL_PERSONAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private CefrLevel level;

    @Column(nullable = false, length = 60)
    private String topic;

    @Column(nullable = false)
    private int estimatedMinutes;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "content_origin", nullable = false, length = 30)
    private String contentOrigin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "speaker_count", nullable = false)
    private int speakerCount;

    @Column(name = "accent_profile", length = 120)
    private String accentProfile;

    @Column(name = "vocabulary_difficulty", nullable = false)
    private int vocabularyDifficulty;

    @Column(name = "grammar_difficulty", nullable = false)
    private int grammarDifficulty;

    @Column(name = "sentence_difficulty", nullable = false)
    private int sentenceDifficulty;

    @Column(name = "assessment_calibrated", nullable = false)
    private boolean assessmentCalibrated;

    protected Lesson() {
    }

    public Lesson(String slug, String title, String description, CefrLevel level, String topic,
                  int estimatedMinutes, int displayOrder, Instant now) {
        this(slug, title, description, level, topic, estimatedMinutes, displayOrder, "ADMIN_UPLOAD", now);
    }

    public Lesson(String slug, String title, String description, CefrLevel level, String topic,
                  int estimatedMinutes, int displayOrder, String contentOrigin, Instant now) {
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.level = level;
        this.topic = topic;
        this.estimatedMinutes = estimatedMinutes;
        this.displayOrder = displayOrder;
        this.active = false;
        this.contentOrigin = contentOrigin;
        this.createdAt = now;
        this.updatedAt = now;
        int initialDifficulty = level.ordinal() + 1;
        this.vocabularyDifficulty = initialDifficulty;
        this.grammarDifficulty = initialDifficulty;
        this.sentenceDifficulty = initialDifficulty;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public CefrLevel getLevel() {
        return level;
    }

    public String getTopic() {
        return topic;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public String getContentOrigin() {
        return contentOrigin;
    }

    public boolean isPersonalContent() {
        return PERSONAL_CONTENT_ORIGIN.equals(contentOrigin);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getSpeakerCount() {
        return speakerCount;
    }

    public String getAccentProfile() {
        return accentProfile;
    }

    public int getVocabularyDifficulty() {
        return vocabularyDifficulty;
    }

    public int getGrammarDifficulty() {
        return grammarDifficulty;
    }

    public int getSentenceDifficulty() {
        return sentenceDifficulty;
    }

    public boolean isAssessmentCalibrated() {
        return assessmentCalibrated;
    }

    public void updateAssessmentMetadata(int speakerCount, String accentProfile,
                                         int vocabularyDifficulty, int grammarDifficulty,
                                         int sentenceDifficulty, boolean assessmentCalibrated,
                                         Instant now) {
        this.speakerCount = speakerCount;
        this.accentProfile = accentProfile == null || accentProfile.isBlank() ? null : accentProfile.trim();
        this.vocabularyDifficulty = vocabularyDifficulty;
        this.grammarDifficulty = grammarDifficulty;
        this.sentenceDifficulty = sentenceDifficulty;
        this.assessmentCalibrated = assessmentCalibrated;
        this.updatedAt = now;
    }

    public void updateMetadata(String title, String description, CefrLevel level, String topic,
                               int estimatedMinutes, int displayOrder, Instant now) {
        this.title = title;
        this.description = description;
        this.level = level;
        this.topic = topic;
        this.estimatedMinutes = estimatedMinutes;
        this.displayOrder = displayOrder;
        this.updatedAt = now;
    }

    public void setPublished(boolean published, Instant now) {
        this.active = published;
        this.updatedAt = now;
    }
}
