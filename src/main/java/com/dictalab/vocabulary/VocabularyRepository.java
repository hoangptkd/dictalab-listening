package com.dictalab.vocabulary;

import com.dictalab.exception.InvalidPracticeStateException;
import com.dictalab.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class VocabularyRepository {
    final JdbcTemplate db;
    final ObjectMapper json;
    public VocabularyRepository(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    JdbcTemplate jdbc() { return db; }
    ObjectMapper mapper() { return json; }
    static String id() { return UUID.randomUUID().toString(); }
    static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.OffsetDateTime time) return time.toInstant();
        if (value instanceof Timestamp time) return time.toInstant();
        return Instant.parse(value.toString());
    }
    static long number(Map<String,Object> row,String name) { return ((Number)row.get(name)).longValue(); }
    static InvalidPracticeStateException conflict(String text) { return new InvalidPracticeStateException(text); }
    static org.springframework.web.server.ResponseStatusException invalid(String text) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,text);
    }
    Map<String,Object> one(String sql,Object... args) {
        var rows=rows(sql,args);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Không tìm thấy dữ liệu từ vựng.");
        return rows.get(0);
    }
    List<Map<String,Object>> rows(String sql,Object... args) {
        return db.query(sql,(rs,index) -> {
            Map<String,Object> result=new LinkedHashMap<>();
            var metadata=rs.getMetaData();
            for (int column=1;column<=metadata.getColumnCount();column++) {
                Object value=rs.getObject(column);
                if (value instanceof java.time.OffsetDateTime || value instanceof Timestamp) value=instant(value);
                result.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT),value);
            }
            return result;
        },args);
    }
    void lockUser(long user) { one("SELECT id FROM app_users WHERE id=? FOR UPDATE",user); }
    Map<String,Object> deck(Long owner,String id) {
        return one("SELECT * FROM vocab_decks WHERE id=? AND " + (owner==null ? "owner_user_id IS NULL" : "owner_user_id=?")
                + " AND status<>'ARCHIVED'",owner==null ? new Object[]{id} : new Object[]{id,owner});
    }
    Map<String,Object> note(Long owner,String id) {
        return one("SELECT * FROM vocab_notes WHERE id=? AND " + (owner==null ? "owner_user_id IS NULL" : "owner_user_id=?")
                + " AND archived=FALSE",owner==null ? new Object[]{id} : new Object[]{id,owner});
    }
    String encode(Object value) { return json.writeValueAsString(value); }
    @SuppressWarnings("unchecked") Map<String,Object> object(String value) { return json.readValue(value,Map.class); }
    @SuppressWarnings("unchecked") List<String> strings(String value) { return json.readValue(value,List.class); }
    <T> T receipt(long user,String key,String action,Object body,Class<T> type,Supplier<T> work) {
        if (key==null || !key.matches("[A-Za-z0-9_.:-]{1,100}")) throw invalid("Thiếu mã thao tác hợp lệ.");
        lockUser(user); // One lock order for all vocabulary writes, including concurrent retries.
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(body).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var prior=db.queryForList("SELECT * FROM vocab_receipts WHERE user_id=? AND submission_key=?",user,key);
        if (!prior.isEmpty()) {
            var saved=prior.get(0);
            if (!action.equals(saved.get("action")) || !hash.equals(saved.get("request_hash")))
                throw conflict("Mã thao tác đã được dùng cho nội dung khác.");
            return json.readValue((String)saved.get("response_json"),type);
        }
        T result=work.get();
        db.update("INSERT INTO vocab_receipts(user_id,submission_key,action,request_hash,response_json) VALUES (?,?,?,?,?)",
                user,key,action,hash,encode(result));
        return result;
    }
    ReviewScheduler.State schedule(Map<String,Object> card) {
        return new ReviewScheduler.State((String)card.get("state"),(String)card.get("scheduler_version"),
                ((Number)card.get("interval_days")).intValue(),((Number)card.get("ease_factor")).doubleValue(),
                ((Number)card.get("successful_repetitions")).intValue(),((Number)card.get("lapse_count")).intValue(),instant(card.get("due_at")));
    }
}
