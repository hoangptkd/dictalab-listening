package com.dictalab.service;

import com.dictalab.config.AssessmentProperties;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.ListeningErrorCategory;
import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.SentenceAttempt;
import com.dictalab.domain.SessionStatus;
import com.dictalab.domain.WordAttempt;
import com.dictalab.domain.WordAttemptEvent;
import com.dictalab.dto.ApiDtos;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.repository.SentenceAttemptRepository;
import com.dictalab.repository.WordAttemptEventRepository;
import com.dictalab.repository.WordAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ListeningAssessmentService {

    private final PracticeSessionRepository sessionRepository;
    private final SentenceAttemptRepository sentenceAttemptRepository;
    private final WordAttemptRepository wordAttemptRepository;
    private final WordAttemptEventRepository eventRepository;
    private final TranscriptTokenizer tokenizer;
    private final AssessmentProperties properties;
    private final PreviewVocabularyService previewVocabularyService;

    public ListeningAssessmentService(PracticeSessionRepository sessionRepository,
                                      SentenceAttemptRepository sentenceAttemptRepository,
                                      WordAttemptRepository wordAttemptRepository,
                                      WordAttemptEventRepository eventRepository,
                                      TranscriptTokenizer tokenizer,
                                      AssessmentProperties properties,
                                      PreviewVocabularyService previewVocabularyService) {
        this.sessionRepository = sessionRepository;
        this.sentenceAttemptRepository = sentenceAttemptRepository;
        this.wordAttemptRepository = wordAttemptRepository;
        this.eventRepository = eventRepository;
        this.tokenizer = tokenizer;
        this.properties = properties;
        this.previewVocabularyService = previewVocabularyService;
    }

    public ApiDtos.ListeningAssessment assess(Long userId) {
        List<PracticeSession> evidence = selectEvidence(userId);
        if (evidence.isEmpty()) {
            return emptyAssessment();
        }

        List<String> sessionIds = evidence.stream().map(PracticeSession::getId).toList();
        List<SentenceAttempt> sentenceAttempts = sentenceAttemptRepository.findBySessionIds(sessionIds);
        List<WordAttempt> wordAttempts = wordAttemptRepository.findBySessionIds(sessionIds);
        List<WordAttemptEvent> events = eventRepository.findBySessionIds(sessionIds);

        Map<Long, List<WordAttempt>> wordsBySentence = new HashMap<>();
        for (WordAttempt word : wordAttempts) {
            wordsBySentence.computeIfAbsent(word.getSentenceAttempt().getId(), ignored -> new ArrayList<>()).add(word);
        }
        Map<String, Signal> signals = new HashMap<>();
        Map<Long, Set<String>> previewWordsByLesson = previewVocabularyService.normalizedWordsForLessons(
                evidence.stream().map(session -> session.getLesson().getId()).distinct().toList());
        for (PracticeSession session : evidence) {
            signals.put(session.getId(), new Signal());
        }
        for (SentenceAttempt sentenceAttempt : sentenceAttempts) {
            Signal signal = signals.get(sentenceAttempt.getPracticeSession().getId());
            if (signal == null) {
                continue;
            }
            addSentenceSignal(signal, sentenceAttempt,
                    wordsBySentence.getOrDefault(sentenceAttempt.getId(), List.of()),
                    previewWordsByLesson.getOrDefault(
                            sentenceAttempt.getPracticeSession().getLesson().getId(), Set.of()
                    ));
        }

        Signal total = new Signal();
        signals.values().forEach(total::merge);
        long responseTimeSum = events.stream().mapToLong(WordAttemptEvent::getResponseTimeMs).sum();
        long averageResponseTime = events.isEmpty() ? 0 : Math.round(responseTimeSum / (double) events.size());
        ApiDtos.AssessmentMetrics metrics = new ApiDtos.AssessmentMetrics(
                percent(total.unassistedFirstCorrect, total.totalWords),
                percent(total.firstCorrect, total.totalWords),
                percent(total.finalCorrect, total.totalWords),
                percent(total.functionCorrect, total.functionWords),
                percent(total.contentCorrect, total.contentWords),
                total.functionWords,
                total.contentWords,
                ratio(total.additionalReplays, total.sentenceCount),
                percent(total.hintedWords, total.totalWords),
                percent(total.slowPlaybackEvents, total.playbackEvents),
                percent(total.revealedWords, total.totalWords),
                averageResponseTime
        );

        EnumMap<ListeningErrorCategory, Integer> errors = new EnumMap<>(ListeningErrorCategory.class);
        for (ListeningErrorCategory category : ListeningErrorCategory.values()) {
            errors.put(category, 0);
        }
        events.stream().map(WordAttemptEvent::getErrorCategory).filter(java.util.Objects::nonNull)
                .forEach(category -> errors.merge(category, 1, Integer::sum));
        wordAttempts.stream()
                .filter(word -> word.getAttemptCount() == 0 && word.getInferredErrorCategory() != null)
                .forEach(word -> errors.merge(word.getInferredErrorCategory(), 1, Integer::sum));

        double estimatedPosition = estimatePosition(evidence, signals);
        CefrLevel estimatedLevel = CefrLevel.values()[(int) clamp(Math.round(estimatedPosition), 0, 3)];
        double abilityScore = round1(clamp((estimatedPosition + 1.0) * 25.0, 0, 100));
        int calibratedLessons = (int) evidence.stream()
                .filter(session -> session.getLesson().isAssessmentCalibrated()).count();
        double confidence = confidence(evidence, total, events, calibratedLessons);
        boolean provisional = evidence.size() < properties.minimumUnseenLessons()
                || calibratedLessons < evidence.size();

        return new ApiDtos.ListeningAssessment(
                estimatedLevel,
                abilityScore,
                confidence,
                confidenceBand(confidence),
                provisional,
                evidence.size(),
                properties.minimumUnseenLessons(),
                calibratedLessons,
                metrics,
                new ApiDtos.ErrorCategoryBreakdown(
                        errors.get(ListeningErrorCategory.OMISSION),
                        errors.get(ListeningErrorCategory.INSERTION),
                        errors.get(ListeningErrorCategory.SUBSTITUTION),
                        errors.get(ListeningErrorCategory.SPELLING),
                        errors.get(ListeningErrorCategory.WORD_FORM),
                        errors.get(ListeningErrorCategory.WORD_ORDER)
                ),
                assessmentSummary(evidence.size(), calibratedLessons)
        );
    }

    private List<PracticeSession> selectEvidence(Long userId) {
        return sessionRepository.assessmentEvidence(userId, SessionStatus.COMPLETED,
                PageRequest.of(0, properties.maximumUnseenLessons()));
    }

    private void addSentenceSignal(Signal signal, SentenceAttempt sentenceAttempt,
                                   List<WordAttempt> wordAttempts, Set<String> previewWords) {
        signal.sentenceCount++;
        signal.additionalReplays += Math.max(0, sentenceAttempt.getReplayCount() - 1);
        signal.playbackEvents += sentenceAttempt.getPlaybackEventCount();
        signal.slowPlaybackEvents += sentenceAttempt.getSlowPlaybackCount();

        Map<Integer, WordAttempt> byIndex = new HashMap<>();
        wordAttempts.forEach(word -> byIndex.put(word.getWordIndex(), word));
        List<String> expectedWords = tokenizer.words(sentenceAttempt.getSentence().getTranscript());
        Double audioStart = sentenceAttempt.getSentence().getAudioStartSeconds();
        Double audioEnd = sentenceAttempt.getSentence().getAudioEndSeconds();
        if (audioStart != null && audioEnd != null && audioEnd > audioStart) {
            signal.audioDurationSeconds += audioEnd - audioStart;
            signal.audioWords += expectedWords.size();
        }
        for (int index = 0; index < expectedWords.size(); index++) {
            if (previewWords.contains(tokenizer.normalize(expectedWords.get(index)))) {
                continue;
            }
            WordAttempt word = byIndex.get(index);
            boolean firstCorrect = word != null && Boolean.TRUE.equals(word.getFirstAttemptCorrect());
            signal.totalWords++;
            if (firstCorrect) {
                signal.firstCorrect++;
                if (!word.isFirstAttemptAssisted()) {
                    signal.unassistedFirstCorrect++;
                }
            }
            if (word != null && word.isFinalCorrect()) {
                signal.finalCorrect++;
            }
            if (tokenizer.isFunctionWord(expectedWords.get(index))) {
                signal.functionWords++;
                if (firstCorrect) {
                    signal.functionCorrect++;
                }
            } else {
                signal.contentWords++;
                if (firstCorrect) {
                    signal.contentCorrect++;
                }
            }
            if (word != null && (word.hasFirstLetterHint() || word.isRevealed())) {
                signal.hintedWords++;
            }
            if (word != null && word.isRevealed()) {
                signal.revealedWords++;
            }
        }
    }

    private double estimatePosition(List<PracticeSession> evidence, Map<String, Signal> signals) {
        double weightedPosition = 0;
        double totalWeight = 0;
        for (int index = 0; index < evidence.size(); index++) {
            PracticeSession session = evidence.get(index);
            Signal signal = signals.get(session.getId());
            if (signal == null || signal.totalWords == 0) {
                continue;
            }
            double first = percent(signal.firstCorrect, signal.totalWords);
            double unassisted = percent(signal.unassistedFirstCorrect, signal.totalWords);
            double finalAccuracy = percent(signal.finalCorrect, signal.totalWords);
            double hintRate = fraction(signal.hintedWords, signal.totalWords);
            double revealRate = fraction(signal.revealedWords, signal.totalWords);
            double slowRate = fraction(signal.slowPlaybackEvents, signal.playbackEvents);
            double replayRate = clamp(ratio(signal.additionalReplays, signal.sentenceCount) / 2.0, 0, 1);
            double independence = 100 * (1 - clamp(
                    0.35 * hintRate + 0.25 * revealRate + 0.20 * slowRate + 0.20 * replayRate,
                    0, 1
            ));
            double performance = 0.50 * unassisted + 0.15 * first + 0.20 * finalAccuracy
                    + 0.15 * independence;
            double averageDifficulty = (session.getLesson().getVocabularyDifficulty()
                    + session.getLesson().getGrammarDifficulty()
                    + session.getLesson().getSentenceDifficulty()) / 3.0;
            double lessonPosition = 0.75 * session.getLesson().getLevel().ordinal()
                    + 0.25 * (averageDifficulty - 1.0);
            double wordsPerMinute = signal.audioDurationSeconds == 0
                    ? 120 : signal.audioWords * 60.0 / signal.audioDurationSeconds;
            double speechRateAdjustment = signal.audioDurationSeconds == 0
                    ? 0 : clamp((wordsPerMinute - 120.0) / 250.0, -0.15, 0.25);
            double speakerAdjustment = clamp((session.getLesson().getSpeakerCount() - 1) * 0.06, 0, 0.18);
            double performanceAdjustment = clamp((performance - 70.0) / 35.0, -1.0, 0.75);
            double position = clamp(
                    lessonPosition + speechRateAdjustment + speakerAdjustment + performanceAdjustment, 0, 3
            );
            double recencyWeight = 1.0 - Math.min(0.35, index * 0.05);
            double calibrationWeight = session.getLesson().isAssessmentCalibrated() ? 1.0 : 0.8;
            double weight = Math.max(1.0, Math.sqrt(signal.totalWords)) * recencyWeight * calibrationWeight;
            weightedPosition += position * weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? 0 : weightedPosition / totalWeight;
    }

    private double confidence(List<PracticeSession> evidence, Signal total,
                              List<WordAttemptEvent> events, int calibratedLessons) {
        double evidenceCoverage = clamp(evidence.size() / (double) properties.minimumUnseenLessons(), 0, 1);
        double evidenceBreadth = clamp(evidence.size() / (double) properties.maximumUnseenLessons(), 0, 1);
        double wordCoverage = clamp(total.totalWords / 120.0, 0, 1);
        long firstAttemptEvents = events.stream().filter(event -> event.getAttemptNumber() == 1).count();
        double telemetryCoverage = fraction(firstAttemptEvents, total.totalWords);
        long distinctLevels = evidence.stream().map(session -> session.getLesson().getLevel()).distinct().count();
        double levelCoverage = clamp(distinctLevels / (double) Math.min(3, evidence.size()), 0, 1);
        double calibrationCoverage = fraction(calibratedLessons, evidence.size());
        double confidence = 100 * (0.40 * evidenceCoverage + 0.15 * evidenceBreadth
                + 0.15 * wordCoverage + 0.10 * telemetryCoverage
                + 0.10 * levelCoverage + 0.10 * calibrationCoverage);
        if (evidence.size() < properties.minimumUnseenLessons()) {
            confidence = Math.min(confidence, 49);
        }
        if (calibratedLessons < evidence.size()) {
            confidence = Math.min(confidence, 65 + 20 * calibrationCoverage);
        }
        return round1(confidence);
    }

    private ApiDtos.ListeningAssessment emptyAssessment() {
        return new ApiDtos.ListeningAssessment(
                null, 0, 0, "Chưa đủ dữ liệu", true, 0,
                properties.minimumUnseenLessons(), 0,
                new ApiDtos.AssessmentMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new ApiDtos.ErrorCategoryBreakdown(0, 0, 0, 0, 0, 0),
                "Hoàn thành bài nghe chưa từng học để bắt đầu ước lượng năng lực."
        );
    }

    private String assessmentSummary(int evidenceLessons, int calibratedLessons) {
        if (evidenceLessons < properties.minimumUnseenLessons()) {
            int remaining = properties.minimumUnseenLessons() - evidenceLessons;
            return "Ước lượng tạm thời từ " + evidenceLessons + " bài mới; cần thêm " + remaining
                    + " bài chưa từng học để tăng độ tin cậy.";
        }
        if (calibratedLessons < evidenceLessons) {
            return "Đã đủ số bài mới, nhưng một số bài chưa được hiệu chỉnh chuẩn; kết quả dùng để chẩn đoán nội bộ.";
        }
        return "Ước lượng dựa trên các bài mới gần đây, độ khó bài và mức phụ thuộc vào replay/gợi ý.";
    }

    private String confidenceBand(double confidence) {
        if (confidence < 50) {
            return "Thấp";
        }
        if (confidence < 75) {
            return "Trung bình";
        }
        return "Cao";
    }

    private double percent(long numerator, long denominator) {
        return round1(100 * fraction(numerator, denominator));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : round1(numerator / (double) denominator);
    }

    private double fraction(long numerator, long denominator) {
        return denominator == 0 ? 0 : numerator / (double) denominator;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Signal {
        private int sentenceCount;
        private int totalWords;
        private int firstCorrect;
        private int unassistedFirstCorrect;
        private int finalCorrect;
        private int functionWords;
        private int functionCorrect;
        private int contentWords;
        private int contentCorrect;
        private int hintedWords;
        private int revealedWords;
        private int additionalReplays;
        private int playbackEvents;
        private int slowPlaybackEvents;
        private int audioWords;
        private double audioDurationSeconds;

        private void merge(Signal other) {
            sentenceCount += other.sentenceCount;
            totalWords += other.totalWords;
            firstCorrect += other.firstCorrect;
            unassistedFirstCorrect += other.unassistedFirstCorrect;
            finalCorrect += other.finalCorrect;
            functionWords += other.functionWords;
            functionCorrect += other.functionCorrect;
            contentWords += other.contentWords;
            contentCorrect += other.contentCorrect;
            hintedWords += other.hintedWords;
            revealedWords += other.revealedWords;
            additionalReplays += other.additionalReplays;
            playbackEvents += other.playbackEvents;
            slowPlaybackEvents += other.slowPlaybackEvents;
            audioWords += other.audioWords;
            audioDurationSeconds += other.audioDurationSeconds;
        }
    }
}
