package com.dictalab.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Core keeps a durable submit/poll ledger and never waits for CPU inference on an HTTP request. */
@Service
public class ModelDemoService {
    public static final String PROMPT_SHA = "62d4e1c7e03c26ebd9a5dca7d186bb27c22fd62be6078f09a72c2643402cf5cb";
    public static final String BASE_REVISION = "851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a";
    public static final String ADAPTER_SHA = "1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AiPlatformClient ai;
    private final ConversationService conversation;
    private final AiUsageBudget budget;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public ModelDemoService(JdbcTemplate db, ObjectMapper json, AiPlatformClient ai, ConversationService conversation,
            AiUsageBudget budget, PlatformTransactionManager manager,
            @Value("${dictalab.model-demo.enabled:false}") boolean enabled) {
        this.db=db; this.json=json; this.ai=ai; this.conversation=conversation; this.budget=budget; this.enabled=enabled;
        this.tx=new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    public Map<String,Object> catalog(long user) {
        return Map.of("enabled", enabled, "scenarios", ConversationService.SCENARIOS,
                "levels", List.of("A1","A2","B1"), "jobs", enabled ? history(user) : List.of());
    }

    public ObjectNode submit(long user, UUID key, UUID session, String scenario, String level,
            String mode, String variant, boolean fallback) {
        requireEnabled();
        if (!Set.of("practice","compare").contains(mode) || !Set.of("base","full07","cloud").contains(variant)
                || mode.equals("compare") && (fallback || !variant.equals("full07")) || mode.equals("practice") && session==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cấu hình demo không hợp lệ.");
        // Hash the browser's intent separately: a retry after session progress returns the original job.
        String intent = AiBackgroundJobs.hash(json.writeValueAsString(Arrays.asList(session,scenario,level,mode,variant,fallback)));
        var prior = db.queryForList("SELECT id,input_hash FROM model_demo_jobs WHERE user_id=? AND request_key=?", user,key.toString());
        if (!prior.isEmpty()) return existing(user, prior.get(0), intent);
        JsonNode context = conversation.modelDemoContext(user, session==null ? null : session.toString(), scenario, level);
        var payload = json.createObjectNode().put("kind","conversation_demo").put("level",context.path("difficulty").asText())
                .put("mode",mode).put("variant",variant).put("allowFallback",fallback);
        payload.set("context",context);
        if (payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngữ cảnh demo quá dài.");
        String id=UUID.randomUUID().toString();
        String registered=tx.execute(status -> {
            db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",Long.class,user);
            var duplicates=db.queryForList("SELECT id,input_hash FROM model_demo_jobs WHERE user_id=? AND request_key=?", user,key.toString());
            if (!duplicates.isEmpty()) {
                existing(user,duplicates.get(0),intent);
                return (String)duplicates.get(0).get("id");
            }
            if (db.queryForObject("SELECT COUNT(*) FROM model_demo_jobs WHERE user_id=? AND status IN ('PENDING','RUNNING')",Integer.class,user)>=2)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Hãy chờ job demo hiện tại hoàn tất.");
            budget.reserveDemo(user,!variant.equals("cloud"));
            db.update("""
                    INSERT INTO model_demo_jobs(id,user_id,request_key,session_id,mode,payload,input_hash,deadline)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,id,user,key.toString(),session==null ? null : session.toString(),mode,payload.toString(),intent,
                    Timestamp.from(Instant.now().plusSeconds(3500)));
            return id;
        });
        return get(user,registered);
    }

    private ObjectNode existing(long user, Map<String,Object> row, String hash) {
        if (!hash.equals(row.get("input_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Mã yêu cầu đã được dùng với nội dung khác.");
        return get(user,(String)row.get("id"));
    }

    public ObjectNode get(long user, String id) {
        requireEnabled();
        return publicView(owned(user,id));
    }

    public List<ObjectNode> history(long user) {
        return db.query("SELECT * FROM model_demo_jobs WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20",
                (r,n) -> publicView(row(r)),user);
    }

    private Map<String,Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        var value = new HashMap<String,Object>();
        for (String name:List.of("id","session_id","mode","payload","status","result_json","error_code")) value.put(name,rs.getString(name));
        value.put("created_at",rs.getTimestamp("created_at"));
        return value;
    }

    private Map<String,Object> owned(long user, String id) {
        var rows=db.queryForList("SELECT * FROM model_demo_jobs WHERE id=? AND user_id=?",id,user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy job demo.");
        return rows.get(0);
    }

    private ObjectNode publicView(Map<String,Object> row) {
        var view=json.createObjectNode().put("id",(String)row.get("id")).put("mode",(String)row.get("mode"))
                .put("status",(String)row.get("status")).put("sessionId",(String)row.get("session_id"))
                .put("createdAt",instant(row.get("created_at")).toString()).put("error",(String)row.get("error_code"));
        if (row.get("result_json")!=null) {
            ObjectNode result=(ObjectNode)json.readTree((String)row.get("result_json"));
            if (row.get("mode").equals("practice")) {
                result.remove("task");
                for (var arm:result.path("arms")) ((ObjectNode)arm).remove(List.of("task","candidate","raw"));
            }
            view.set("result",result);
        }
        return view;
    }

    public ObjectNode accept(long user, String id) {
        requireEnabled();
        var row=owned(user,id);
        if (!row.get("mode").equals("practice") || !row.get("status").equals("DONE") || row.get("session_id")==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Job này không có bài luyện để áp dụng.");
        JsonNode result=json.readTree((String)row.get("result_json"));
        JsonNode selected=null;
        for (var arm:result.path("arms")) if (arm.path("valid").asBoolean()) selected=arm;
        if (selected==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Model chưa tạo được bài hợp lệ.");
        return conversation.acceptModelDemo(user,(String)row.get("session_id"),id,
                json.readTree((String)row.get("payload")).path("context"),selected);
    }

    @Scheduled(initialDelayString="${dictalab.model-demo.poll-delay-ms:2000}",fixedDelayString="${dictalab.model-demo.poll-delay-ms:2000}")
    public void dispatch() {
        if (!enabled) return;
        db.update("""
                UPDATE model_demo_jobs SET status='EXPIRED',error_code='DEADLINE',payload='{}'
                WHERE status IN ('PENDING','RUNNING') AND deadline<CURRENT_TIMESTAMP
                """);
        for (String id:db.queryForList("""
                SELECT id FROM model_demo_jobs WHERE status IN ('PENDING','RUNNING') AND poll_at<=CURRENT_TIMESTAMP
                    AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY poll_at,id LIMIT 5
                """,String.class)) {
            String lease=UUID.randomUUID().toString();
            if (db.update("""
                    UPDATE model_demo_jobs SET lease_id=?,lease_until=? WHERE id=? AND status IN ('PENDING','RUNNING')
                    AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                    """,lease,Timestamp.from(Instant.now().plusSeconds(30)),id)!=1) continue;
            var rows=db.queryForList("SELECT * FROM model_demo_jobs WHERE id=? AND lease_id=?",id,lease);
            if (rows.isEmpty()) continue;
            var row=rows.get(0);
            try {
                JsonNode payload=json.readTree((String)row.get("payload"));
                long user=((Number)row.get("user_id")).longValue();
                JsonNode state=Boolean.TRUE.equals(row.get("submitted")) ? ai.demoStatus(user,id)
                        : ai.submitDemo(user,id,instant(row.get("deadline")),payload);
                if (!id.equals(state.path("jobId").asText()) || !"1".equals(state.path("schemaVersion").asText())
                        || !Set.of("PENDING","RUNNING","DONE","FAILED","UNKNOWN","EXPIRED").contains(state.path("status").asText()))
                    throw new IllegalArgumentException("Invalid demo job response");
                JsonNode result=state.path("result");
                if (state.path("status").asText().equals("DONE")) validateResult(payload,result);
                db.update("""
                        UPDATE model_demo_jobs SET status=?,submitted=TRUE,result_json=?,error_code=?,lease_id=NULL,
                            lease_until=NULL,poll_at=? WHERE id=? AND lease_id=?
                        """,state.path("status").asText(),result.isObject()?result.toString():null,
                        Set.of("FAILED","UNKNOWN","EXPIRED").contains(state.path("status").asText())?"AI_JOB_FAILED":null,
                        Timestamp.from(Instant.now().plusSeconds(3)),id,lease);
            } catch (IllegalArgumentException invalid) {
                db.update("UPDATE model_demo_jobs SET status='FAILED',error_code='INVALID_AI_RESULT',lease_id=NULL,lease_until=NULL WHERE id=? AND lease_id=?",id,lease);
            } catch (RuntimeException unavailable) {
                db.update("""
                        UPDATE model_demo_jobs SET error_code='AI_SERVICE_UNAVAILABLE',lease_id=NULL,lease_until=NULL,poll_at=?
                        WHERE id=? AND lease_id=?
                        """,Timestamp.from(Instant.now().plusSeconds(10)),id,lease);
            }
        }
    }

    static void validateResult(JsonNode payload, JsonNode result) {
        if (!result.isObject() || !result.path("evaluationOnly").asBoolean() || !PROMPT_SHA.equals(result.path("promptHash").asText())
                || !payload.path("mode").asText().equals(result.path("mode").asText())
                || !BASE_REVISION.equals(result.path("baseRevision").asText()) || !ADAPTER_SHA.equals(result.path("adapterSha256").asText())
                || !result.path("arms").isArray() || result.path("arms").isEmpty() || result.path("arms").size()>2)
            throw new IllegalArgumentException("Invalid demo result provenance");
        boolean compare=payload.path("mode").asText().equals("compare");
        if (compare && (result.path("arms").size()!=2 || !result.path("arms").path(0).path("variant").asText().equals("base")
                || !result.path("arms").path(1).path("variant").asText().equals("full07")))
            throw new IllegalArgumentException("Invalid comparison arms");
        if (!compare && (!result.path("arms").path(0).path("variant").asText().equals(payload.path("variant").asText())
                || result.path("arms").size()==2 && (!payload.path("allowFallback").asBoolean()
                    || result.path("arms").path(0).path("valid").asBoolean()
                    || !result.path("arms").path(1).path("variant").asText().equals("cloud")
                    || !result.path("arms").path(1).path("fallbackFrom").asText().equals(payload.path("variant").asText()))))
            throw new IllegalArgumentException("Invalid fallback sequence");
        for (var arm:result.path("arms")) {
            String variant=arm.path("variant").asText();
            if (!Set.of("base","full07","cloud").contains(variant) || compare && variant.equals("cloud")
                    || !compare && !variant.equals(payload.path("variant").asText())
                        && !(variant.equals("cloud") && payload.path("allowFallback").asBoolean()))
                throw new IllegalArgumentException("Unrequested model variant");
            if (arm.path("valid").asBoolean()) {
                if (!arm.path("task").isObject() || !arm.path("calls").isArray() || arm.path("calls").size()!=2
                        || !arm.path("calls").path(0).path("purpose").asText().equals("content")
                        || !arm.path("calls").path(1).path("purpose").asText().equals("translation"))
                    throw new IllegalArgumentException("Missing demo task");
                for (var call:arm.path("calls")) {
                    if (!call.path("model").isTextual() || call.path("model").asText().isBlank() || call.path("model").asText().length()>128)
                        throw new IllegalArgumentException("Missing model source");
                    if (variant.equals("cloud")) {
                        if (!Set.of("gemini","deepseek","openai_compatible").contains(call.path("provider").asText()))
                            throw new IllegalArgumentException("Invalid cloud source");
                    } else if (!call.path("provider").asText().equals("local_cpu") || !call.path("model").asText().equals("Qwen/Qwen3.5-4B")
                            || !variant.equals(call.path("variant").asText())
                            || !BASE_REVISION.equals(call.path("baseRevision").asText())
                            || variant.equals("full07") && !ADAPTER_SHA.equals(call.path("adapterSha256").asText()))
                        throw new IllegalArgumentException("Invalid local source");
                }
            }
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Demo model chưa được bật.");
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.OffsetDateTime)value).toInstant();
    }
}
