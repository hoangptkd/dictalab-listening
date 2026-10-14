package com.dictalab.service;

import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

/** Receipt and domain writes share one database transaction and the existing session lock. */
@Service
public class PracticeSubmissions {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public PracticeSubmissions(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(timeout = 20)
    public <T> T execute(long user, String session, String key, String action, Object payload,
                         Class<T> type, Supplier<T> write) {
        // Older clients remain compatible; they do not get transport retry protection.
        if (key == null) return write.get();
        if (!key.matches("[A-Za-z0-9_-]{8,80}"))
            throw new InvalidPracticeStateException("Mã gửi bài không hợp lệ.");
        if (db.queryForList("SELECT id FROM practice_sessions WHERE id=? AND user_id=? FOR UPDATE",
                String.class, session, user).isEmpty())
            throw new ResourceNotFoundException("Không tìm thấy phiên luyện.");
        String hash = AiBackgroundJobs.hash(action + "\n" + json.writeValueAsString(payload));
        var receipts = db.queryForList("SELECT action,request_hash,response_json FROM practice_submission_receipts WHERE session_id=? AND submission_key=?", session, key);
        if (!receipts.isEmpty()) {
            var receipt = receipts.get(0);
            if (!action.equals(receipt.get("action")) || !hash.equals(receipt.get("request_hash")))
                throw new InvalidPracticeStateException("Mã gửi bài đã được dùng với nội dung khác.");
            return json.readValue((String) receipt.get("response_json"), type);
        }
        // Completing a sentence has one natural outcome even if another tab uses a different key.
        String canonical = action.endsWith("/complete") ? "completed:" + action : null;
        var completed = canonical == null ? java.util.List.<String>of() : db.queryForList(
                "SELECT response_json FROM practice_submission_receipts WHERE session_id=? AND submission_key=?",
                String.class, session, canonical);
        T result = completed.isEmpty() ? write.get() : json.readValue(completed.get(0), type);
        String response = json.writeValueAsString(result);
        db.update("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
                session, key, action, hash, response);
        if (canonical != null && completed.isEmpty())
            db.update("INSERT INTO practice_submission_receipts(session_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
                    session, canonical, action, hash, response);
        return result;
    }
}
