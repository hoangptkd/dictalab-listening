package com.dictalab.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class AdminQueryService {
    private final JdbcTemplate db;
    public AdminQueryService(JdbcTemplate db) { this.db = db; }
    public record Page(List<Map<String, Object>> items, int page, long total, int size) {}

    @Transactional(readOnly = true)
    public Page lessons(String q, String level, String status, String topic, String origin, String sort, int page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1" + search(q, "l.title", "l.slug", args);
        where += choice(level, List.of("A1", "A2", "B1", "B2"), "l.level", args);
        if (!status.isEmpty()) {
            if (!List.of("PUBLISHED", "DRAFT").contains(status)) bad();
            where += " AND l.active=?"; args.add(status.equals("PUBLISHED"));
        }
        if (!topic.isEmpty()) { if (topic.length() > 60) bad(); where += " AND l.topic=?"; args.add(topic); }
        where += choice(origin, List.of("BUNDLED", "ADMIN_UPLOAD", "BRITISH_COUNCIL_PERSONAL"), "l.content_origin", args);
        String order = order(sort, Map.of("updated", "l.updated_at DESC,l.id DESC", "title", "l.title ASC,l.id ASC"));
        return page("SELECT l.id,l.slug,l.title,l.level,l.topic,l.active AS published,l.content_origin,l.updated_at,"
                + "(SELECT COUNT(*) FROM lesson_sentences s WHERE s.lesson_id=l.id) AS sentence_count,"
                + "(EXISTS(SELECT 1 FROM practice_sessions p WHERE p.lesson_id=l.id) OR "
                + "EXISTS(SELECT 1 FROM listening_units u WHERE u.lesson_id=l.id)) AS content_locked",
                " FROM lessons l", where, order, args, page);
    }

    public List<Map<String, Object>> lessonFilters() {
        return lower(db.queryForList("SELECT DISTINCT topic,content_origin FROM lessons ORDER BY topic,content_origin"));
    }
    @Transactional(readOnly = true)
    public Page users(String q, String role, Boolean enabled, Boolean verified, String sort, int page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1" + search(q, "u.email", "u.display_name", args);
        where += choice(role, List.of("USER", "ADMIN"), "u.role", args);
        if (enabled != null) { where += " AND u.enabled=?"; args.add(enabled); }
        if (verified != null) { where += " AND u.email_verified=?"; args.add(verified); }
        return page("SELECT u.id,u.email,u.display_name,u.role,u.enabled,u.email_verified,u.created_at,u.last_login_at",
                " FROM app_users u", where, order(sort, Map.of("created", "u.created_at DESC,u.id DESC", "login", "u.last_login_at DESC NULLS LAST,u.id DESC", "name", "u.display_name ASC,u.id ASC")), args, page);
    }

    @Transactional(readOnly = true)
    public Page units(String q, String level, String status, Long lessonId, String sort, int page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1" + search(q, "u.title", "CAST(u.id AS VARCHAR)", args);
        where += choice(level, List.of("A1", "A2", "B1", "B2"), "u.level", args);
        if (!status.isEmpty()) where += switch (status) {
            case "PUBLISHED" -> " AND u.published=TRUE";
            case "DRAFT" -> " AND u.published=FALSE AND u.retired=FALSE";
            case "RETIRED" -> " AND u.retired=TRUE";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        if (lessonId != null) { if (lessonId <= 0) bad(); where += " AND u.lesson_id=?"; args.add(lessonId); }
        return page("SELECT u.id,u.lesson_id,u.title,u.level,u.duration_seconds,u.published,u.retired,u.created_at,u.edit_version,l.title AS lesson_title",
                " FROM listening_units u JOIN lessons l ON l.id=u.lesson_id", where,
                order(sort, Map.of("updated", "u.id DESC", "title", "u.title ASC,u.id ASC")), args, page);
    }

    public Map<String, Object> unit(long id) {
        var rows = lower(db.queryForList("SELECT * FROM listening_units WHERE id=?", id));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài nghe hiểu.");
        var unit = rows.get(0);
        unit.put("transcript", lower(db.queryForList("SELECT position_index,transcript,audio_start_seconds,audio_end_seconds FROM lesson_sentences WHERE lesson_id=? AND audio_url=? ORDER BY position_index",
                unit.get("lesson_id"), unit.get("audio_url"))));
        return unit;
    }

    @Transactional(readOnly = true)
    public Page tickets(String q, String status, String category, String referenceType, String from, String to, String sort, int page) {
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1";
        if (!q.isEmpty()) {
            if (q.length() > 160) bad();
            where += " AND (LOWER(t.subject) LIKE ? ESCAPE '!' OR LOWER(u.email) LIKE ? ESCAPE '!' OR CAST(t.id AS VARCHAR)=?)";
            args.add(pattern(q)); args.add(pattern(q)); args.add(q.strip().replaceFirst("^#", ""));
        }
        where += choice(status, List.of("OPEN", "IN_PROGRESS", "RESOLVED"), "t.status", args);
        where += choice(category, List.of("AUDIO", "TRANSCRIPT", "SCORE", "ACCOUNT_DELETE", "OTHER"), "t.category", args);
        where += choice(referenceType, List.of("LISTENING", "DICTATION"), "t.reference_type", args);
        try {
            if (!from.isEmpty()) { where += " AND t.created_at>=?"; args.add(java.time.LocalDate.parse(from).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)); }
            if (!to.isEmpty()) { where += " AND t.created_at<?"; args.add(java.time.LocalDate.parse(to).plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)); }
            if (!from.isEmpty() && !to.isEmpty() && from.compareTo(to)>0) bad();
        } catch (java.time.DateTimeException e) { bad(); }
        return page("SELECT t.*,u.email", " FROM support_tickets t JOIN app_users u ON u.id=t.user_id", where,
                order(sort, Map.of("oldest", "t.created_at ASC,t.id ASC", "newest", "t.created_at DESC,t.id DESC")), args, page);
    }

    private Page page(String select, String tables, String where, String order, List<Object> args, int requested) {
        if (requested < 0 || requested > 100000) bad();
        long total = Objects.requireNonNull(db.queryForObject("SELECT COUNT(*)" + tables + where, Long.class, args.toArray()));
        int page = Math.min(requested, (int)Math.max(0, (total - 1) / 20));
        args.add(20); args.add(page * 20);
        return new Page(lower(db.queryForList(select + tables + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray())), page, total, 20);
    }
    private String search(String q, String first, String second, List<Object> args) {
        if (q.length() > 160) bad();
        if (q.isBlank()) return "";
        args.add(pattern(q)); args.add(pattern(q));
        return " AND (LOWER(" + first + ") LIKE ? ESCAPE '!' OR LOWER(" + second + ") LIKE ? ESCAPE '!')";
    }
    private static String pattern(String value) { return "%" + value.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private String choice(String value, List<String> allowed, String column, List<Object> args) {
        if (value.isEmpty()) return "";
        if (!allowed.contains(value)) bad();
        args.add(value); return " AND " + column + "=?";
    }
    private String order(String sort, Map<String, String> allowed) {
        String value = allowed.get(sort); if (value == null) bad(); return value;
    }
    private static void bad() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bộ lọc không hợp lệ."); }
    public static List<Map<String, Object>> lower(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> { Map<String, Object> result = new LinkedHashMap<>(); row.forEach((k,v) -> result.put(k.toLowerCase(Locale.ROOT),v)); return result; }).toList();
    }
    public void event(Long actor, String type, long id, String action, String detail) {
        db.update("INSERT INTO admin_events(actor_id,object_type,object_id,action,detail) VALUES (?,?,?,?,?)", actor,type,id,action,detail);
    }
    public void verifyLesson(long id, String expected) {
        var rows = db.queryForList("SELECT updated_at FROM lessons WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (expected == null) return; // Compatibility with existing integrations; admin UI always sends a revision.
        try {
            Object value = rows.get(0).get("updated_at");
            java.time.Instant actual = value instanceof java.time.OffsetDateTime time ? time.toInstant() : ((java.sql.Timestamp)value).toInstant();
            if (!actual.equals(java.time.Instant.parse(expected))) conflict();
        } catch (java.time.DateTimeException e) { bad(); }
    }
    public void verifyUnit(long id, Long expected) {
        var rows = db.queryForList("SELECT edit_version FROM listening_units WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (expected != null && ((Number)rows.get(0).get("edit_version")).longValue() != expected) conflict();
    }
    private void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "Nội dung đã thay đổi ở nơi khác. Hãy giữ phần đang nhập và tải lại bản mới trước khi lưu."); }
    public List<Map<String, Object>> events(String type, long id) {
        return lower(db.queryForList("SELECT e.*,u.email AS actor_email FROM admin_events e LEFT JOIN app_users u ON u.id=e.actor_id WHERE e.object_type=? AND e.object_id=? ORDER BY e.id DESC LIMIT 100",type,id));
    }
}
