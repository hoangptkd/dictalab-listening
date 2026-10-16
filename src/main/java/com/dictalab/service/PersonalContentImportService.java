package com.dictalab.service;

import com.dictalab.config.PersonalContentProperties;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.domain.LessonPreviewWord;
import com.dictalab.repository.LessonPreviewWordRepository;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class PersonalContentImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalContentImportService.class);
    private static final String EXPECTED_ORIGIN = Lesson.PERSONAL_CONTENT_ORIGIN;
    private static final String BRITISH_COUNCIL_SLUG_PREFIX = "british-council-personal-";

    private final PersonalContentProperties properties;
    private final LessonRepository lessonRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final LessonPreviewWordRepository previewWordRepository;
    private final ObjectMapper objectMapper;
    private final MediaStorageService media;
    private final Clock clock = Clock.systemUTC();

    public PersonalContentImportService(PersonalContentProperties properties,
                                        LessonRepository lessonRepository,
                                        LessonSentenceRepository sentenceRepository,
                                        LessonPreviewWordRepository previewWordRepository,
                                        ObjectMapper objectMapper, MediaStorageService media) {
        this.properties = properties;
        this.lessonRepository = lessonRepository;
        this.sentenceRepository = sentenceRepository;
        this.previewWordRepository = previewWordRepository;
        this.objectMapper = objectMapper;
        this.media = media;
    }

    @Transactional
    public int importConfiguredManifest() {
        if (!properties.enabled()) {
            return 0;
        }
        Path root = properties.root().toAbsolutePath().normalize();
        Path manifestPath = properties.manifest().toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Personal-content manifest was not found: " + manifestPath);
        }
        PersonalManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestPath.toFile(), PersonalManifest.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not read personal-content manifest: " + manifestPath, exception);
        }
        if (!EXPECTED_ORIGIN.equals(manifest.contentOrigin())) {
            throw new IllegalStateException("Unsupported personal content origin: " + manifest.contentOrigin());
        }
        int imported = 0;
        for (PersonalLesson item : manifest.lessons()) {
            if (lessonRepository.findBySlug(item.slug()).isPresent()) {
                continue;
            }
            Path audio = root.resolve(item.audioPath()).normalize();
            if (!audio.startsWith(root) || !Files.isRegularFile(audio)) {
                throw new IllegalStateException("Personal audio was not found under configured root: " + item.audioPath());
            }
            validateSentences(item);
            String audioUrl = "/private-media/" + item.audioPath().replace('\\', '/');
            if (media.s3Enabled() && media.legacyAudioLocation(audioUrl).isEmpty())
                throw new IllegalStateException("Upload personal audio with migrate-audio-s3.py before importing: " + item.audioPath());
            Instant now = Instant.now(clock);
            Lesson lesson = new Lesson(
                    item.slug(), item.title(), item.description(), item.level(), item.topic(),
                    item.estimatedMinutes(), item.displayOrder(), EXPECTED_ORIGIN, now
            );
            lesson.updateAssessmentMetadata(
                    item.speakerCount(), item.accentProfile(), item.vocabularyDifficulty(),
                    item.grammarDifficulty(), item.sentenceDifficulty(), item.assessmentCalibrated(), now
            );
            lesson.setPublished(true, now);
            lessonRepository.save(lesson);
            for (PersonalSentence sentenceItem : item.sentences()) {
                LessonSentence sentence = new LessonSentence(
                        lesson, sentenceItem.position(), sentenceItem.transcript(), sentenceItem.speechRate()
                );
                sentence.update(
                        sentenceItem.transcript(), sentenceItem.speechRate(),
                        sentenceItem.audioStartSeconds(), sentenceItem.audioEndSeconds()
                );
                sentence.attachAudio(audioUrl);
                sentence.update(
                        sentenceItem.transcript(), sentenceItem.speechRate(),
                        sentenceItem.audioStartSeconds(), sentenceItem.audioEndSeconds()
                );
                sentenceRepository.save(sentence);
            }
            for (int index = 0; index < item.previewWords().size(); index++) {
                PersonalPreviewWord preview = item.previewWords().get(index);
                previewWordRepository.save(new LessonPreviewWord(
                        lesson, index + 1, preview.word(),
                        preview.audioStartSeconds(), preview.audioEndSeconds()
                ));
            }
            imported++;
        }
        if (manifest.lessons().stream().allMatch(item -> item.slug().startsWith(BRITISH_COUNCIL_SLUG_PREFIX))) {
            Instant now = Instant.now(clock);
            var currentSlugs = manifest.lessons().stream().map(PersonalLesson::slug).collect(java.util.stream.Collectors.toSet());
            lessonRepository.findByContentOrigin(EXPECTED_ORIGIN).stream()
                    .filter(lesson -> lesson.getSlug().startsWith(BRITISH_COUNCIL_SLUG_PREFIX))
                    .filter(lesson -> !currentSlugs.contains(lesson.getSlug()))
                    .filter(Lesson::isActive)
                    .forEach(lesson -> lesson.setPublished(false, now));
        }
        LOGGER.info("Personal content ready: {} lesson(s) imported, {} lesson(s) in manifest",
                imported, manifest.lessons().size());
        return imported;
    }

    private void validateSentences(PersonalLesson lesson) {
        if (lesson.sentences() == null || lesson.sentences().isEmpty()) {
            throw new IllegalStateException("Personal lesson has no sentences: " + lesson.slug());
        }
        if (lesson.previewWords() == null || lesson.previewWords().size() < 2 || lesson.previewWords().size() > 5) {
            throw new IllegalStateException("Personal lesson must have 2 to 5 preview words: " + lesson.slug());
        }
        var previewWords = new java.util.HashSet<String>();
        for (PersonalPreviewWord preview : lesson.previewWords()) {
            String normalized = preview.word() == null ? "" : preview.word().trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank() || !previewWords.add(normalized)
                    || preview.audioStartSeconds() == null || preview.audioStartSeconds() < 0
                    || preview.audioEndSeconds() == null
                    || preview.audioEndSeconds() <= preview.audioStartSeconds()) {
                throw new IllegalStateException("Invalid preview word in personal lesson: " + lesson.slug());
            }
        }
        for (int index = 0; index < lesson.sentences().size(); index++) {
            PersonalSentence sentence = lesson.sentences().get(index);
            if (sentence.position() != index + 1 || sentence.transcript() == null || sentence.transcript().isBlank()
                    || sentence.audioStartSeconds() < 0
                    || sentence.audioEndSeconds() <= sentence.audioStartSeconds()) {
                throw new IllegalStateException("Invalid sentence data in personal lesson: " + lesson.slug());
            }
        }
    }

    public record PersonalManifest(
            int schemaVersion, String contentOrigin, String sourceName, String usage,
            List<PersonalLesson> lessons
    ) {
    }

    public record PersonalLesson(
            String slug, String title, String description, CefrLevel level, String topic,
            int estimatedMinutes, int displayOrder, String sourceUrl, String audioPath,
            Double durationSeconds, Integer speechRateWpm, int speakerCount,
            String accentProfile, int vocabularyDifficulty, int grammarDifficulty,
            int sentenceDifficulty, boolean assessmentCalibrated, Double alignmentMatchRate,
            List<PersonalPreviewWord> previewWords,
            List<PersonalSentence> sentences
    ) {
    }

    public record PersonalSentence(
            int position, String transcript, double audioStartSeconds,
            double audioEndSeconds, double speechRate
    ) {
    }

    public record PersonalPreviewWord(
            String word, Double audioStartSeconds, Double audioEndSeconds
    ) {
    }
}
