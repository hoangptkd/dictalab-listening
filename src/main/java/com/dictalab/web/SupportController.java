package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ListeningComprehensionService;
import com.dictalab.service.AdminQueryService;
import com.dictalab.room.RoomRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@RestController
public class SupportController {
    private final JdbcTemplate db;
    private final ListeningComprehensionService listening;
    private final ObjectMapper json;
    private final RoomRegistry rooms;
    private final AdminQueryService queries;
    public SupportController(JdbcTemplate db, ListeningComprehensionService listening, ObjectMapper json,
                             RoomRegistry rooms, AdminQueryService queries) {
        this.db = db;
        this.listening = listening;
        this.json = json;
        this.rooms = rooms;
        this.queries=queries;
    }

    public record Ticket(@NotBlank @Pattern(regexp = "AUDIO|TRANSCRIPT|SCORE|ACCOUNT_DELETE|OTHER") String category,
                         @NotBlank @Size(max = 160) String subject,
                         @NotBlank @Size(max = 3000) String description,
                         @Pattern(regexp = "LISTENING|DICTATION") String referenceType,
                         @Size(max = 64) String referenceId) {}
    public record Resolution(@NotBlank @Pattern(regexp = "OPEN|IN_PROGRESS|RESOLVED") String status,
                             @Size(max = 1000) String adminNote, Long version) {}
    public record TicketPage(List<Map<String, Object>> tickets, int page, boolean hasMore) {}
    @GetMapping("/api/admin/support/tickets/page")
    public AdminQueryService.Page page(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="OPEN") String status,
            @RequestParam(defaultValue="") String category, @RequestParam(defaultValue="") String referenceType,
            @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
            @RequestParam(defaultValue="oldest") String sort, @RequestParam(defaultValue="0") int page) {
        return queries.tickets(q,status,category,referenceType,from,to,sort,page);
    }
    @GetMapping("/api/admin/support/tickets/{id}")
    public Map<String,Object> detail(@PathVariable long id) {
        var rows=AdminQueryService.lower(db.queryForList("SELECT t.*,u.email FROM support_tickets t JOIN app_users u ON u.id=t.user_id WHERE t.id=?",id));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var result=rows.get(0);
        result.put("events",queries.events("TICKET",id));
        Object reference=result.get("reference_id");
        if (reference != null) {
            var links="LISTENING".equals(result.get("reference_type"))
                    ? db.queryForList("SELECT unit_id AS content_id FROM listening_sessions WHERE id=? AND user_id=?",reference,result.get("user_id"))
                    : db.queryForList("SELECT lesson_id AS content_id FROM practice_sessions WHERE id=? AND user_id=?",reference,result.get("user_id"));
            if(!links.isEmpty()) result.put("content_id",links.get(0).get("content_id"));
        }
        return result;
    }

    @PostMapping("/api/support/tickets")
    public Map<String, Object> create(@AuthenticationPrincipal DictaLabPrincipal user,
                                      @Valid @RequestBody Ticket ticket) {
        if ((ticket.referenceType() == null) != (ticket.referenceId() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin bài học không đầy đủ.");
        if (ticket.referenceType() != null && ticket.referenceId() != null) {
            String table = switch (ticket.referenceType()) {
                case "LISTENING" -> "listening_sessions";
                case "DICTATION" -> "practice_sessions";
                default -> null;
            };
            if (table != null) {
                if (!ticket.referenceId().matches("[0-9a-fA-F-]{36}"))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã bài học không hợp lệ.");
                Integer owned = db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=? AND user_id=?",
                        Integer.class, ticket.referenceId(), user.id());
                if (owned == null || owned == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        Integer recent = db.queryForObject("SELECT COUNT(*) FROM support_tickets WHERE user_id=? AND created_at>?",
                Integer.class, user.id(), Timestamp.from(Instant.now().minusSeconds(3600)));
        if (recent != null && recent >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng thử lại sau.");
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("user_id", user.id());
        values.put("category", ticket.category());
        values.put("subject", ticket.subject().strip());
        values.put("description", ticket.description().strip());
        values.put("reference_type", ticket.referenceType());
        values.put("reference_id", ticket.referenceId());
        long id = new SimpleJdbcInsert(db).withTableName("support_tickets")
                .usingColumns("user_id", "category", "subject", "description", "reference_type", "reference_id")
                .usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
        return Map.of("id", id, "status", "OPEN");
    }

    @GetMapping("/api/support/tickets")
    public TicketPage mine(@AuthenticationPrincipal DictaLabPrincipal user,
                           @RequestParam(defaultValue = "0") int page) {
        checkPage(page);
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id,category,subject,description,reference_type,reference_id,status,admin_note,created_at,updated_at
                FROM support_tickets WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?
                """, user.id(), page * 20);
        return ticketPage(rows, page);
    }

    @GetMapping("/api/admin/support/tickets")
    public TicketPage all(@RequestParam(defaultValue = "OPEN") String status,
                          @RequestParam(defaultValue = "0") int page) {
        if (!List.of("OPEN", "IN_PROGRESS", "RESOLVED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        checkPage(page);
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT t.id,t.user_id,u.email,t.category,t.subject,t.description,t.reference_type,
                    t.reference_id,t.status,t.admin_note,t.created_at,t.updated_at
                FROM support_tickets t JOIN app_users u ON u.id=t.user_id
                WHERE t.status=? ORDER BY t.created_at ASC,t.id ASC LIMIT 21 OFFSET ?
                """, status, page * 20);
        return ticketPage(rows, page);
    }

    private static void checkPage(int page) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static TicketPage ticketPage(List<Map<String, Object>> rows, int page) {
        return new TicketPage(rows.subList(0, Math.min(rows.size(), 20)), page, rows.size() > 20);
    }

    @PutMapping("/api/admin/support/tickets/{id}")
    @Transactional
    public void resolve(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                        @Valid @RequestBody Resolution resolution) {
        var old=AdminQueryService.lower(db.queryForList("SELECT * FROM support_tickets WHERE id=? FOR UPDATE",id));
        if(old.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(resolution.version()!=null && ((Number)old.get(0).get("edit_version")).longValue()!=resolution.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Yêu cầu đã được sửa ở nơi khác. Giữ phản hồi đang nhập và tải lại bản mới.");
        if (queries.events("TICKET",id).isEmpty() && old.get(0).get("admin_note") instanceof String note && !note.isBlank())
            queries.event(null,"TICKET",id,"PREVIOUS_RESPONSE",note);
        db.update("UPDATE support_tickets SET status=?,admin_note=?,updated_at=?,edit_version=edit_version+1 WHERE id=?",
                resolution.status(), resolution.adminNote(), Timestamp.from(Instant.now()), id);
        queries.event(admin.id(),"TICKET",id,resolution.status(),resolution.adminNote());
        db.update("INSERT INTO account_audit(actor_id,action,detail) VALUES (?,?,?)",
                admin.id(), "SUPPORT_TICKET_UPDATE", "Ticket #" + id + ": " + resolution.status());
    }

    @GetMapping("/api/admin/support/tickets/{id}/grading")
    public Map<String, Object> grading(@PathVariable long id) {
        String sessionId = gradingSession(id);
        Map<String, Object> unit = db.queryForMap("""
                SELECT u.content_json FROM listening_sessions s JOIN listening_units u ON u.id=s.unit_id
                WHERE s.id=?
                """, sessionId);
        JsonNode facts = json.readTree((String) unit.get("content_json")).path("facts");
        List<Map<String, Object>> passes = db.queryForList("""
                SELECT pass_number,recall_text,recall_result_json,recall_score,grading_status
                FROM listening_passes WHERE session_id=? AND recall_submitted_at IS NOT NULL ORDER BY pass_number
                """, sessionId);
        return Map.of("sessionId", sessionId, "facts", facts, "passes", passes);
    }

    public record ManualGrade(@NotBlank String result) {}

    @Transactional
    @PutMapping("/api/admin/support/tickets/{id}/grading/{pass}")
    public void manualGrade(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                            @PathVariable int pass, @Valid @RequestBody ManualGrade body) {
        String sessionId = gradingSession(id);
        JsonNode result;
        try { result = json.readTree(body.result()); }
        catch (RuntimeException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kết quả chấm không hợp lệ."); }
        listening.manualGrade(sessionId, pass, result);
        db.update("INSERT INTO account_audit(actor_id,action,detail) VALUES (?,?,?)",
                admin.id(), "MANUAL_RECALL_GRADE", "Ticket #" + id + ", pass " + pass);
    }

    private String gradingSession(long ticketId) {
        List<String> ids = db.queryForList("""
                SELECT t.reference_id FROM support_tickets t JOIN listening_sessions s
                    ON s.id=t.reference_id AND s.user_id=t.user_id
                WHERE t.id=? AND t.category='SCORE' AND t.reference_type='LISTENING'
                """, String.class, ticketId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ids.get(0);
    }

    public record DeleteAccount(@NotBlank String email) {}

    @Transactional
    @PostMapping("/api/admin/support/tickets/{id}/delete-account")
    public void deleteAccount(@AuthenticationPrincipal DictaLabPrincipal admin, @PathVariable long id,
                              @Valid @RequestBody DeleteAccount body) {
        List<Map<String, Object>> tickets = db.queryForList("""
                SELECT t.user_id,u.email,u.role FROM support_tickets t JOIN app_users u ON u.id=t.user_id
                WHERE t.id=? AND t.category='ACCOUNT_DELETE' FOR UPDATE
                """, id);
        if (tickets.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> ticket = tickets.get(0);
        long userId = ((Number) ticket.get("user_id")).longValue();
        if (userId == admin.id() || "ADMIN".equals(ticket.get("role")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không xóa tài khoản quản trị ở đây.");
        if (!body.email().equalsIgnoreCase((String) ticket.get("email")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email xác nhận không khớp.");
        if (rooms.findRoomForUser(userId).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Người dùng cần rời phòng gọi trước khi xóa.");
        db.update("DELETE FROM listening_sessions WHERE user_id=?", userId);
        db.update("DELETE FROM practice_sessions WHERE user_id=?", userId);
        db.update("DELETE FROM audio_exposure_events WHERE user_id=?", userId);
        db.update("DELETE FROM account_tokens WHERE user_id=?", userId);
        db.update("DELETE FROM admin_events WHERE object_type='TICKET' AND object_id IN (SELECT id FROM support_tickets WHERE user_id=?)", userId);
        db.update("DELETE FROM support_tickets WHERE user_id=?", userId);
        db.update("UPDATE account_audit SET actor_id=NULL WHERE actor_id=?", userId);
        db.update("DELETE FROM account_audit WHERE subject_id=?", userId);
        db.update("DELETE FROM app_users WHERE id=?", userId);
        db.update("INSERT INTO account_audit(actor_id,action,detail) VALUES (?,?,?)",
                admin.id(), "DELETE_ACCOUNT", "Đã xử lý yêu cầu #" + id);
    }
}
