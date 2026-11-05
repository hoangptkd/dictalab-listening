package com.dictalab.scenario;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class ScenarioData {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    public ScenarioData(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; tx = new TransactionTemplate(manager); tx.setTimeout(10);
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String json(Object value) { return ScenarioJson.MAPPER.writeValueAsString(value); }
    static JsonNode tree(String value) { return ScenarioJson.MAPPER.readTree(value); }
    static ObjectNode object() { return ScenarioJson.MAPPER.createObjectNode(); }
    static long number(Map<String,Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime at) return at.toInstant();
        return ((java.sql.Timestamp) value).toInstant();
    }
    void lockUser(long user) {
        if (db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", user).isEmpty()) throw missing();
    }
    Map<String,Object> owned(String table, long user, String id) {
        if (!java.util.Set.of("scenario_sessions","scenario_drafts","scenario_operations").contains(table)) throw new IllegalArgumentException("Invalid table");
        var rows = db.queryForList("SELECT * FROM " + table + " WHERE id=? AND user_id=?", id, user);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    Map<String,Object> activity(long user, String session, String id) {
        owned("scenario_sessions", user, session);
        var rows = db.queryForList("SELECT * FROM scenario_activities WHERE id=? AND session_id=?", id, session);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }
    static ScenarioFailure missing() { return new ScenarioFailure(404,"NOT_FOUND","Không tìm thấy dữ liệu."); }
    static ScenarioFailure invalid() { return new ScenarioFailure(400,"INVALID_REQUEST","Nội dung yêu cầu không hợp lệ."); }
    static void require(boolean valid) { if (!valid) throw invalid(); }
    static void version(Map<String,Object> row, long expected) {
        if (expected != number(row,"version")) throw new ScenarioFailure(409,"SESSION_VERSION_CONFLICT","Phiên đã thay đổi. Hãy tải lại và giữ câu trả lời đang soạn.");
    }
    static String key(String key) {
        require(key != null && key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"));
        return UUID.fromString(key).toString();
    }
    JsonNode receipt(long user, String scope, String key, Object input, java.util.function.Supplier<JsonNode> work) {
        key = key(key); lockUser(user);
        String hash = ScenarioJson.hash(ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(input)));
        var saved = db.queryForList("SELECT input_hash,response_json FROM scenario_receipts WHERE user_id=? AND scope=? AND request_key=?", user, scope, key);
        if (!saved.isEmpty()) {
            if (!hash.equals(saved.get(0).get("input_hash"))) throw new ScenarioFailure(409,"IDEMPOTENCY_CONFLICT","Mã thao tác đã được dùng cho nội dung khác.");
            return tree((String) saved.get(0).get("response_json"));
        }
        JsonNode response = work.get();
        db.update("INSERT INTO scenario_receipts(user_id,scope,request_key,input_hash,response_json) VALUES (?,?,?,?,?)",user,scope,key,hash,response.toString());
        return response;
    }
}
