package com.dictalab.service;

import com.dictalab.domain.PracticeSession;
import com.dictalab.domain.CefrLevel;
import com.dictalab.domain.SentenceAttempt;
import com.dictalab.domain.SessionStatus;
import com.dictalab.domain.WordAttempt;
import com.dictalab.domain.WordAttemptEvent;
import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.PracticeSessionRepository;
import com.dictalab.repository.SentenceAttemptRepository;
import com.dictalab.repository.WordAttemptEventRepository;
import com.dictalab.repository.WordAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class HistoryService {

    private final PracticeSessionRepository sessionRepository;
    private final SentenceAttemptRepository sentenceAttemptRepository;
    private final WordAttemptRepository wordAttemptRepository;
    private final WordAttemptEventRepository wordAttemptEventRepository;
    private final TranscriptTokenizer tokenizer;
    private final ListeningAssessmentService assessmentService;
    private final PracticeService practiceService;

    public HistoryService(PracticeSessionRepository sessionRepository,
                          SentenceAttemptRepository sentenceAttemptRepository,
                          WordAttemptRepository wordAttemptRepository,
                          WordAttemptEventRepository wordAttemptEventRepository,
                          TranscriptTokenizer tokenizer,
                          ListeningAssessmentService assessmentService,
                          PracticeService practiceService) {
        this.sessionRepository = sessionRepository;
        this.sentenceAttemptRepository = sentenceAttemptRepository;
        this.wordAttemptRepository = wordAttemptRepository;
        this.wordAttemptEventRepository = wordAttemptEventRepository;
        this.tokenizer = tokenizer;
        this.assessmentService = assessmentService;
        this.practiceService = practiceService;
    }

    public ApiDtos.HistoryPage list(Long userId, CefrLevel level, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Phân trang không hợp lệ.");
        }
        var sessions = sessionRepository.history(userId, SessionStatus.COMPLETED, level,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "completedAt", "id")));
        return new ApiDtos.HistoryPage(sessions.getContent().stream().map(this::toHistoryEntry).toList(),
                page, size, sessions.getTotalElements(), sessions.getTotalPages());
    }

    public ApiDtos.LessonResult detail(Long userId, String sessionId) {
        return detail(userId, sessionId, true);
    }

    public ApiDtos.LessonResult detailForCoach(Long userId, String sessionId) {
        return detail(userId, sessionId, false);
    }

    private ApiDtos.LessonResult detail(Long userId, String sessionId, boolean includeAssessment) {
        PracticeSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên luyện tập."));
        if (session.getStatus() != SessionStatus.COMPLETED) {
            throw new ResourceNotFoundException("Phiên luyện tập này chưa hoàn thành.");
        }
        return buildResult(session, includeAssessment);
    }

    private ApiDtos.HistoryEntry toHistoryEntry(PracticeSession session) {
        return new ApiDtos.HistoryEntry(
                session.getId(),
                session.getLesson().getId(),
                session.getLesson().getTitle(),
                session.getLesson().getLevel(),
                session.getOverallScore(),
                session.getFirstAttemptAccuracy(),
                session.getFinalAccuracy(),
                session.getTotalReplays(),
                session.getHintCount(),
                session.getRevealCount(),
                session.getTotalPracticeSeconds(),
                session.isFirstExposure(),
                session.getCompletedAt()
        );
    }

    private ApiDtos.LessonResult buildResult(PracticeSession session, boolean includeAssessment) {
        List<SentenceAttempt> attempts = sentenceAttemptRepository
                .findByPracticeSessionIdOrderBySentencePositionAsc(session.getId());

        Map<Long, List<WordAttemptEvent>> eventsByWord = new HashMap<>();
        wordAttemptEventRepository.findBySessionIds(List.of(session.getId()))
                .forEach(event -> eventsByWord
                        .computeIfAbsent(event.getWordAttempt().getId(), ignored -> new ArrayList<>())
                        .add(event));

        List<ApiDtos.SentenceReview> sentenceReviews = new ArrayList<>();
        Map<Long,List<WordAttempt>> wordsBySentence=new HashMap<>();
        wordAttemptRepository.findBySessionIds(List.of(session.getId())).forEach(word ->
                wordsBySentence.computeIfAbsent(word.getSentenceAttempt().getId(), ignored -> new ArrayList<>()).add(word));
        wordsBySentence.values().forEach(words -> words.sort(java.util.Comparator.comparingInt(WordAttempt::getWordIndex)));
        int totalWords = 0;
        for (SentenceAttempt attempt : attempts) {
            List<String> expectedWords = tokenizer.words(attempt.getSentence().getTranscript());
            List<WordAttempt> wordAttempts = wordsBySentence.getOrDefault(attempt.getId(),List.of());
            totalWords += expectedWords.size();
            sentenceReviews.add(practiceService.toSentenceReview(attempt, expectedWords, wordAttempts, eventsByWord));
        }

        ApiDtos.LessonReview review = practiceService.toLessonReview(session, totalWords, sentenceReviews);
        return practiceService.toResult(session, review, includeAssessment);
    }
}
