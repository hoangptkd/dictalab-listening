package com.dictalab.service;

import com.dictalab.domain.AppUser;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.domain.LessonPreviewWord;
import com.dictalab.domain.ReplayEventType;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.AppUserRepository;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonPreviewWordRepository;
import com.dictalab.repository.LessonSentenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ListeningAssessmentIntegrationTest {

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private ListeningAssessmentService assessmentService;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private LessonSentenceRepository sentenceRepository;

    @Autowired
    private LessonPreviewWordRepository previewWordRepository;

    @Autowired
    private PreviewVocabularyService previewVocabularyService;

    @Test
    void bundledLessonsNeverExposeAThinOneWordPreview() {
        // Other integration tests deliberately create one-word ADMIN_UPLOAD fixtures.
        assertThat(lessonRepository.findByActiveTrueOrderByLevelAscDisplayOrderAsc().stream()
                .filter(lesson -> "BUNDLED".equals(lesson.getContentOrigin())).toList())
                .isNotEmpty()
                .allSatisfy(lesson -> assertThat(previewVocabularyService.wordsFor(lesson).stream()
                        .filter(word -> !word.properName()).count())
                        .isIn(0L, 2L, 3L, 4L, 5L));
    }

    @Test
    void excludesPreviewedVocabularyFromCefrAccuracy() {
        AppUser user = userRepository.save(new AppUser(
                "preview-assessment-" + UUID.randomUUID() + "@dictalab.local",
                "Preview learner", "unused", Instant.now()
        ));
        Instant now = Instant.now();
        Lesson lesson = new Lesson(
                "preview-assessment-" + UUID.randomUUID(), "Preview assessment", "Test lesson",
                CefrLevel.B1, "Assessment", 1, 1999, now
        );
        lesson.setPublished(true, now);
        lesson = lessonRepository.save(lesson);
        var audioSentence = new LessonSentence(lesson, 1, "special common", 1.0);
        audioSentence.attachAudio("/audio/assessment-" + UUID.randomUUID() + ".mp3");
        sentenceRepository.save(audioSentence);
        previewWordRepository.save(new LessonPreviewWord(lesson, 1, "special", null, null));

        ApiDtos.PracticeStartResponse session = practiceService.start(
                user.getId(), new ApiDtos.StartSessionRequest(lesson.getId())
        );
        Long sentenceId = session.sentence().sentenceId();
        practiceService.replay(user.getId(), session.sessionId(), sentenceId,
                new ApiDtos.ReplayRequest(ReplayEventType.STARTED, 1.0));
        practiceService.replay(user.getId(), session.sessionId(), sentenceId,
                new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED, 1.0));
        practiceService.checkWord(user.getId(), session.sessionId(), sentenceId, 0,
                new ApiDtos.CheckWordRequest("wrong", 500L));
        practiceService.checkWord(user.getId(), session.sessionId(), sentenceId, 0,
                new ApiDtos.CheckWordRequest("special", 500L));
        practiceService.checkWord(user.getId(), session.sessionId(), sentenceId, 1,
                new ApiDtos.CheckWordRequest("common", 500L));
        practiceService.completeSentence(user.getId(), session.sessionId(), sentenceId,
                new ApiDtos.CompleteSentenceRequest(2));

        ApiDtos.ListeningAssessment assessment = assessmentService.assess(user.getId());
        assertThat(assessment.metrics().firstAttemptAccuracy()).isEqualTo(100);
        assertThat(assessment.metrics().contentWordsObserved()).isEqualTo(1);
    }

    @Test
    void usesAtMostTenDistinctFirstExposureLessons() {
        AppUser user = userRepository.save(new AppUser(
                "assessment-" + UUID.randomUUID() + "@dictalab.local",
                "Assessment learner", "unused", Instant.now()
        ));
        List<Long> lessonIds = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            lessonIds.add(createOneWordLesson(index));
        }

        for (Long lessonId : lessonIds) {
            completeOneWordLesson(user.getId(), lessonId);
        }

        ApiDtos.ListeningAssessment assessment = assessmentService.assess(user.getId());
        assertThat(assessment.evidenceLessons()).isEqualTo(10);
        assertThat(assessment.requiredLessons()).isEqualTo(5);
        assertThat(assessment.metrics().unassistedFirstPassAccuracy()).isEqualTo(100);

        completeOneWordLesson(user.getId(), lessonIds.get(10));
        assertThat(assessmentService.assess(user.getId()).evidenceLessons()).isEqualTo(10);
    }

    private Long createOneWordLesson(int index) {
        Instant now = Instant.now();
        Lesson lesson = new Lesson(
                "assessment-" + UUID.randomUUID(), "Assessment " + index, "Test lesson",
                CefrLevel.values()[index % CefrLevel.values().length], "Assessment",
                1, 2000 + index, now
        );
        lesson.setPublished(true, now);
        lesson = lessonRepository.save(lesson);
        var audioSentence = new LessonSentence(lesson, 1, "hello", 1.0);
        audioSentence.attachAudio("/audio/assessment-" + UUID.randomUUID() + ".mp3");
        sentenceRepository.save(audioSentence);
        return lesson.getId();
    }

    private void completeOneWordLesson(Long userId, Long lessonId) {
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(lessonId)
        );
        Long sentenceId = session.sentence().sentenceId();
        practiceService.replay(userId, session.sessionId(), sentenceId,
                new ApiDtos.ReplayRequest(ReplayEventType.STARTED, 1.0));
        practiceService.replay(userId, session.sessionId(), sentenceId,
                new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED, 1.0));
        practiceService.checkWord(userId, session.sessionId(), sentenceId, 0,
                new ApiDtos.CheckWordRequest("hello", 500L));
        practiceService.completeSentence(userId, session.sessionId(), sentenceId,
                new ApiDtos.CompleteSentenceRequest(2));
    }
}
