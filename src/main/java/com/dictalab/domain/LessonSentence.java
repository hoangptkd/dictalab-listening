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

@Entity
@Table(name = "lesson_sentences", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sentence_lesson_position", columnNames = {"lesson_id", "position_index"})
})
public class LessonSentence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @Column(name = "position_index", nullable = false)
    private int position;

    @Column(nullable = false, length = 1000)
    private String transcript;

    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    @Column(name = "audio_start_seconds")
    private Double audioStartSeconds;

    @Column(name = "audio_end_seconds")
    private Double audioEndSeconds;

    @Column(name = "speech_rate", nullable = false)
    private double speechRate;

    protected LessonSentence() {
    }

    public LessonSentence(Lesson lesson, int position, String transcript, double speechRate) {
        this.lesson = lesson;
        this.position = position;
        this.transcript = transcript;
        this.speechRate = speechRate;
    }

    public Long getId() {
        return id;
    }

    public Lesson getLesson() {
        return lesson;
    }

    public int getPosition() {
        return position;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public Double getAudioStartSeconds() {
        return audioStartSeconds;
    }

    public Double getAudioEndSeconds() {
        return audioEndSeconds;
    }

    public double getSpeechRate() {
        return speechRate;
    }

    public void update(String transcript, double speechRate, Double audioStartSeconds, Double audioEndSeconds) {
        this.transcript = transcript;
        this.speechRate = speechRate;
        this.audioStartSeconds = audioStartSeconds;
        this.audioEndSeconds = audioEndSeconds;
    }

    public void attachAudio(String audioUrl) {
        this.audioUrl = audioUrl;
        this.audioStartSeconds = null;
        this.audioEndSeconds = null;
    }

    public void moveTo(int position) {
        this.position = position;
    }
}
