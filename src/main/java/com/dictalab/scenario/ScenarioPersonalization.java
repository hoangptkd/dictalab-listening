package com.dictalab.scenario;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static com.dictalab.scenario.ScenarioData.*;

/** Resolves an explicit practice choice; never estimates proficiency or converts exam scales. */
@Service
public class ScenarioPersonalization {
    static final Set<String> SKILLS=Set.of("detail","main-idea","reason","purpose","speaker-attitude","supported-inference");
    private final ScenarioData data;
    private final ScenarioProgramRegistry registry;
    private final ScenarioGoalService goals;
    private final ScenarioProperties properties;
    public ScenarioPersonalization(ScenarioData data,ScenarioProgramRegistry registry,ScenarioGoalService goals,ScenarioProperties properties) {
        this.data=data; this.registry=registry; this.goals=goals; this.properties=properties;
    }
    public record Preference(String programCode,int programVersion,ScenarioGoalRequest.Target target,
                             String currentValue,String focusSkill,long expectedVersion) {}

    public JsonNode list(long user) {
        var result=object(); var items=result.putArray("preferences");
        for (var row : data.db.queryForList("SELECT preference_json,version FROM scenario_learning_preferences WHERE user_id=? ORDER BY updated_at DESC,program_code",user)) {
            var item=(ObjectNode)tree((String)row.get("preference_json")); item.put("version",number(row,"version")); items.add(item);
        }
        return result;
    }
    public JsonNode save(long user,String key,Preference request) {
        return data.tx.execute(tx -> data.receipt(user,"learning-preference",key,request,() -> {
            if (!properties.enabled()) throw new ScenarioFailure(503,"SCENARIO_DISABLED","Chưa bật cá nhân hóa bài luyện.");
            require(request!=null && request.expectedVersion()>=0 && request.expectedVersion()<=9007199254740991L);
            ScenarioProgram program;
            try { program=registry.require(request.programCode(),request.programVersion()); }
            catch (IllegalArgumentException invalid) { throw ScenarioData.invalid(); }
            require(program.version()>=2 && program.status()!=ScenarioProgram.Status.RETIRED);
            var context=context(program,request.target(),request.currentValue(),request.focusSkill());
            var rows=data.db.queryForList("SELECT version FROM scenario_learning_preferences WHERE user_id=? AND program_code=?",user,program.code());
            long version=rows.isEmpty() ? 0 : number(rows.get(0),"version");
            if (version!=request.expectedVersion()) throw new ScenarioFailure(409,"PREFERENCE_VERSION_CONFLICT","Lựa chọn đã thay đổi. Hãy tải lại trước khi lưu.");
            var prior=goals.list(user).stream().filter(g -> g.programCode().equals(program.code()) && g.target().component().equals(request.target().component())).findFirst();
            goals.save(user,key,new ScenarioGoalRequest("scenario-goal.v1",program.code(),program.version(),request.target(),prior.map(ScenarioGoalService.Goal::version).orElse(0L)));
            var stored=ScenarioJson.MAPPER.valueToTree(request); ((ObjectNode)stored).remove("expectedVersion");
            if (rows.isEmpty()) data.db.update("INSERT INTO scenario_learning_preferences(user_id,program_code,program_version,preference_json,version) VALUES (?,?,?,?,?)",user,program.code(),program.version(),stored.toString(),version+1);
            else data.db.update("UPDATE scenario_learning_preferences SET program_version=?,preference_json=?,version=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND program_code=?",program.version(),stored.toString(),version+1,user,program.code());
            ((ObjectNode)stored).put("version",version+1); ((ObjectNode)stored).set("learningContext",context); return stored;
        }));
    }
    public ObjectNode forDraft(long user,ScenarioProgram program) {
        return forDraft(user,program,null);
    }
    public ObjectNode forDraft(long user,ScenarioProgram program,String focus) {
        var rows=data.db.queryForList("SELECT preference_json FROM scenario_learning_preferences WHERE user_id=? AND program_code=? AND program_version=?",user,program.code(),program.version());
        if (rows.isEmpty()) throw new ScenarioFailure(400,"PERSONALIZATION_REQUIRED","Hãy chọn mục tiêu luyện nghe trước khi tạo bài.");
        var preference=tree((String)rows.get(0).get("preference_json"));
        // Only an explicit recommendation overrides the default, never a removed legacy preference field.
        return context(program,ScenarioJson.MAPPER.treeToValue(preference.path("target"),ScenarioGoalRequest.Target.class),null,focus==null || focus.isEmpty() ? "main-idea" : focus);
    }
    static ObjectNode context(ScenarioProgram program,ScenarioGoalRequest.Target target,String currentValue,String focus) {
        try {
            if (currentValue!=null && currentValue.isEmpty()) currentValue=null;
            program.validateTarget(target);
            require("LISTENING".equals(target.component()) && SKILLS.contains(focus));
            if (currentValue!=null) program.validateTarget(new ScenarioGoalRequest.Target(target.scaleId(),target.component(),currentValue));
            String level=currentValue==null ? target.value() : currentValue;
            var profile=program.difficultyProfiles().stream().filter(p -> p.targetRange()==null ? p.id().equals(level)
                    : p.targetRange().scaleId().equals(target.scaleId()) && new BigDecimal(level).compareTo(new BigDecimal(p.targetRange().min()))>=0
                    && new BigDecimal(level).compareTo(new BigDecimal(p.targetRange().max()))<=0).findFirst().orElseThrow(ScenarioData::invalid);
            require(profile.skills().contains(focus));
            var context=object().put("contractVersion",program.meaningAssessment() ? "scenario-learning-context.v2" : "scenario-learning-context.v1").put("programCode",program.code()).put("programVersion",program.version())
                    .put("programHash",program.definitionHash()).put("practiceMode","guided-listening").put("layoutVersion","three-activities.v1")
                    .put("focusSkill",focus).put("targetProvenance","USER_DECLARED_GOAL").put("currentProvenance",currentValue==null ? "UNKNOWN" : "USER_DECLARED")
                    .put("calibrationStatus","INTERNAL_PRACTICE_UNCALIBRATED").put("qualityPolicyVersion",program.qualityPolicyVersion())
                    .put("skillTaxonomyVersion",program.skillTaxonomyVersion());
            context.set("target",ScenarioJson.MAPPER.valueToTree(target)); context.put("currentValue",currentValue);
            context.set("targetScale",ScenarioJson.MAPPER.valueToTree(program.targetScales().stream().filter(s -> s.scaleId().equals(target.scaleId())).findFirst().orElseThrow(ScenarioData::invalid)));
            context.set("profile",ScenarioJson.MAPPER.valueToTree(profile));
            context.set("answerLanguages",ScenarioJson.MAPPER.valueToTree(List.of("vi","en")));
            return context;
        } catch (IllegalArgumentException invalid) { throw ScenarioData.invalid(); }
    }
}
