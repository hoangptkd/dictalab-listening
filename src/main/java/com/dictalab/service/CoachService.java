package com.dictalab.service;

import com.dictalab.dto.ApiDtos;
import com.dictalab.exception.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class CoachService {
    private final HistoryService history;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final CoachAnalysis analysis;
    private final TranscriptTokenizer tokenizer;
    private final ChatGptWebCoachClient client;
    // ponytail: the browser bridge serializes requests; use a distributed lease if running multiple app instances.
    private final Semaphore generation = new Semaphore(1);

    public CoachService(HistoryService history, JdbcTemplate db, ObjectMapper json, CoachAnalysis analysis,
                        TranscriptTokenizer tokenizer, ChatGptWebCoachClient client) {
        this.history = history;
        this.db = db;
        this.json = json;
        this.analysis = analysis;
        this.tokenizer = tokenizer;
        this.client = client;
    }

    public CoachAnalysis.Report report(Long userId, String sessionId, boolean generate) {
        ApiDtos.LessonResult result = history.detail(userId, sessionId); // ownership + completed state
        List<String> cached = db.queryForList("SELECT report_json FROM dictation_coach_reports WHERE session_id=?",
                String.class, sessionId);
        if (!cached.isEmpty()) return json.readValue(cached.get(0), CoachAnalysis.Report.class);
        Set<String> names = properNames(result.lessonId());
        List<String> earlier = db.queryForList("""
                SELECT p.id FROM practice_sessions p JOIN lessons l ON l.id=p.lesson_id
                WHERE p.user_id=? AND p.status='COMPLETED' AND p.completed_at<? AND l.level=?
                AND p.room_id IS NULL AND p.first_exposure=TRUE
                ORDER BY p.completed_at DESC LIMIT 5
                """, String.class, userId, Timestamp.from(result.completedAt()), result.level().name());
        Map<String, Integer> repeats = new HashMap<>();
        List<Double> scores = new ArrayList<>();
        if (!earlier.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(earlier.size(), "?"));
            scores = db.queryForList("SELECT first_attempt_accuracy FROM practice_sessions WHERE id IN (" + placeholders + ")",
                    Double.class, earlier.toArray());
            db.query("""
                    SELECT s.transcript,w.word_index FROM word_attempts w
                    JOIN sentence_attempts a ON a.id=w.sentence_attempt_id
                    JOIN lesson_sentences s ON s.id=a.sentence_id
                    WHERE w.first_attempt_correct=FALSE AND a.practice_session_id IN (
                    """ + placeholders + ")", row -> {
                List<String> words = tokenizer.words(row.getString("transcript"));
                int index = row.getInt("word_index");
                if (index >= 0 && index < words.size()) repeats.merge(tokenizer.normalize(words.get(index)), 1, Integer::sum);
            }, earlier.toArray());
        }
        boolean comparable = Boolean.TRUE.equals(db.queryForObject(
                "SELECT (first_exposure=TRUE AND room_id IS NULL) FROM practice_sessions WHERE id=?", Boolean.class, sessionId));
        CoachAnalysis.Progress progress = analysis.progress(result.firstAttemptAccuracy(), scores, comparable);
        List<CoachAnalysis.ReviewItem> candidates = analysis.candidates(result, names, repeats);
        CoachAnalysis.Report fallback = analysis.baseline(candidates, progress, client.enabled(), "RULES");
        if (!generate || !client.enabled() || candidates.isEmpty()) return fallback;
        if (!generation.tryAcquire()) return analysis.baseline(candidates, progress, true, "BUSY");
        try {
            // Check again after acquiring the bridge slot: another request may just have saved this report.
            cached = db.queryForList("SELECT report_json FROM dictation_coach_reports WHERE session_id=?", String.class, sessionId);
            if (!cached.isEmpty()) return json.readValue(cached.get(0), CoachAnalysis.Report.class);
            var evidence = Map.of("level", result.level(), "firstAttemptAccuracy", result.firstAttemptAccuracy(),
                    "progress", progress, "candidates", candidates.stream().limit(40).map(item -> Map.of(
                            "id", item.id(), "pattern", item.pattern(), "heard", item.heard(), "expected", item.expected(),
                            "sentence", item.transcript(), "priority", item.priority(), "occurrences", item.occurrences())).toList());
            CoachAnalysis.Report interpreted;
            try {
                interpreted = analysis.interpret(client.interpret(evidence), candidates.stream().limit(40).toList(), progress);
            } catch (RuntimeException exception) {
                // No raw response/transcript in logs; failures never prevent completion or review.
                return analysis.baseline(candidates, progress, true, "UNAVAILABLE");
            }
            try {
                db.update("INSERT INTO dictation_coach_reports(session_id,report_json) VALUES (?,?)",
                        sessionId, json.writeValueAsString(interpreted));
            } catch (DuplicateKeyException ignored) {
                return report(userId, sessionId, false);
            }
            return interpreted;
        } finally {
            generation.release();
        }
    }

    public record BankEntry(String sessionId, CoachAnalysis.ReviewItem item) {}

    private Set<String> properNames(Long lessonId) {
        Set<String> names = new HashSet<>();
        db.queryForList("SELECT word FROM lesson_preview_words WHERE lesson_id=? AND proper_name=TRUE",
                String.class, lessonId).forEach(name -> names.add(tokenizer.normalize(name)));
        return names;
    }

    public List<BankEntry> bank(Long userId) {
        return db.query("""
                SELECT session_id,item_json FROM dictation_error_bank WHERE user_id=?
                ORDER BY created_at DESC,id DESC LIMIT 100
                """, (row, index) -> new BankEntry(row.getString("session_id"),
                json.readValue(row.getString("item_json"), CoachAnalysis.ReviewItem.class)), userId);
    }

    public void saveError(Long userId, String sessionId, String itemId) {
        CoachAnalysis.Report report = report(userId, sessionId, false);
        CoachAnalysis.ReviewItem item = report.reviewItems().stream().filter(candidate -> candidate.id().equals(itemId))
                .findFirst().orElseGet(() -> {
                    // AI may finish reranking while the learner saves an item from the initial shortlist.
                    ApiDtos.LessonResult result = history.detail(userId, sessionId);
                    return analysis.candidates(result, properNames(result.lessonId()), Map.of()).stream()
                            .filter(candidate -> candidate.id().equals(itemId)).findFirst()
                            .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lỗi cần lưu trong phiên này."));
                });
        try {
            db.update("INSERT INTO dictation_error_bank(user_id,session_id,item_id,item_json) VALUES (?,?,?,?)",
                    userId, sessionId, itemId, json.writeValueAsString(item));
        } catch (DuplicateKeyException ignored) {
            // Adding the same evidence twice is idempotent.
        }
    }
}
