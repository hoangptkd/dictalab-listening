package com.dictalab.service;

import com.dictalab.domain.HintType;
import com.dictalab.domain.LearnerErrorType;
import com.dictalab.domain.Lesson;
import com.dictalab.domain.LessonSentence;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.ReplayEventType;
import com.dictalab.domain.SentenceAttempt;
import com.dictalab.domain.SessionStatus;
import com.dictalab.domain.WordAttempt;
import com.dictalab.domain.WordAttemptEvent;
import com.dictalab.domain.WordLearningOutcome;
import com.dictalab.domain.AppUser;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonPreviewWordRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.repository.SentenceAttemptRepository;
import com.dictalab.repository.WordAttemptRepository;
import com.dictalab.repository.WordAttemptEventRepository;
import com.dictalab.repository.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class PracticeService {

    private final LessonRepository lessonRepository;
    private final LessonPreviewWordRepository previewWordRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final PracticeSessionRepository sessionRepository;
    private final SentenceAttemptRepository sentenceAttemptRepository;
    private final WordAttemptRepository wordAttemptRepository;
    private final WordAttemptEventRepository wordAttemptEventRepository;
    private final TranscriptTokenizer tokenizer;
    private final ScoringService scoringService;
    private final ListeningErrorClassifier errorClassifier;
    private final ListeningAssessmentService assessmentService;
    private final AppUserRepository userRepository;
    private final JdbcTemplate db;
    private final Clock clock;

    public PracticeService(LessonRepository lessonRepository,
                           LessonPreviewWordRepository previewWordRepository,
                           LessonSentenceRepository sentenceRepository,
                           PracticeSessionRepository sessionRepository,
                           SentenceAttemptRepository sentenceAttemptRepository,
                           WordAttemptRepository wordAttemptRepository,
                           WordAttemptEventRepository wordAttemptEventRepository,
                           AppUserRepository userRepository,
                           TranscriptTokenizer tokenizer,
                           ScoringService scoringService,
                           ListeningErrorClassifier errorClassifier,
                           ListeningAssessmentService assessmentService,
                           JdbcTemplate db) {
        this.lessonRepository = lessonRepository;
        this.previewWordRepository = previewWordRepository;
        this.sentenceRepository = sentenceRepository;
        this.sessionRepository = sessionRepository;
        this.sentenceAttemptRepository = sentenceAttemptRepository;
        this.wordAttemptRepository = wordAttemptRepository;
        this.wordAttemptEventRepository = wordAttemptEventRepository;
        this.userRepository = userRepository;
        this.tokenizer = tokenizer;
        this.scoringService = scoringService;
        this.errorClassifier = errorClassifier;
        this.assessmentService = assessmentService;
        this.db = db;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public ApiDtos.PracticeStartResponse start(Long userId, ApiDtos.StartSessionRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        Lesson lesson = lessonRepository.findById(request.lessonId())
                .filter(Lesson::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài luyện."));
        if (lesson.isPersonalContent() && !"ADMIN".equals(user.getRole())) {
            throw new ResourceNotFoundException("Không tìm thấy bài luyện.");
        }

        PracticeSession existingSession = sessionRepository
                .findFirstByUser_IdAndLesson_IdAndRoomIdIsNullAndStatusOrderByStartedAtDesc(
                        userId, lesson.getId(), SessionStatus.IN_PROGRESS
                )
                .orElse(null);
        if (existingSession != null) {
            LessonSentence currentSentence = currentSentence(existingSession);
            SentenceAttempt currentAttempt = getOrFailAttempt(existingSession, currentSentence.getId());
            return new ApiDtos.PracticeStartResponse(
                    existingSession.getId(), lesson.getId(), lesson.getTitle(), lesson.getLevel(),
                    toState(currentAttempt)
            );
        }

        LessonSentence firstSentence = sentenceRepository.findByLessonIdAndPosition(lesson.getId(), 1)
                .orElseThrow(() -> new InvalidPracticeStateException("Bài luyện chưa có câu nghe."));

        Integer otherExposure = db.queryForObject("""
                SELECT (SELECT COUNT(*) FROM listening_sessions s JOIN listening_units u ON u.id=s.unit_id
                    WHERE s.user_id=? AND u.audio_url IN
                        (SELECT audio_url FROM lesson_sentences WHERE lesson_id=?))
                    + (SELECT COUNT(*) FROM audio_exposure_events e WHERE e.user_id=? AND e.audio_url IN
                        (SELECT audio_url FROM lesson_sentences WHERE lesson_id=?))
                """, Integer.class, userId, lesson.getId(), userId, lesson.getId());
        boolean firstExposure = !sessionRepository.existsByUserIdAndLessonId(userId, lesson.getId())
                && otherExposure != null && otherExposure == 0;
        PracticeSession session = sessionRepository.save(new PracticeSession(user, lesson, now(), firstExposure));
        SentenceAttempt attempt = sentenceAttemptRepository.save(new SentenceAttempt(session, firstSentence, now()));
        return new ApiDtos.PracticeStartResponse(
                session.getId(), lesson.getId(), lesson.getTitle(), lesson.getLevel(), toState(attempt)
        );
    }

    public int sharedLessonSize(Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .filter(Lesson::isActive)
                .filter(value -> !value.isPersonalContent())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài học chung."));
        return Math.toIntExact(sentenceRepository.countByLessonId(lesson.getId()));
    }

    @Transactional
    public ApiDtos.PracticeStartResponse startShared(Long userId, String roomId, Long lessonId) {
        PracticeSession existing = sessionRepository.findFirstByUserIdAndRoomIdOrderByStartedAtDesc(userId, roomId)
                .orElse(null);
        if (existing != null) {
            if (!existing.getLesson().getId().equals(lessonId)) {
                throw new InvalidPracticeStateException("Buổi học chung đã thay đổi bài.");
            }
            if (existing.getStatus() == SessionStatus.COMPLETED) {
                return new ApiDtos.PracticeStartResponse(existing.getId(), lessonId,
                        existing.getLesson().getTitle(), existing.getLesson().getLevel(), null);
            }
            return current(userId, existing.getId());
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        Lesson lesson = lessonRepository.findById(lessonId)
                .filter(Lesson::isActive)
                .filter(value -> !value.isPersonalContent())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài học chung."));
        LessonSentence first = sentenceRepository.findByLessonIdAndPosition(lessonId, 1)
                .orElseThrow(() -> new InvalidPracticeStateException("Bài học chưa có câu nghe."));
        PracticeSession session = new PracticeSession(user, lesson, now(), false);
        session.setRoomId(roomId);
        sessionRepository.save(session);
        SentenceAttempt attempt = sentenceAttemptRepository.save(new SentenceAttempt(session, first, now()));
        return new ApiDtos.PracticeStartResponse(session.getId(), lessonId, lesson.getTitle(), lesson.getLevel(),
                toState(attempt));
    }

    public boolean isSharedSentenceReady(Long userId, String roomId, int position) {
        PracticeSession session = sessionRepository.findFirstByUserIdAndRoomIdOrderByStartedAtDesc(userId, roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên học chung."));
        if (session.getStatus() != SessionStatus.IN_PROGRESS || session.getCurrentSentencePosition() != position) {
            return false;
        }
        LessonSentence sentence = currentSentence(session);
        return toState(getOrFailAttempt(session, sentence.getId())).canComplete();
    }

    public ApiDtos.PracticeStartResponse current(Long userId, String sessionId) {
        PracticeSession session = getSession(userId, sessionId);
        ensureInProgress(session);
        LessonSentence sentence = currentSentence(session);
        SentenceAttempt attempt = getOrFailAttempt(session, sentence.getId());
        return new ApiDtos.PracticeStartResponse(
                session.getId(), session.getLesson().getId(), session.getLesson().getTitle(),
                session.getLesson().getLevel(), toState(attempt)
        );
    }

    @Transactional
    public ApiDtos.SentenceState replay(Long userId, String sessionId, Long sentenceId,
                                        ApiDtos.ReplayRequest request) {
        PracticeSession session = getSession(userId, sessionId);
        SentenceAttempt attempt = currentAttempt(session, sentenceId);
        if (request.event() == ReplayEventType.STARTED) {
            boolean sentenceReadyToComplete = toState(attempt).canComplete();
            if (!sentenceReadyToComplete) {
                double playbackSpeed = request.playbackSpeed() == null ? 1.0 : request.playbackSpeed();
                attempt.incrementReplayCount();
                attempt.recordPlayback(playbackSpeed);
                session.incrementReplays();
            }
        } else if (attempt.hasAnswerReveal()) {
            attempt.markReplayCompleted();
        }
        return toState(attempt);
    }

    @Transactional
    public ApiDtos.SentenceState checkWord(Long userId, String sessionId, Long sentenceId,
                                           int wordIndex, ApiDtos.CheckWordRequest request) {
        PracticeSession session = getSession(userId, sessionId);
        SentenceAttempt attempt = currentAttempt(session, sentenceId);
        requireListening(attempt);
        List<String> expectedWords = tokenizer.words(attempt.getSentence().getTranscript());
        validateWordIndex(wordIndex, expectedWords);
        if (properNameIndices(attempt.getSentence()).contains(wordIndex)) {
            return toState(attempt);
        }

        WordAttempt wordAttempt = getOrCreateWordAttempt(attempt, wordIndex);
        String rawAnswer = request.answer().trim();
        String normalizedAnswer = tokenizer.normalize(rawAnswer);
        String expected = tokenizer.normalize(expectedWords.get(wordIndex));
        boolean correct = expected.equals(normalizedAnswer);
        boolean contextAssisted = attempt.getReplayCount() > 1 || attempt.getSlowPlaybackCount() > 0;
        wordAttempt.recordAnswer(rawAnswer, correct, contextAssisted);
        var errorCategory = correct ? null : errorClassifier.classify(
                expected,
                normalizedAnswer,
                expectedWords.stream().map(tokenizer::normalize).toList(),
                wordIndex
        );
        wordAttempt.recordInferredError(errorCategory);
        long responseTimeMs = request.responseTimeMs() == null ? 0 : request.responseTimeMs();
        wordAttemptEventRepository.save(new WordAttemptEvent(
                wordAttempt, wordAttempt.getAttemptCount(), rawAnswer, normalizedAnswer,
                correct, responseTimeMs, errorCategory, now()
        ));
        return toState(attempt);
    }

    @Transactional
    public ApiDtos.SentenceState useHint(Long userId, String sessionId, Long sentenceId,
                                         ApiDtos.HintRequest request) {
        PracticeSession session = getSession(userId, sessionId);
        SentenceAttempt attempt = currentAttempt(session, sentenceId);
        requireListening(attempt);
        List<String> words = tokenizer.words(attempt.getSentence().getTranscript());

        if (request.type() == HintType.REVEAL_SENTENCE) {
            revealSentence(session, attempt, words);
            return toState(attempt);
        }

        if (request.wordIndex() == null) {
            throw new InvalidPracticeStateException("Hãy chọn một ô từ trước khi dùng gợi ý này.");
        }
        validateWordIndex(request.wordIndex(), words);
        if (properNameIndices(attempt.getSentence()).contains(request.wordIndex())) {
            return toState(attempt);
        }
        WordAttempt wordAttempt = getOrCreateWordAttempt(attempt, request.wordIndex());

        if (request.type() == HintType.FIRST_LETTER && wordAttempt.markFirstLetterHint()) {
            attempt.incrementFirstLetterHintCount();
            session.incrementHints();
        } else if (request.type() == HintType.REVEAL_WORD && wordAttempt.reveal(HintType.REVEAL_WORD)) {
            attempt.incrementWordRevealCount();
            attempt.requireReplayAfterReveal();
            session.incrementHints();
        }
        return toState(attempt);
    }

    @Transactional
    public ApiDtos.SentenceState classifyWord(Long userId, String sessionId, Long sentenceId,
                                               ApiDtos.ClassifyWordRequest request) {
        PracticeSession session = getSession(userId, sessionId);
        SentenceAttempt attempt = currentAttempt(session, sentenceId);
        List<String> words = tokenizer.words(attempt.getSentence().getTranscript());
        validateWordIndex(request.wordIndex(), words);
        WordAttempt wordAttempt = getOrCreateWordAttempt(attempt, request.wordIndex());
        if (!Boolean.FALSE.equals(wordAttempt.getFirstAttemptCorrect())) {
            throw new InvalidPracticeStateException("Chỉ phân loại những từ bị sai ở lần thử đầu.");
        }
        wordAttempt.classify(request.errorType());
        return toState(attempt);
    }

    @Transactional
    public ApiDtos.CompleteSentenceResponse completeSentence(Long userId, String sessionId, Long sentenceId,
                                                              ApiDtos.CompleteSentenceRequest request) {
        PracticeSession session = getSession(userId, sessionId);
        SentenceAttempt attempt = currentAttempt(session, sentenceId);
        ApiDtos.SentenceState state = toState(attempt);
        if (!state.canComplete()) {
            String message = state.requiresReplayAfterReveal()
                    ? "Bạn cần nghe lại câu đúng trọn vẹn trước khi tiếp tục."
                    : "Hãy hoàn thành hoặc xem đáp án của tất cả các ô từ.";
            throw new InvalidPracticeStateException(message);
        }

        attempt.setTimeSpentSeconds(request.timeSpentSeconds());
        attempt.markCompleted();
        int totalSentences = Math.toIntExact(sentenceRepository.countByLessonId(session.getLesson().getId()));
        if (attempt.getSentence().getPosition() >= totalSentences) {
            ApiDtos.LessonResult result = finishSession(session);
            return new ApiDtos.CompleteSentenceResponse(true, null, result);
        }

        int nextPosition = attempt.getSentence().getPosition() + 1;
        LessonSentence next = sentenceRepository.findByLessonIdAndPosition(session.getLesson().getId(), nextPosition)
                .orElseThrow(() -> new InvalidPracticeStateException("Không tìm thấy câu tiếp theo."));
        session.moveToSentence(nextPosition);
        SentenceAttempt nextAttempt = sentenceAttemptRepository.save(new SentenceAttempt(session, next, now()));
        return new ApiDtos.CompleteSentenceResponse(false, toState(nextAttempt), null);
    }

    private void revealSentence(PracticeSession session, SentenceAttempt attempt, List<String> words) {
        if (!attempt.isFullRevealUsed()) {
            attempt.markFullRevealUsed();
            session.incrementReveals();
        } else {
            attempt.requireReplayAfterReveal();
        }
        Set<Integer> properNames = properNameIndices(attempt.getSentence());
        for (int index = 0; index < words.size(); index++) {
            if (!properNames.contains(index)) {
                getOrCreateWordAttempt(attempt, index).reveal(HintType.REVEAL_SENTENCE);
            }
        }
    }

    private ApiDtos.LessonResult finishSession(PracticeSession session) {
        List<SentenceAttempt> attempts = sentenceAttemptRepository
                .findByPracticeSessionIdOrderBySentencePositionAsc(session.getId());
        List<ApiDtos.SentenceReview> sentenceReviews = new ArrayList<>();
        int totalWords = 0;
        int scoreableWords = 0;
        int firstCorrect = 0;
        int finalCorrect = 0;
        int knownMissed = 0;
        int unknown = 0;
        int practiceSeconds = 0;
        int firstLetterHints = 0;
        int wordReveals = 0;
        int sentenceReveals = 0;
        Map<Long, List<WordAttemptEvent>> eventsByWord = new HashMap<>();
        wordAttemptEventRepository.findBySessionIds(List.of(session.getId()))
                .forEach(event -> eventsByWord
                        .computeIfAbsent(event.getWordAttempt().getId(), ignored -> new ArrayList<>())
                        .add(event));

        for (SentenceAttempt attempt : attempts) {
            List<String> expectedWords = tokenizer.words(attempt.getSentence().getTranscript());
            List<WordAttempt> wordAttempts = wordAttemptRepository
                    .findBySentenceAttemptIdOrderByWordIndex(attempt.getId());
            totalWords += expectedWords.size();
            Set<Integer> properNames = properNameIndices(attempt.getSentence());
            scoreableWords += expectedWords.size() - properNames.size();
            practiceSeconds += attempt.getTimeSpentSeconds();
            firstLetterHints += attempt.getFirstLetterHintCount();
            wordReveals += attempt.getWordRevealCount();
            sentenceReveals += attempt.isFullRevealUsed() ? 1 : 0;
            for (WordAttempt word : wordAttempts) {
                if (properNames.contains(word.getWordIndex())) {
                    continue;
                }
                if (Boolean.TRUE.equals(word.getFirstAttemptCorrect())) {
                    firstCorrect++;
                }
                if (word.isFinalCorrect()) {
                    finalCorrect++;
                }
                if (Boolean.FALSE.equals(word.getFirstAttemptCorrect())) {
                    if (word.getErrorType() == LearnerErrorType.UNKNOWN) {
                        unknown++;
                    } else {
                        knownMissed++;
                    }
                }
            }
            sentenceReviews.add(toSentenceReview(attempt, expectedWords, wordAttempts, eventsByWord));
        }

        ScoringService.ScoreBreakdown score = scoringService.calculate(
                scoreableWords, firstCorrect, finalCorrect, firstLetterHints, wordReveals,
                sentenceReveals, session.getTotalReplays(), attempts.size()
        );
        Instant completedAt = now();
        session.complete(completedAt, practiceSeconds, score.firstAttemptAccuracy(), score.finalAccuracy(),
                knownMissed, unknown, score.overallScore());
        ApiDtos.LessonReview review = toLessonReview(session, totalWords, sentenceReviews);
        return toResult(session, review);
    }

    ApiDtos.LessonResult toResult(PracticeSession session, ApiDtos.LessonReview review) {
        return new ApiDtos.LessonResult(
                session.getId(), session.getLesson().getId(), session.getLesson().getTitle(),
                session.getLesson().getLevel(), session.getFirstAttemptAccuracy(), session.getFinalAccuracy(),
                session.getTotalReplays(), session.getHintCount(), session.getRevealCount(),
                session.getKnownMissedCount(), session.getUnknownCount(), session.getTotalPracticeSeconds(),
                session.getOverallScore(), session.getCompletedAt(), review,
                assessmentService.assess(session.getUser().getId())
        );
    }

    ApiDtos.LessonReview toLessonReview(PracticeSession session, int totalWords,
                                                 List<ApiDtos.SentenceReview> sentences) {
        Lesson lesson = session.getLesson();
        String sourceName = "DictaLab";
        String sourceUrl = null;
        if (!sentences.isEmpty() && sentences.get(0).audioUrl() != null) {
            String prefix = "/audio/elllo/";
            String audioUrl = sentences.get(0).audioUrl();
            if (audioUrl.startsWith(prefix)) {
                String[] path = audioUrl.substring(prefix.length()).split("/", 3);
                if (path.length >= 2) {
                    sourceName = "ELLLO — English Listening Lesson Library Online";
                    sourceUrl = "https://elllo.org/book/" + path[0] + "/" + path[1] + ".html";
                }
            }
        }
        boolean completeDuration = !sentences.isEmpty() && sentences.stream().allMatch(sentence ->
                sentence.audioStartSeconds() != null && sentence.audioEndSeconds() != null
                        && sentence.audioEndSeconds() > sentence.audioStartSeconds());
        Double durationSeconds = completeDuration
                ? Math.round(sentences.stream().mapToDouble(sentence ->
                        sentence.audioEndSeconds() - sentence.audioStartSeconds()).sum() * 100.0) / 100.0
                : null;
        Integer speechRateWpm = durationSeconds == null || durationSeconds == 0
                ? null : (int) Math.round(totalWords * 60.0 / durationSeconds);
        return new ApiDtos.LessonReview(
                sourceName, sourceUrl, lesson.getTopic(), durationSeconds, speechRateWpm,
                lesson.getSpeakerCount(), lesson.getAccentProfile(),
                lesson.getVocabularyDifficulty(), lesson.getGrammarDifficulty(), lesson.getSentenceDifficulty(),
                lesson.isAssessmentCalibrated(), sentences.size(), totalWords, sentences
        );
    }

    ApiDtos.SentenceReview toSentenceReview(SentenceAttempt attempt, List<String> expectedWords,
                                                     List<WordAttempt> wordAttempts,
                                                     Map<Long, List<WordAttemptEvent>> eventsByWord) {
        Map<Integer, WordAttempt> attemptsByIndex = new HashMap<>();
        wordAttempts.forEach(word -> attemptsByIndex.put(word.getWordIndex(), word));
        List<ApiDtos.WordReview> words = java.util.stream.IntStream.range(0, expectedWords.size())
                .mapToObj(index -> {
                    WordAttempt word = attemptsByIndex.get(index);
                    WordLearningOutcome outcome = word == null ? null : word.learningOutcome();
                    if (word != null && word.isRevealed() && word.getRevealType() == null
                            && attempt.isFullRevealUsed()) {
                        outcome = WordLearningOutcome.SENTENCE_REVEAL;
                    }
                    return new ApiDtos.WordReview(
                            index,
                            expectedWords.get(index),
                            word == null ? null : word.getFirstAnswer(),
                            word == null ? null : word.getLastAnswer(),
                            word == null ? 0 : word.getAttemptCount(),
                            outcome,
                            word == null ? null : word.getErrorType(),
                            word == null ? null : word.getInferredErrorCategory(),
                            word == null ? List.of() : eventsByWord.getOrDefault(word.getId(), List.of()).stream()
                                    .map(event -> new ApiDtos.WordAttemptReview(
                                            event.getAttemptNumber(), event.getRawAnswer(),
                                            event.getNormalizedAnswer(), event.isCorrect(),
                                            event.getResponseTimeMs(), event.getErrorCategory()
                                    )).toList()
                    );
                })
                .toList();
        return new ApiDtos.SentenceReview(
                attempt.getSentence().getId(), attempt.getSentence().getPosition(),
                attempt.getSentence().getTranscript(), attempt.getSentence().getAudioUrl(),
                attempt.getSentence().getAudioStartSeconds(), attempt.getSentence().getAudioEndSeconds(),
                attempt.getReplayCount(), attempt.getTimeSpentSeconds(), attempt.getFirstLetterHintCount(),
                attempt.getWordRevealCount(), attempt.isFullRevealUsed(), attempt.getAveragePlaybackSpeed(),
                attempt.getSlowPlaybackCount(), words
        );
    }

    private ApiDtos.SentenceState toState(SentenceAttempt attempt) {
        LessonSentence sentence = attempt.getSentence();
        List<TranscriptTokenizer.TranscriptToken> expectedTokens = tokenizer.tokens(sentence.getTranscript());
        Set<Integer> properNames = properNameIndices(sentence);
        Map<Integer, WordAttempt> attemptsByIndex = new HashMap<>();
        if (attempt.getId() != null) {
            wordAttemptRepository.findBySentenceAttemptIdOrderByWordIndex(attempt.getId())
                    .forEach(word -> attemptsByIndex.put(word.getWordIndex(), word));
        }

        List<ApiDtos.WordSlot> slots = java.util.stream.IntStream.range(0, expectedTokens.size())
                .mapToObj(index -> toWordSlot(index, expectedTokens.get(index), attemptsByIndex.get(index),
                        properNames.contains(index)))
                .toList();
        boolean resolved = slots.stream().allMatch(slot -> slot.correct() || slot.revealed());
        boolean requiresReplay = attempt.hasAnswerReveal() && !attempt.isReplayedAfterReveal();
        int totalSentences = Math.toIntExact(sentenceRepository.countByLessonId(sentence.getLesson().getId()));
        return new ApiDtos.SentenceState(
                sentence.getId(), sentence.getPosition(), totalSentences, slots,
                sentence.getTranscript(), sentence.getAudioUrl(),
                sentence.getAudioStartSeconds(), sentence.getAudioEndSeconds(), sentence.getSpeechRate(),
                attempt.getReplayCount(), attempt.getFirstLetterHintCount() + attempt.getWordRevealCount(),
                attempt.isFullRevealUsed() ? 1 : 0, attempt.getReplayCount() > 0,
                requiresReplay, resolved && !requiresReplay
        );
    }

    private ApiDtos.WordSlot toWordSlot(int index, TranscriptTokenizer.TranscriptToken token,
                                        WordAttempt attempt, boolean prefilled) {
        String expectedWord = token.word();
        boolean revealed = !prefilled && attempt != null && attempt.isRevealed();
        boolean correct = prefilled || attempt != null && attempt.isFinalCorrect();
        String firstLetter = !prefilled && attempt != null && attempt.hasFirstLetterHint()
                ? tokenizer.firstLetter(expectedWord)
                : null;
        String revealedText = revealed ? expectedWord : null;
        String userInput = prefilled ? expectedWord : attempt == null ? null : attempt.getLastAnswer();
        LearnerErrorType errorType = prefilled || attempt == null ? null : attempt.getErrorType();
        return new ApiDtos.WordSlot(
                index, tokenizer.visibleLength(expectedWord), tokenizer.mask(expectedWord),
                token.punctuationBefore(), token.punctuationAfter(),
                firstLetter, revealedText, userInput, correct, revealed, prefilled, errorType
        );
    }

    private Set<Integer> properNameIndices(LessonSentence sentence) {
        List<String> words = tokenizer.words(sentence.getTranscript());
        Set<Integer> indices = new HashSet<>();
        previewWordRepository.findByLessonIdOrderByPositionAsc(sentence.getLesson().getId()).stream()
                .filter(word -> word.isProperName())
                .forEach(preview -> {
                    List<String> name = tokenizer.words(preview.getWord());
                    for (int start = 0; start + name.size() <= words.size(); start++) {
                        if (!name.isEmpty() && words.subList(start, start + name.size()).equals(name)) {
                            for (int index = start; index < start + name.size(); index++) {
                                indices.add(index);
                            }
                        }
                    }
                });
        return indices;
    }

    private PracticeSession getSession(Long userId, String sessionId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên luyện tập."));
    }

    private void ensureInProgress(PracticeSession session) {
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new InvalidPracticeStateException("Phiên luyện tập này đã hoàn thành.");
        }
    }

    private LessonSentence currentSentence(PracticeSession session) {
        return sentenceRepository.findByLessonIdAndPosition(
                        session.getLesson().getId(), session.getCurrentSentencePosition())
                .orElseThrow(() -> new InvalidPracticeStateException("Không tìm thấy câu hiện tại."));
    }

    private SentenceAttempt currentAttempt(PracticeSession session, Long sentenceId) {
        ensureInProgress(session);
        LessonSentence current = currentSentence(session);
        if (!current.getId().equals(sentenceId)) {
            throw new InvalidPracticeStateException("Câu này không phải câu hiện tại của phiên luyện tập.");
        }
        return getOrFailAttempt(session, sentenceId);
    }

    private SentenceAttempt getOrFailAttempt(PracticeSession session, Long sentenceId) {
        return sentenceAttemptRepository.findByPracticeSessionIdAndSentenceId(session.getId(), sentenceId)
                .orElseThrow(() -> new InvalidPracticeStateException("Không tìm thấy lượt làm câu này."));
    }

    private WordAttempt getOrCreateWordAttempt(SentenceAttempt sentenceAttempt, int wordIndex) {
        return wordAttemptRepository.findBySentenceAttemptIdAndWordIndex(sentenceAttempt.getId(), wordIndex)
                .orElseGet(() -> wordAttemptRepository.save(new WordAttempt(sentenceAttempt, wordIndex)));
    }

    private void validateWordIndex(int index, List<String> words) {
        if (index < 0 || index >= words.size()) {
            throw new InvalidPracticeStateException("Vị trí từ không hợp lệ.");
        }
    }

    private void requireListening(SentenceAttempt attempt) {
        if (attempt.getReplayCount() == 0) {
            throw new InvalidPracticeStateException("Hãy nghe câu ít nhất một lần trước khi nhập đáp án.");
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
