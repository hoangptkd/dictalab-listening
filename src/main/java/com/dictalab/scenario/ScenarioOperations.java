package com.dictalab.scenario;

import com.dictalab.service.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.dictalab.scenario.ScenarioData.*;

/** Durable dispatch: stable job IDs survive ACK loss; no provider I/O in domain transactions. */
@Service
public class ScenarioOperations {
    private final ScenarioData data;
    private final AiPlatformClient ai;
    private final AiUsageBudget budget;
    private final ObjectProvider<ScenarioEngine> engine;

    public ScenarioOperations(ScenarioData data, AiPlatformClient ai, AiUsageBudget budget,
                              ObjectProvider<ScenarioEngine> engine) {
        this.data=data; this.ai=ai; this.budget=budget; this.engine=engine;
    }
    JsonNode prior(long user, String scope, String key, Object input) {
        key = key(key); String hash = ScenarioJson.hash(ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(input)));
        var rows = data.db.queryForList("SELECT id,input_hash FROM scenario_operations WHERE user_id=? AND scope=? AND request_key=?", user,scope,key);
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.get(0).get("input_hash"))) throw new ScenarioFailure(409,"IDEMPOTENCY_CONFLICT","Mã yêu cầu đã được dùng cho nội dung khác.");
        return get(user,(String)rows.get(0).get("id"));
    }
    JsonNode create(long user, String scope, String key, Object intent, String kind, String resource, JsonNode payload) {
        data.lockUser(user); var prior = prior(user,scope,key,intent); if (prior != null) return prior;
        if (data.db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE user_id=? AND status IN ('PENDING','RUNNING')", Integer.class,user) >= 3)
            throw new ScenarioFailure(429,"USER_QUOTA_EXCEEDED","Hãy chờ tác vụ đang chạy hoàn tất.");
        String id = id(), body = payload.toString(); require(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 60000);
        data.db.update("""
                INSERT INTO scenario_operations(id,user_id,request_key,scope,input_hash,kind,resource_id,payload_json,deadline)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,id,user,key(key),scope,ScenarioJson.hash(ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(intent))),kind,resource,body,
                Timestamp.from(Instant.now().plusSeconds(kind.equals("TTS") ? 90 : 240)));
        budget.reserveScenario(user,id,kind.equals("GENERATE") ? 3 : 1,kind.equals("TTS"),ai.local());
        return get(user,id);
    }
    public ObjectNode get(long user, String id) {
        var row = data.owned("scenario_operations",user,id);
        var result = object().put("contractVersion","scenario-public.v1").put("operationId",id).put("status",(String)row.get("status"))
                .put("stage",(String)row.get("kind")).put("version",number(row,"version")).put("pollAfterMs",2000);
        String resource=(String)row.get("resource_id"), kind=(String)row.get("kind");
        result.put(switch(kind) { case "EXTRACT" -> "draftId"; case "GENERATE" -> "sessionId"; case "GRADE" -> "attemptId"; default -> "mediaId"; },resource);
        if (row.get("error_code") != null) result.put("errorCode",(String)row.get("error_code"));
        return result;
    }
    @Scheduled(initialDelayString="${dictalab.scenario.engine.poll-delay-ms:2000}", fixedDelayString="${dictalab.scenario.engine.poll-delay-ms:2000}")
    public void dispatch() {
        // Rollout flags stop new admissions; already admitted stable jobs still settle after rollback.
        for (String id : data.db.queryForList("""
                SELECT id FROM scenario_operations WHERE status IN ('PENDING','RUNNING') AND poll_at<=CURRENT_TIMESTAMP
                AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY poll_at,id LIMIT 5
                """,String.class)) dispatch(id);
    }
    public void dispatch(String id) {
        String lease=id();
        if (data.db.update("""
                UPDATE scenario_operations SET lease_id=?,lease_until=?,status='RUNNING',version=version+1
                WHERE id=? AND status IN ('PENDING','RUNNING') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                """,lease,Timestamp.from(Instant.now().plusSeconds(60)),id) != 1) return;
        var rows=data.db.queryForList("SELECT * FROM scenario_operations WHERE id=? AND lease_id=?",id,lease);
        if (rows.isEmpty()) return; // Account deletion may cascade after the claim.
        var row=rows.get(0);
        long user=number(row,"user_id");
        if (instant(row.get("deadline")).isBefore(Instant.now())) { terminal(user,id,lease,"UNKNOWN","DEADLINE"); return; }
        try {
            JsonNode payload=tree((String)row.get("payload_json"));
            JsonNode state=Boolean.TRUE.equals(row.get("submitted")) ? ai.scenarioStatus(user,id) : ai.submitScenario(user,id,instant(row.get("deadline")),payload);
            ScenarioContent.check(id.equals(state.path("jobId").asText()) && "1".equals(state.path("schemaVersion").asText()));
            String status=state.path("status").asText();
            ScenarioContent.check(Set.of("PENDING","RUNNING","DONE","FAILED","UNKNOWN","EXPIRED").contains(status));
            if (status.equals("DONE")) {
                JsonNode result=state.path("result");
                ScenarioContent.check(result.isObject() && result.toString().length() <= (row.get("kind").equals("TTS") ? 4100000 : 262144));
                engine.getObject().accept(user,id,lease,row,result);
            } else if (Set.of("FAILED","UNKNOWN","EXPIRED").contains(status)) {
                terminal(user,id,lease,status.equals("EXPIRED") ? "FAILED" : status,
                        state.path("error").asText().equals("QUALITY_REJECTED") ? "GENERATION_REJECTED" : "AI_JOB_"+status);
            } else data.db.update("""
                    UPDATE scenario_operations SET submitted=TRUE,lease_id=NULL,lease_until=NULL,poll_at=?,version=version+1
                    WHERE id=? AND lease_id=? AND status='RUNNING'
                    """,Timestamp.from(Instant.now().plusSeconds(2)),id,lease);
        } catch (IllegalArgumentException invalid) { terminal(user,id,lease,"FAILED","INVALID_AI_RESULT"); }
        catch (RuntimeException unavailable) {
            // Retrying this stable job registration/poll cannot issue a fresh provider job.
            data.db.update("""
                    UPDATE scenario_operations SET lease_id=NULL,lease_until=NULL,poll_at=?,error_code='AI_SERVICE_UNAVAILABLE'
                    WHERE id=? AND lease_id=? AND status='RUNNING'
                    """,Timestamp.from(Instant.now().plusSeconds(10)),id,lease);
        }
    }
    private void terminal(long user,String id,String lease,String status,String code) {
        data.tx.executeWithoutResult(tx -> {
            var owners=data.db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",user); if (owners.isEmpty()) return;
            if (data.db.update("UPDATE scenario_operations SET status=?,error_code=?,lease_id=NULL,lease_until=NULL,version=version+1 WHERE id=? AND lease_id=? AND status='RUNNING'",status,code,id,lease)==1)
                engine.getObject().failed(data.owned("scenario_operations",user,id),status);
        });
    }
}
