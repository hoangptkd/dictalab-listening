package com.dictalab.scenario;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ScenarioGoalService {
    private final JdbcTemplate db;
    private final ScenarioProgramRegistry registry;
    private final ScenarioProperties properties;

    public ScenarioGoalService(JdbcTemplate db, ScenarioProgramRegistry registry, ScenarioProperties properties) {
        this.db = db; this.registry = registry; this.properties = properties;
    }

    public record Goal(String id, String programCode, int programVersion, ScenarioGoalRequest.Target target,
                       String provenance, long version) {}

    @Transactional(readOnly = true)
    public List<Goal> list(long userId) {
        return db.query("""
                SELECT * FROM scenario_learning_goals WHERE user_id=? ORDER BY program_code,component
                """, (rs, index) -> new Goal(rs.getString("id"), rs.getString("program_code"), rs.getInt("program_version"),
                new ScenarioGoalRequest.Target(rs.getString("scale_id"), rs.getString("component"), rs.getString("target_value")),
                rs.getString("provenance"), rs.getLong("version")), userId);
    }

    @Transactional
    public Goal save(long userId, String key, ScenarioGoalRequest request) {
        if (!properties.enabled()) throw new ScenarioFailure(503, "SCENARIO_DISABLED", "Tính năng tình huống mới chưa được bật.");
        if (key == null || !key.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new ScenarioFailure(400, "INVALID_REQUEST", "Cần Idempotency-Key dạng UUID.");
        key = UUID.fromString(key).toString();
        ScenarioProgram definition;
        try {
            definition = registry.require(request.programCode(), request.programVersion());
            definition.validateTarget(request.target());
        } catch (IllegalArgumentException invalid) {
            throw new ScenarioFailure(400, "INVALID_TARGET", "Mục tiêu không thuộc thang điểm của chương trình.");
        }
        String fingerprint = ScenarioJson.hash(ScenarioJson.canonical(ScenarioJson.MAPPER.valueToTree(request)));
        // Short local transaction, same lock order for create/edit/retry and account deletion. No AI or billing I/O.
        var owner = db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE", userId);
        if (owner.isEmpty()) throw new ScenarioFailure(404, "NOT_FOUND", "Không tìm thấy tài khoản.");
        var prior = db.queryForList("SELECT input_hash,response_json FROM scenario_goal_requests WHERE user_id=? AND idempotency_key=?", userId, key);
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.get(0).get("input_hash")))
                throw new ScenarioFailure(409, "IDEMPOTENCY_CONFLICT", "Mã thao tác đã được dùng cho nội dung khác.");
            return ScenarioJson.MAPPER.readValue((String) prior.get(0).get("response_json"), Goal.class);
        }
        if (definition.status() == ScenarioProgram.Status.RETIRED)
            throw new ScenarioFailure(400, "UNSUPPORTED_PROGRAM_MODE", "Phiên bản chương trình đã ngừng nhận mục tiêu mới.");
        var current = list(userId).stream().filter(g -> g.programCode().equals(request.programCode())
                && g.target().component().equals(request.target().component())).findFirst();
        long version = current.map(Goal::version).orElse(0L);
        if (version != request.expectedVersion())
            throw new ScenarioFailure(409, "GOAL_VERSION_CONFLICT", "Mục tiêu đã thay đổi. Hãy tải lại trước khi sửa.");
        var result = new Goal(current.map(Goal::id).orElseGet(() -> UUID.randomUUID().toString()), request.programCode(),
                request.programVersion(), request.target(), "USER_DECLARED_GOAL", version + 1);
        if (current.isEmpty()) {
            db.update("""
                    INSERT INTO scenario_learning_goals(id,user_id,program_code,program_version,component,scale_id,target_value,provenance,version)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, result.id(), userId, result.programCode(), result.programVersion(), result.target().component(),
                    result.target().scaleId(), result.target().value(), result.provenance(), result.version());
        } else {
            db.update("""
                    UPDATE scenario_learning_goals SET program_version=?,scale_id=?,target_value=?,version=?,updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND user_id=?
                    """, result.programVersion(), result.target().scaleId(), result.target().value(), result.version(), result.id(), userId);
        }
        db.update("INSERT INTO scenario_goal_requests(user_id,idempotency_key,input_hash,response_json) VALUES (?,?,?,?)",
                userId, key, fingerprint, ScenarioJson.MAPPER.writeValueAsString(result));
        return result;
    }
}
