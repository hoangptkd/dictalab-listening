package com.dictalab.service;

import com.dictalab.domain.PracticeSession;
import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import com.dictalab.repository.PracticeSessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Function;
import java.util.function.Supplier;

/** Receipt and domain writes share one database transaction and the existing session lock. */
@Service
public class PracticeSubmissions {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final PracticeSessionRepository sessions;

    public PracticeSubmissions(JdbcTemplate db, ObjectMapper json, PracticeSessionRepository sessions) {
        this.db = db;
        this.json = json;
        this.sessions = sessions;
    }

    @Transactional(timeout = 20)
    public <T> T execute(long user, String session, String key, String action, Object payload,
                         Class<T> type, Supplier<T> write) {
        // Older clients remain compatible; they do not get transport retry protection.
        if (key == null) return write.get();
        return execute(user, session, key, action, payload, type, locked -> write.get());
    }

    @Transactional(timeout = 20)
    public <T> T execute(long user, String session, String key, String action, Object payload,
                         Class<T> type, Function<PracticeSession, T> write) {
        if (key != null && !key.matches("[A-Za-z0-9_-]{8,80}"))
            throw new InvalidPracticeStateException("Mã gửi bài không hợp lệ.");
        PracticeSession locked = sessions.lockOwned(session, user)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên luyện."));
        if (key == null) return write.apply(locked);
        String hash = AiBackgroundJobs.hash(action + "\n" + json.writeValueAsString(payload));
        // Ownership is already locked; read the retry and its action limit in one round trip.
        var receipt = db.queryForMap("""
                SELECT saved.action,saved.request_hash,saved.response_json,
                       (SELECT COUNT(*) FROM practice_submission_receipts WHERE session_id=? AND action=?) AS receipt_count
                FROM (SELECT 1) seed
                LEFT JOIN practice_submission_receipts saved ON saved.session_id=? AND saved.submission_key=?
                """, session, action, session, key);
        if (receipt.get("action") != null) {
            if (!action.equals(receipt.get("action")) || !hash.equals(receipt.get("request_hash")))
                throw new InvalidPracticeStateException("Mã gửi bài đã được dùng với nội dung khác.");
            return json.readValue((String) receipt.get("response_json"), type);
        }
        long receiptCount = ((Number) receipt.get("receipt_count")).longValue();
        // Keep all accepted retry keys; reject new writes at the bound instead of expiring deduplication.
        if (receiptCount >= 500) throw new InvalidPracticeStateException("Đã đạt giới hạn thao tác này. Hãy tiếp tục câu hoặc tải lại trang.");
        // Completing a sentence has one natural outcome even if another tab uses a different key.
        String canonical = action.endsWith("/complete") ? "completed:" + action : null;
        var completed = canonical == null ? java.util.List.<String>of() : db.queryForList(
                "SELECT response_json FROM practice_submission_receipts WHERE session_id=? AND submission_key=?",
                String.class, session, canonical);
        T result = completed.isEmpty() ? write.apply(locked) : json.readValue(completed.get(0), type);
        String response = json.writeValueAsString(result);
        db.update("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
                session, key, action, hash, response);
        if (canonical != null && completed.isEmpty())
            db.update("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
                    session, canonical, action, hash, response);
        return result;
    }

}
