package com.dictalab.service;

import com.dictalab.domain.HintType;
import com.dictalab.domain.AppUser;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.domain.ReplayEventType;
import com.dictalab.domain.WordLearningOutcome;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(statements = "UPDATE lessons SET active = TRUE WHERE id = 1")
class PracticeServiceIntegrationTest {

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private LessonRepository lessonRepository;

    @Autowired
    private LessonSentenceRepository sentenceRepository;

    @Autowired
    private TranscriptTokenizer tokenizer;

    @Autowired
    private AppUserRepository userRepository;

    private Long userId;

    @BeforeEach
    void setUpUser() {
        AppUser user = userRepository.save(new AppUser(
                "integration-" + UUID.randomUUID() + "@dictalab.local",
                "Integration learner", "unused", Instant.now()
        ));
        userId = user.getId();
    }

    @Test
    void resumesInProgressLessonWithSavedSentenceAndWords() {
        ApiDtos.PracticeStartResponse initial = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(1L)
        );
        ApiDtos.SentenceState firstSentence = initial.sentence();
        listenToSentence(userId, initial.sessionId(), firstSentence.sentenceId());
        List<String> answers = List.of("Anna", "buys", "fresh", "apples", "at", "the", "market");
        for (int wordIndex = 0; wordIndex < answers.size(); wordIndex++) {
            firstSentence = practiceService.checkWord(
                    userId, initial.sessionId(), firstSentence.sentenceId(), wordIndex,
                    new ApiDtos.CheckWordRequest(answers.get(wordIndex))
            );
        }
        ApiDtos.CompleteSentenceResponse completion = practiceService.completeSentence(
                userId, initial.sessionId(), firstSentence.sentenceId(),
                new ApiDtos.CompleteSentenceRequest(12)
        );
        listenToSentence(userId, initial.sessionId(), completion.nextSentence().sentenceId());
        practiceService.checkWord(
                userId, initial.sessionId(), completion.nextSentence().sentenceId(), 0,
                new ApiDtos.CheckWordRequest("She")
        );

        ApiDtos.PracticeStartResponse resumed = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(1L)
        );

        assertThat(resumed.sessionId()).isEqualTo(initial.sessionId());
        assertThat(resumed.sentence().position()).isEqualTo(2);
        assertThat(resumed.sentence().replayCount()).isEqualTo(1);
        assertThat(resumed.sentence().words().get(0).correct()).isTrue();
        assertThat(resumed.sentence().words().get(0).userInput()).isEqualTo("She");
    }

    @Test
    void ellloCatalogUsesConversationAudioSegments() {
        List<Lesson> ellloLessons = lessonRepository.findAll().stream()
                .filter(Lesson::isActive)
                .filter(lesson -> lesson.getSlug().startsWith("elllo-sentences-"))
                .toList();

        assertThat(ellloLessons).hasSize(20);
        assertThat(ellloLessons)
                .filteredOn(lesson -> lesson.getLevel().name().equals("A1"))
                .hasSize(5);
        assertThat(ellloLessons)
                .filteredOn(lesson -> lesson.getLevel().name().equals("A2"))
                .hasSize(5);
        assertThat(ellloLessons)
                .filteredOn(lesson -> lesson.getLevel().name().equals("B1"))
                .hasSize(5);
        assertThat(ellloLessons)
                .filteredOn(lesson -> lesson.getLevel().name().equals("B2"))
                .hasSize(5);

        List<LessonSentence> sentences = ellloLessons.stream()
                .flatMap(lesson -> sentenceRepository.findByLessonIdOrderByPositionAsc(lesson.getId()).stream())
                .toList();
        assertThat(sentences).hasSize(910);
        assertThat(sentences).allSatisfy(sentence -> {
            assertThat(tokenizer.words(sentence.getTranscript())).hasSizeLessThanOrEqualTo(24);
            assertThat(sentence.getAudioUrl()).startsWith("/audio/elllo/");
            assertThat(new ClassPathResource("static" + sentence.getAudioUrl()).exists()).isTrue();
            assertThat(sentence.getAudioStartSeconds()).isNotNull().isGreaterThanOrEqualTo(0.0);
            assertThat(sentence.getAudioEndSeconds()).isGreaterThan(sentence.getAudioStartSeconds());
        });
        assertThat(sentences.stream().map(LessonSentence::getAudioUrl).distinct()).hasSize(79);

        Lesson firstA1Lesson = ellloLessons.stream()
                .filter(lesson -> lesson.getLevel().name().equals("A1"))
                .min(java.util.Comparator.comparingInt(Lesson::getDisplayOrder))
                .orElseThrow();
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(firstA1Lesson.getId())
        );
        assertThat(session.sentence().words()).hasSize(1);
        assertThat(session.sentence().audioUrl()).contains("conversation-1-A1-01-1-Be-Verbs-First-Day.mp3");
        assertThat(session.sentence().audioStartSeconds()).isEqualTo(2.86);
        assertThat(session.sentence().audioEndSeconds()).isEqualTo(3.455);
    }

    @Test
    void completesLessonAndCalculatesPerfectFirstAttempt() {
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(1L)
        );
        List<List<String>> answers = List.of(
                List.of("Anna", "buys", "fresh", "apples", "at", "the", "market"),
                List.of("She", "talks", "to", "a", "friendly", "fruit", "seller"),
                List.of("Then", "she", "walks", "home", "before", "lunch")
        );

        ApiDtos.CompleteSentenceResponse completion = null;
        for (List<String> sentenceAnswers : answers) {
            ApiDtos.SentenceState sentence = session.sentence();
            listenToSentence(userId, session.sessionId(), sentence.sentenceId());
            for (int index = 0; index < sentenceAnswers.size(); index++) {
                sentence = practiceService.checkWord(
                        userId, session.sessionId(), sentence.sentenceId(), index,
                        new ApiDtos.CheckWordRequest(sentenceAnswers.get(index))
                );
            }
            assertThat(sentence.canComplete()).isTrue();
            completion = practiceService.completeSentence(
                    userId, session.sessionId(), sentence.sentenceId(),
                    new ApiDtos.CompleteSentenceRequest(12)
            );
            if (!completion.lessonCompleted()) {
                session = new ApiDtos.PracticeStartResponse(
                        session.sessionId(), session.lessonId(), session.lessonTitle(), session.level(),
                        completion.nextSentence()
                );
            }
        }

        assertThat(completion).isNotNull();
        assertThat(completion.lessonCompleted()).isTrue();
        assertThat(completion.result().firstAttemptAccuracy()).isEqualTo(100.0);
        assertThat(completion.result().finalAccuracy()).isEqualTo(100.0);
        assertThat(completion.result().overallScore()).isEqualTo(100.0);
        assertThat(completion.result().replayCount()).isEqualTo(3);
        assertThat(completion.result().review().totalSentences()).isEqualTo(3);
        assertThat(completion.result().review().totalWords()).isEqualTo(20);
        assertThat(completion.result().review().sentences())
                .flatExtracting(ApiDtos.SentenceReview::words)
                .extracting(ApiDtos.WordReview::outcome)
                .containsOnly(WordLearningOutcome.FIRST_ATTEMPT);
    }

    @Test
    void buildsWordByWordLessonReviewFromAttemptsAndHints() {
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(1L)
        );
        Long firstSentenceId = session.sentence().sentenceId();
        listenToSentence(userId, session.sessionId(), firstSentenceId);

        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 0,
                new ApiDtos.CheckWordRequest("Anna"));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 1,
                new ApiDtos.CheckWordRequest("buy"));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 1,
                new ApiDtos.CheckWordRequest("buys"));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 2,
                new ApiDtos.CheckWordRequest("free"));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 2,
                new ApiDtos.CheckWordRequest("fruit"));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 2,
                new ApiDtos.CheckWordRequest("fresh"));
        for (int attempt = 0; attempt < 6; attempt++) {
            practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 3,
                    new ApiDtos.CheckWordRequest("apple"));
        }
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 3,
                new ApiDtos.CheckWordRequest("apples"));
        practiceService.useHint(userId, session.sessionId(), firstSentenceId,
                new ApiDtos.HintRequest(HintType.FIRST_LETTER, 4));
        practiceService.checkWord(userId, session.sessionId(), firstSentenceId, 4,
                new ApiDtos.CheckWordRequest("at"));
        practiceService.useHint(userId, session.sessionId(), firstSentenceId,
                new ApiDtos.HintRequest(HintType.REVEAL_WORD, 5));
        practiceService.useHint(userId, session.sessionId(), firstSentenceId,
                new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null));
        listenToSentence(userId, session.sessionId(), firstSentenceId);

        ApiDtos.CompleteSentenceResponse completion = practiceService.completeSentence(
                userId, session.sessionId(), firstSentenceId, new ApiDtos.CompleteSentenceRequest(30)
        );
        while (!completion.lessonCompleted()) {
            Long sentenceId = completion.nextSentence().sentenceId();
            listenToSentence(userId, session.sessionId(), sentenceId);
            practiceService.useHint(userId, session.sessionId(), sentenceId,
                    new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null));
            listenToSentence(userId, session.sessionId(), sentenceId);
            completion = practiceService.completeSentence(
                    userId, session.sessionId(), sentenceId, new ApiDtos.CompleteSentenceRequest(10)
            );
        }

        List<ApiDtos.WordReview> reviewedWords = completion.result().review().sentences().get(0).words();
        assertThat(reviewedWords)
                .extracting(ApiDtos.WordReview::outcome)
                .containsExactly(
                        WordLearningOutcome.FIRST_ATTEMPT,
                        WordLearningOutcome.SECOND_ATTEMPT,
                        WordLearningOutcome.ATTEMPTS_3_TO_6,
                        WordLearningOutcome.ATTEMPTS_7_PLUS,
                        WordLearningOutcome.FIRST_LETTER_HINT,
                        WordLearningOutcome.WORD_REVEAL,
                        WordLearningOutcome.SENTENCE_REVEAL
                );
        assertThat(reviewedWords.get(1).firstAnswer()).isEqualTo("buy");
        assertThat(reviewedWords.get(1).lastAnswer()).isEqualTo("buys");
        assertThat(reviewedWords.get(5).lastAnswer()).isNull();
    }

    @Test
    void blocksMovingForwardUntilRevealedSentenceIsHeardAgain() {
        ApiDtos.PracticeStartResponse session = practiceService.start(
                userId, new ApiDtos.StartSessionRequest(1L)
        );
        Long sentenceId = session.sentence().sentenceId();
        listenToSentence(userId, session.sessionId(), sentenceId);

        ApiDtos.SentenceState revealed = practiceService.useHint(
                userId, session.sessionId(), sentenceId,
                new ApiDtos.HintRequest(HintType.REVEAL_SENTENCE, null)
        );

        assertThat(revealed.requiresReplayAfterReveal()).isTrue();
        assertThat(revealed.canComplete()).isFalse();
        assertThatThrownBy(() -> practiceService.completeSentence(
                userId, session.sessionId(), sentenceId, new ApiDtos.CompleteSentenceRequest(10)
        )).isInstanceOf(InvalidPracticeStateException.class)
                .hasMessageContaining("nghe lại câu đúng");

        listenToSentence(userId, session.sessionId(), sentenceId);
        ApiDtos.SentenceState ready = practiceService.current(userId, session.sessionId()).sentence();
        assertThat(ready.requiresReplayAfterReveal()).isFalse();
        assertThat(ready.canComplete()).isTrue();
    }

    private void listenToSentence(Long learner, String sessionId, Long sentenceId) {
        practiceService.replay(
                learner, sessionId, sentenceId, new ApiDtos.ReplayRequest(ReplayEventType.STARTED)
        );
        practiceService.replay(
                learner, sessionId, sentenceId, new ApiDtos.ReplayRequest(ReplayEventType.COMPLETED)
        );
    }
}
