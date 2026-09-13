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
import com.dictalab.domain.WordLearningOutcome;
import com.dictalab.domain.AppUser;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.LessonRepository;
import com.dictalab.repository.LessonSentenceRepository;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.repository.SentenceAttemptRepository;
import com.dictalab.repository.WordAttemptRepository;
import com.dictalab.repository.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class PracticeService {

    private final LessonRepository lessonRepository;
    private final LessonSentenceRepository sentenceRepository;
    private final PracticeSessionRepository sessionRepository;
    private final SentenceAttemptRepository sentenceAttemptRepository;
    private final WordAttemptRepository wordAttemptRepository;
    private final TranscriptTokenizer tokenizer;
    private final ScoringService scoringService;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public PracticeService(LessonRepository lessonRepository,
                           LessonSentenceRepository sentenceRepository,
                           PracticeSessionRepository sessionRepository,
                           SentenceAttemptRepository sentenceAttemptRepository,
                           WordAttemptRepository wordAttemptRepository,
                           AppUserRepository userRepository,
                           TranscriptTokenizer tokenizer,
                           ScoringService scoringService) {
        this.lessonRepository = lessonRepository;
        this.sentenceRepository = sentenceRepository;
        this.sessionRepository = sessionRepository;
        this.sentenceAttemptRepository = sentenceAttemptRepository;
        this.wordAttemptRepository = wordAttemptRepository;
        this.userRepository = userRepository;
        this.tokenizer = tokenizer;
        this.scoringService = scoringService;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public ApiDtos.PracticeStartResponse start(Long userId, ApiDtos.StartSessionRequest request) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản."));
        Lesson lesson = lessonRepository.findById(request.lessonId())
                .filter(Lesson::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy bài luyện."));

        PracticeSession existingSession = sessionRepository
                .findFirstByUser_IdAndLesson_IdAndStatusOrderByStartedAtDesc(
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

        PracticeSession session = sessionRepository.save(new PracticeSession(user, lesson, now()));
        SentenceAttempt attempt = sentenceAttemptRepository.save(new SentenceAttempt(session, firstSentence, now()));
        return new ApiDtos.PracticeStartResponse(
                session.getId(), lesson.getId(), lesson.getTitle(), lesson.getLevel(), toState(attempt)
        );
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
            attempt.incrementReplayCount();
            session.incrementReplays();
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

        WordAttempt wordAttempt = getOrCreateWordAttempt(attempt, wordIndex);
        String expected = tokenizer.normalize(expectedWords.get(wordIndex));
        boolean correct = expected.equals(tokenizer.normalize(request.answer()));
        wordAttempt.recordAnswer(request.answer().trim(), correct);
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
        for (int index = 0; index < words.size(); index++) {
            getOrCreateWordAttempt(attempt, index).reveal(HintType.REVEAL_SENTENCE);
        }
    }

    private ApiDtos.LessonResult finishSession(PracticeSession session) {
        List<SentenceAttempt> attempts = sentenceAttemptRepository
                .findByPracticeSessionIdOrderBySentencePositionAsc(session.getId());
        List<ApiDtos.SentenceReview> sentenceReviews = new ArrayList<>();
        int totalWords = 0;
        int firstCorrect = 0;
        int finalCorrect = 0;
        int knownMissed = 0;
        int unknown = 0;
        int practiceSeconds = 0;
        int firstLetterHints = 0;
        int wordReveals = 0;
        int sentenceReveals = 0;

        for (SentenceAttempt attempt : attempts) {
            List<String> expectedWords = tokenizer.words(attempt.getSentence().getTranscript());
            List<WordAttempt> wordAttempts = wordAttemptRepository
                    .findBySentenceAttemptIdOrderByWordIndex(attempt.getId());
            totalWords += expectedWords.size();
            practiceSeconds += attempt.getTimeSpentSeconds();
            firstLetterHints += attempt.getFirstLetterHintCount();
            wordReveals += attempt.getWordRevealCount();
            sentenceReveals += attempt.isFullRevealUsed() ? 1 : 0;
            for (WordAttempt word : wordAttempts) {
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
            sentenceReviews.add(toSentenceReview(attempt, expectedWords, wordAttempts));
        }

        ScoringService.ScoreBreakdown score = scoringService.calculate(
                totalWords, firstCorrect, finalCorrect, firstLetterHints, wordReveals,
                sentenceReveals, session.getTotalReplays(), attempts.size()
        );
        Instant completedAt = now();
        session.complete(completedAt, practiceSeconds, score.firstAttemptAccuracy(), score.finalAccuracy(),
                knownMissed, unknown, score.overallScore());
        ApiDtos.LessonReview review = toLessonReview(session, totalWords, sentenceReviews);
        return toResult(session, review);
    }

    private ApiDtos.LessonResult toResult(PracticeSession session, ApiDtos.LessonReview review) {
        return new ApiDtos.LessonResult(
                session.getId(), session.getLesson().getId(), session.getLesson().getTitle(),
                session.getLesson().getLevel(), session.getFirstAttemptAccuracy(), session.getFinalAccuracy(),
                session.getTotalReplays(), session.getHintCount(), session.getRevealCount(),
                session.getKnownMissedCount(), session.getUnknownCount(), session.getTotalPracticeSeconds(),
                session.getOverallScore(), session.getCompletedAt(), review
        );
    }

    private ApiDtos.LessonReview toLessonReview(PracticeSession session, int totalWords,
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
        return new ApiDtos.LessonReview(
                sourceName, sourceUrl, lesson.getTopic(), sentences.size(), totalWords, sentences
        );
    }

    private ApiDtos.SentenceReview toSentenceReview(SentenceAttempt attempt, List<String> expectedWords,
                                                     List<WordAttempt> wordAttempts) {
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
                            word == null ? null : word.getErrorType()
                    );
                })
                .toList();
        return new ApiDtos.SentenceReview(
                attempt.getSentence().getId(), attempt.getSentence().getPosition(),
                attempt.getSentence().getTranscript(), attempt.getSentence().getAudioUrl(),
                attempt.getSentence().getAudioStartSeconds(), attempt.getSentence().getAudioEndSeconds(),
                attempt.getReplayCount(), attempt.getTimeSpentSeconds(), attempt.getFirstLetterHintCount(),
                attempt.getWordRevealCount(), attempt.isFullRevealUsed(), words
        );
    }

    private ApiDtos.SentenceState toState(SentenceAttempt attempt) {
        LessonSentence sentence = attempt.getSentence();
        List<String> expectedWords = tokenizer.words(sentence.getTranscript());
        Map<Integer, WordAttempt> attemptsByIndex = new HashMap<>();
        if (attempt.getId() != null) {
            wordAttemptRepository.findBySentenceAttemptIdOrderByWordIndex(attempt.getId())
                    .forEach(word -> attemptsByIndex.put(word.getWordIndex(), word));
        }

        List<ApiDtos.WordSlot> slots = java.util.stream.IntStream.range(0, expectedWords.size())
                .mapToObj(index -> toWordSlot(index, expectedWords.get(index), attemptsByIndex.get(index)))
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

    private ApiDtos.WordSlot toWordSlot(int index, String expectedWord, WordAttempt attempt) {
        boolean revealed = attempt != null && attempt.isRevealed();
        boolean correct = attempt != null && attempt.isFinalCorrect();
        String firstLetter = attempt != null && attempt.hasFirstLetterHint()
                ? tokenizer.firstLetter(expectedWord)
                : null;
        String revealedText = revealed ? expectedWord : null;
        String userInput = attempt == null ? null : attempt.getLastAnswer();
        LearnerErrorType errorType = attempt == null ? null : attempt.getErrorType();
        return new ApiDtos.WordSlot(
                index, tokenizer.visibleLength(expectedWord), tokenizer.mask(expectedWord),
                firstLetter, revealedText, userInput, correct, revealed, errorType
        );
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
