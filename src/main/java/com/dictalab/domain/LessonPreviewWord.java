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

@Entity
@Table(name = "lesson_preview_words")
public class LessonPreviewWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @Column(name = "position_index", nullable = false)
    private int position;

    @Column(nullable = false, length = 100)
    private String word;

    @Column(name = "audio_start_seconds")
    private Double audioStartSeconds;

    @Column(name = "audio_end_seconds")
    private Double audioEndSeconds;

    @Column(name = "proper_name", nullable = false)
    private boolean properName;

    protected LessonPreviewWord() {
    }

    public LessonPreviewWord(Lesson lesson, int position, String word,
                             Double audioStartSeconds, Double audioEndSeconds) {
        this(lesson, position, word, audioStartSeconds, audioEndSeconds, false);
    }

    public LessonPreviewWord(Lesson lesson, int position, String word,
                             Double audioStartSeconds, Double audioEndSeconds, boolean properName) {
        this.lesson = lesson;
        this.position = position;
        this.word = word;
        this.audioStartSeconds = audioStartSeconds;
        this.audioEndSeconds = audioEndSeconds;
        this.properName = properName;
    }

    public String getWord() {
        return word;
    }

    public Long lessonKey() { return lesson.getId(); }

    public Double getAudioStartSeconds() {
        return audioStartSeconds;
    }

    public Double getAudioEndSeconds() {
        return audioEndSeconds;
    }

    public boolean isProperName() {
        return properName;
    }
}
