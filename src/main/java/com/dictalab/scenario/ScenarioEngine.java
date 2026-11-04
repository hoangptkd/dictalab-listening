package com.dictalab.scenario;

import com.dictalab.service.AiPlatformClient;
import com.dictalab.service.MediaStorageService;
import com.dictalab.service.S3MediaLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

import static com.dictalab.scenario.ScenarioData.*;

/** Core owns learning state; remote workers return private, independently validated artifacts only. */
@Service
public class ScenarioEngine {
    private final ScenarioData data;
    private final ScenarioOperations operations;
    private final ScenarioRelease release;
    private final AiPlatformClient ai;
    private final MediaStorageService media;
    private final S3MediaLifecycle lifecycle;
    private final boolean local;
    private final ScenarioPersonalization personalization;

    public ScenarioEngine(ScenarioData data, ScenarioOperations operations, ScenarioRelease release,
                          AiPlatformClient ai, MediaStorageService media,
                          S3MediaLifecycle lifecycle, Environment environment, ScenarioPersonalization personalization) {
        this.data=data; this.operations=operations; this.release=release;
        this.ai=ai; this.media=media; this.lifecycle=lifecycle;
        this.local=environment.acceptsProfiles(Profiles.of("local"));
        this.personalization=personalization;
    }

    public record DraftRequest(String inputVi, String programCode, int programVersion, String profileId) {}
    public record SessionRequest(String draftId, long draftVersion) {}
    public record Answer(String questionId, String textVi) {}
    public record AnswersRequest(long expectedVersion, List<Answer> answers) {}
    public record VersionRequest(long expectedVersion) {}
    public record HelpRequest(long expectedVersion, String kind) {}
    public record PlayRequest(long expectedVersion, String segmentId) {}
    public record RemixRequest(long expectedVersion, String inputVi, String profileId) {}

    static String safeInput(String input, int max) {
        require(input != null && !input.isBlank() && input.length() <= max);
        String normalized=Normalizer.normalize(input.strip(),Normalizer.Form.NFC);
        require(normalized.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'));
        return normalized.replaceAll("(?i)Bearer\\s+\\S+", "[REDACTED]")
                .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[REDACTED]")
                .replaceAll("[A-Za-z0-9_+/\\-]{32,}={0,2}", "[REDACTED]")
                .replaceAll("(?<!\\w)(?:\\+?\\d[ .()-]?){9,15}(?!\\w)", "[REDACTED]");
    }

    public JsonNode createDraft(long user, String key, DraftRequest request) {
        return data.tx.execute(tx -> {
            data.lockUser(user);
            var prior=operations.prior(user,"draft",key,request); if (prior != null) return prior;
            if (request.programVersion()>=2) {
                var program=release.require(user,request.programCode(),request.programVersion());
                return newDraft(user,key,request,"draft",request.inputVi(),request.profileId(),null,id(),program,personalization.forDraft(user,program));
            }
            release.require(user);
            require(request.programCode()!=null && request.programCode().equals(release.program().code()) && request.programVersion()==release.program().version());
            return newDraft(user,key,request,"draft",request.inputVi(),request.profileId(),null,id(),release.program(),null);
        });
    }

    private JsonNode newDraft(long user,String key,Object intent,String scope,String input,String profileId,String parent,String family,ScenarioProgram program,ObjectNode context) {
        if (context!=null) {
            profileId=context.path("profile").path("id").asText();
            if (input==null || input.isBlank()) input="Tôi muốn luyện nghe " + program.displayNameVi()+", tập trung kỹ năng "+context.path("focusSkill").asText()+". Hãy đề xuất một bối cảnh phù hợp.";
        }
        input=safeInput(input,2000);
        String selectedProfile=profileId;
        var profile=program.difficultyProfiles().stream().filter(p -> p.id().equals(selectedProfile)).findFirst().orElseThrow(ScenarioData::invalid);
        JsonNode profileJson=object().put("id",profile.id()).put("minWordsPerActivity",profile.minWordsPerActivity())
                .put("maxWordsPerActivity",profile.maxWordsPerActivity()).put("maxWordsPerSession",profile.maxWordsPerSession());
        String draft=id();
        data.db.update("""
                INSERT INTO scenario_drafts(id,user_id,input_vi,program_code,program_version,program_hash,profile_json,target_json,status,parent_session_id,family_id,expires_at,learning_context_json)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?,?,?)
                """,draft,user,input,program.code(),program.version(),program.definitionHash(),profileJson.toString(),
                json(data.db.queryForList("SELECT program_code,program_version,component,scale_id,target_value,provenance FROM scenario_learning_goals WHERE user_id=? AND program_code=?",user,program.code())),
                parent,family,Timestamp.from(Instant.now().plusSeconds(86400)),context==null ? null : context.toString());
        return operations.create(user,scope,key,intent,"EXTRACT",draft,payload(data.owned("scenario_drafts",user,draft),"EXTRACT").put("inputVi",input));
    }

    public JsonNode draft(long user,String id) {
        var row=data.owned("scenario_drafts",user,id);
        var result=object().put("draftId",id).put("status",(String)row.get("status")).put("version",number(row,"version"))
                .put("programCode",(String)row.get("program_code")).put("programVersion",number(row,"program_version"))
                .put("expiresAt",instant(row.get("expires_at")).toString());
        result.set("profile",tree((String)row.get("profile_json"))); result.set("declaredGoals",tree((String)row.get("target_json")));
        if (row.get("learning_context_json")!=null) result.set("learningContext",tree((String)row.get("learning_context_json")));
        if (row.get("specification_json") != null) result.set("specification",tree((String)row.get("specification_json")));
        if (instant(row.get("expires_at")).isBefore(Instant.now())) result.put("status","EXPIRED");
        return result;
    }

    public JsonNode createSession(long user,String key,SessionRequest request) {
        return data.tx.execute(tx -> {
            data.lockUser(user); var prior=operations.prior(user,"session",key,request); if (prior != null) return prior;
            var draft=data.owned("scenario_drafts",user,request.draftId()); version(draft,request.draftVersion());
            var program=requireProgram(user,draft);
            require("READY".equals(draft.get("status")) && instant(draft.get("expires_at")).isAfter(Instant.now()));
            require(program.definitionHash().equals(draft.get("program_hash")));
            if (data.db.queryForObject("SELECT COUNT(*) FROM scenario_sessions WHERE draft_id=?",Integer.class,request.draftId())>0)
                throw new ScenarioFailure(409,"DRAFT_ALREADY_USED","Bản nháp đã có bài học. Hãy mở bài đã tạo.");
            int count=data.db.queryForObject("""
                    SELECT (SELECT COUNT(*) FROM ai_conversation_sessions WHERE user_id=? AND created_at>?)
                         + (SELECT COUNT(*) FROM scenario_sessions WHERE user_id=? AND created_at>?)
                    """,Integer.class,user,Timestamp.from(Instant.now().minusSeconds(86400)),user,Timestamp.from(Instant.now().minusSeconds(86400)));
            if (!local && count>=20) throw new ScenarioFailure(429,"USER_QUOTA_EXCEEDED","Đã đạt giới hạn 20 bài trong 24 giờ.");
            String session=id();
            data.db.update("""
                    INSERT INTO scenario_sessions(id,user_id,draft_id,title_vi,status,parent_session_id,family_id)
                    VALUES (?,?,?,?,'PREPARING',?,?)
                    """,session,user,request.draftId(),tree((String)draft.get("specification_json")).path("titleVi").asText(),draft.get("parent_session_id"),draft.get("family_id"));
            var payload=payload(draft,"GENERATE"); payload.put("inputVi",(String)draft.get("input_vi")); payload.set("specification",tree((String)draft.get("specification_json")));
            return operations.create(user,"session",key,request,"GENERATE",session,payload);
        });
    }

    private ObjectNode payload(Map<String,Object> draft,String phase) {
        boolean personalized=draft.get("learning_context_json")!=null;
        var result=object().put("kind","scenario").put("contractVersion",personalized ? "scenario-job.v2" : "scenario-job.v1").put("phase",phase)
                .put("promptHash",personalized ? release.personalizedPromptHash() : release.promptHash()).put("programHash",(String)draft.get("program_hash"));
        if (personalized) result.set("learningContext",tree((String)draft.get("learning_context_json")));
        result.set("profile",tree((String)draft.get("profile_json"))); return result;
    }
    private ScenarioProgram requireProgram(long user,Map<String,Object> draft) {
        if (draft.get("learning_context_json")!=null) return release.require(user,(String)draft.get("program_code"),(int)number(draft,"program_version"));
        release.require(user); return release.program();
    }

    public JsonNode session(long user,String id) {
        return data.tx.execute(tx -> {
            data.lockUser(user); var row=data.owned("scenario_sessions",user,id);
            var draft=data.owned("scenario_drafts",user,(String)row.get("draft_id"));
            var result=object().put("sessionId",id).put("titleVi",(String)row.get("title_vi")).put("status",(String)row.get("status"))
                    .put("version",number(row,"version")).put("createdAt",instant(row.get("created_at")).toString())
                    .put("familyId",(String)row.get("family_id")).put("programCode",(String)draft.get("program_code"))
                    .put("programVersion",number(draft,"program_version"));
            result.set("profile",tree((String)draft.get("profile_json")));
            result.set("specification",tree((String)draft.get("specification_json")));
            result.set("declaredGoals",tree((String)draft.get("target_json")));
            if (draft.get("learning_context_json")!=null) result.set("learningContext",tree((String)draft.get("learning_context_json")));
            var activities=result.putArray("activities");
            for (var a : data.db.queryForList("SELECT * FROM scenario_activities WHERE session_id=? ORDER BY ordinal",id)) activities.add(publicActivity(a));
            return result;
        });
    }

    private JsonNode publicActivity(Map<String,Object> a) {
        String aid=(String)a.get("id"); JsonNode content=tree((String)a.get("private_json"));
        boolean completed="COMPLETED".equals(a.get("status")), transcript=Boolean.TRUE.equals(a.get("transcript_opened"));
        var result=object().put("activityId",aid).put("ordinal",number(a,"ordinal")).put("status",(String)a.get("status"))
                .put("transcriptOpened",transcript).put("hintOpened",Boolean.TRUE.equals(a.get("hint_opened"))).put("plays",number(a,"plays"));
        var segments=result.putArray("segments");
        for (var s : content.path("activity").path("segments")) {
            var segment=segments.addObject().put("segmentId",s.path("id").asText()).put("speakerId",s.path("speakerId").asText());
            if (completed || transcript) segment.put("textEn",s.path("textEn").asText());
        }
        var questions=result.putArray("questions");
        for (var q : content.path("activity").path("questions")) {
            var question=questions.addObject().put("questionId",q.path("id").asText()).put("type",q.path("type").asText())
                    .put("skill",q.path("skill").asText()).put("promptVi",q.path("promptVi").asText());
            if (completed) { question.put("answerVi",q.path("answerVi").asText()); question.set("evidence",q.path("evidence")); }
        }
        if (completed || transcript) result.set("translations",content.path("translations"));
        var attempts=result.putArray("attempts");
        for (var attempt : data.db.queryForList("SELECT * FROM scenario_attempts WHERE activity_id=? ORDER BY ordinal",aid)) {
            var item=attempts.addObject().put("attemptId",(String)attempt.get("id")).put("ordinal",number(attempt,"ordinal")).put("status",(String)attempt.get("status"));
            item.set("answers",tree((String)attempt.get("answers_json"))); item.set("support",tree((String)attempt.get("support_json")));
            if (attempt.get("result_json")!=null) item.set("result",tree((String)attempt.get("result_json")));
        }
        return result;
    }

    public JsonNode history(long user,String status,int limit,String before,String beforeId) {
        require(limit>=1 && limit<=50);
        require(status==null || Set.of("PREPARING","ACTIVE","COMPLETED","ABANDONED","PREPARATION_FAILED").contains(status));
        var args=new ArrayList<Object>(); args.add(user); String where=" WHERE user_id=?";
        if (status!=null) { where+=" AND status=?"; args.add(status); }
        if (before!=null) {
            try { args.add(Timestamp.from(Instant.parse(before))); args.add(Timestamp.from(Instant.parse(before))); args.add(key(beforeId)); }
            catch (RuntimeException invalid) { throw invalid(); }
            where+=" AND (created_at<? OR (created_at=? AND id<?))";
        } else require(beforeId==null);
        args.add(limit+1);
        var rows=data.db.queryForList("SELECT id,title_vi,status,version,created_at FROM scenario_sessions"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",args.toArray());
        var result=object(); var items=result.putArray("sessions");
        for (var row : rows.subList(0,Math.min(limit,rows.size()))) items.addObject().put("sessionId",(String)row.get("id"))
                .put("titleVi",(String)row.get("title_vi")).put("status",(String)row.get("status"))
                .put("version",number(row,"version")).put("createdAt",instant(row.get("created_at")).toString());
        if (rows.size()>limit) { var last=rows.get(limit-1); result.putObject("nextCursor").put("before",instant(last.get("created_at")).toString()).put("beforeId",(String)last.get("id")); }
        return result;
    }

    private Map<String,Object> editable(long user,String session,String activity,long expected) {
        var s=data.owned("scenario_sessions",user,session); version(s,expected);
        require("ACTIVE".equals(s.get("status")));
        var a=data.activity(user,session,activity); require("READY".equals(a.get("status")));
        require(data.db.queryForObject("SELECT COUNT(*) FROM scenario_activities WHERE session_id=? AND ordinal<? AND status<>'COMPLETED'",Integer.class,session,number(a,"ordinal"))==0);
        return a;
    }
    private void bump(String session) { data.db.update("UPDATE scenario_sessions SET version=version+1 WHERE id=?",session); }

    public JsonNode answer(long user,String session,String activity,String key,AnswersRequest request) {
        String scope="answer/"+activity;
        return data.tx.execute(tx -> {
            data.lockUser(user); data.activity(user,session,activity);
            var prior=operations.prior(user,scope,key,request); if (prior!=null) return prior;
            var ownedSession=data.owned("scenario_sessions",user,session);
            requireProgram(user,data.owned("scenario_drafts",user,(String)ownedSession.get("draft_id")));
            var a=editable(user,session,activity,request.expectedVersion());
            var saved=tree((String)a.get("private_json")); var expected=new HashSet<String>();
            saved.path("activity").path("questions").forEach(q -> expected.add(q.path("id").asText()));
            require(request.answers()!=null && request.answers().size()==2);
            var answers=ScenarioJson.MAPPER.createArrayNode(); var seen=new HashSet<String>();
            for (var answer : request.answers()) {
                require(answer!=null && expected.contains(answer.questionId()) && seen.add(answer.questionId()));
                require(answer.textVi()!=null && answer.textVi().length()<=1500);
                answers.addObject().put("questionId",answer.questionId()).put("textVi",answer.textVi().isBlank() ? "" : safeInput(answer.textVi(),1500));
            }
            var attempts=data.db.queryForList("SELECT status FROM scenario_attempts WHERE activity_id=? ORDER BY ordinal",activity);
            require(attempts.size()<3 && attempts.stream().noneMatch(r -> "PENDING".equals(r.get("status"))));
            String attempt=id(); var support=object().put("transcriptOpened",Boolean.TRUE.equals(a.get("transcript_opened")))
                    .put("hintOpened",Boolean.TRUE.equals(a.get("hint_opened"))).put("plays",number(a,"plays"));
            data.db.update("INSERT INTO scenario_attempts(id,activity_id,ordinal,answers_json,support_json,status) VALUES (?,?,?,?,?,'PENDING')",attempt,activity,attempts.size()+1,answers.toString(),support.toString());
            var s=data.owned("scenario_sessions",user,session); var draft=data.owned("scenario_drafts",user,(String)s.get("draft_id"));
            var payload=payload(draft,"GRADE"); payload.set("activity",saved.path("activity")); payload.set("answers",answers);
            bump(session); return operations.create(user,scope,key,request,"GRADE",attempt,payload);
        });
    }

    public JsonNode help(long user,String session,String activity,String key,HelpRequest request) {
        return data.tx.execute(tx -> data.receipt(user,"help/"+activity,key,request,() -> {
            var a=editable(user,session,activity,request.expectedVersion());
            require(Set.of("TRANSCRIPT","HINT").contains(request.kind()));
            String field=request.kind().equals("TRANSCRIPT") ? "transcript_opened" : "hint_opened";
            data.db.update("UPDATE scenario_activities SET "+field+"=TRUE WHERE id=?",activity); bump(session);
            var result=object().put("version",request.expectedVersion()+1);
            if (request.kind().equals("TRANSCRIPT")) {
                var content=tree((String)a.get("private_json")); result.set("segments",content.path("activity").path("segments")); result.set("translations",content.path("translations"));
            } else result.put("hintVi","Nghe ý chính, lý do và quan điểm của người nói. Trả lời ngắn bằng tiếng Anh hoặc tiếng Việt, chỉ nêu điều câu hỏi yêu cầu.");
            return result;
        }));
    }

    public JsonNode completeActivity(long user,String session,String activity,String key,VersionRequest request) {
        return data.tx.execute(tx -> data.receipt(user,"complete/"+activity,key,request,() -> {
            editable(user,session,activity,request.expectedVersion());
            require(data.db.queryForObject("SELECT COUNT(*) FROM scenario_attempts WHERE activity_id=? AND status='GRADED'",Integer.class,activity)>0);
            require(data.db.queryForObject("SELECT COUNT(*) FROM scenario_attempts WHERE activity_id=? AND status='PENDING'",Integer.class,activity)==0);
            data.db.update("UPDATE scenario_activities SET status='COMPLETED' WHERE id=?",activity); bump(session);
            if (data.db.queryForObject("SELECT COUNT(*) FROM scenario_activities WHERE session_id=? AND status<>'COMPLETED'",Integer.class,session)==0)
                data.db.update("UPDATE scenario_sessions SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?",session);
            return session(user,session);
        }));
    }

    public JsonNode remix(long user,String session,String key,RemixRequest request) {
        return data.tx.execute(tx -> {
            data.lockUser(user); data.owned("scenario_sessions",user,session);
            String scope="remix/"+session; var prior=operations.prior(user,scope,key,request); if (prior!=null) return prior;
            var s=data.owned("scenario_sessions",user,session); version(s,request.expectedVersion()); require("COMPLETED".equals(s.get("status")));
            var d=data.owned("scenario_drafts",user,(String)s.get("draft_id"));
            var program=requireProgram(user,d);
            ObjectNode context=d.get("learning_context_json")==null ? null : (ObjectNode)tree((String)d.get("learning_context_json"));
            String input=safeInput("Tình huống trước: "+tree((String)d.get("specification_json")).path("settingVi").asText()
                    +"\nBiến thể mong muốn: "+safeInput(request.inputVi(),1200),2000);
            if (context!=null) require(request.profileId()==null || request.profileId().equals(context.path("profile").path("id").asText()));
            return newDraft(user,key,request,scope,input,request.profileId(),session,(String)s.get("family_id"),program,context);
        });
    }

    public JsonNode play(long user,String session,String activity,String key,PlayRequest request) {
        return data.tx.execute(tx -> data.receipt(user,"play/"+activity,key,request,() -> {
            var s=data.owned("scenario_sessions",user,session); version(s,request.expectedVersion());
            var draft=data.owned("scenario_drafts",user,(String)s.get("draft_id"));
            require(Set.of("ACTIVE","COMPLETED").contains(s.get("status")));
            var a=data.activity(user,session,activity);
            require(data.db.queryForObject("SELECT COUNT(*) FROM scenario_activities WHERE session_id=? AND ordinal<? AND status<>'COMPLETED'",Integer.class,session,number(a,"ordinal"))==0);
            JsonNode segment=null;
            for (var part : tree((String)a.get("private_json")).path("activity").path("segments")) if (part.path("id").asText().equals(request.segmentId())) segment=part;
            require(segment!=null);
            // Fixed server TTS recipe; cache is private to this activity and never accepts learner text.
            String fingerprint=ScenarioJson.hash("scenario-tts-v1/"+a.get("content_hash")+"/"+segment.toString());
            var rows=data.db.queryForList("SELECT id,status FROM scenario_media WHERE activity_id=? AND segment_id=? AND fingerprint=?",activity,request.segmentId(),fingerprint);
            String mediaId;
            JsonNode result;
            if (!rows.isEmpty()) {
                mediaId=(String)rows.get(0).get("id");
                if ("READY".equals(rows.get(0).get("status"))) result=object().put("mediaId",mediaId).put("status","READY").put("audioUrl","/api/v2/scenarios/media/"+mediaId);
                else {
                    var ops=data.db.queryForList("SELECT id FROM scenario_operations WHERE user_id=? AND kind='TTS' AND resource_id=? ORDER BY created_at DESC,id DESC",String.class,user,mediaId);
                    require(!ops.isEmpty());
                    if ("FAILED".equals(rows.get(0).get("status")) && ops.size()<3) {
                        requireProgram(user,draft);
                        data.db.update("UPDATE scenario_media SET status='PENDING' WHERE id=?",mediaId);
                        result=operations.create(user,"tts/"+mediaId+"/"+ops.size(),key,request,"TTS",mediaId,object().put("kind","tts").put("text",segment.path("textEn").asText()));
                    } else result=operations.get(user,ops.get(0));
                }
            } else {
                requireProgram(user,draft); mediaId=id();
                data.db.update("INSERT INTO scenario_media(id,activity_id,segment_id,fingerprint,status) VALUES (?,?,?,?,'PENDING')",mediaId,activity,request.segmentId(),fingerprint);
                result=operations.create(user,"tts/"+mediaId,key,request,"TTS",mediaId,object().put("kind","tts").put("text",segment.path("textEn").asText()));
            }
            data.db.update("UPDATE scenario_activities SET plays=plays+1 WHERE id=?",activity); bump(session);
            ((ObjectNode)result).put("sessionVersion",request.expectedVersion()+1); return result;
        }));
    }

    public void serveMedia(long user,String id,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var rows=data.db.queryForList("""
                SELECT m.* FROM scenario_media m JOIN scenario_activities a ON a.id=m.activity_id
                JOIN scenario_sessions s ON s.id=a.session_id WHERE m.id=? AND s.user_id=? AND m.status='READY'
                """,id,user);
        if (rows.isEmpty()) throw missing(); var row=rows.get(0); response.setHeader("Cache-Control","no-store");
        if (row.get("s3_audio_url")!=null) media.serveConversationAudio((String)row.get("s3_audio_url"),request,response);
        else { byte[] wav=(byte[])row.get("wav"); response.setContentType("audio/wav"); response.setContentLength(wav.length); if (!request.getMethod().equals("HEAD")) response.getOutputStream().write(wav); }
    }

    public JsonNode cancel(long user,String id,String key,VersionRequest request) {
        return data.tx.execute(tx -> data.receipt(user,"cancel/"+id,key,request,() -> {
            var op=data.owned("scenario_operations",user,id); version(op,request.expectedVersion());
            if (!"PENDING".equals(op.get("status")) || Boolean.TRUE.equals(op.get("submitted")))
                throw new ScenarioFailure(409,"OPERATION_ALREADY_DISPATCHED","Tác vụ đã gửi đi; cần chờ kết quả để tránh tính phí lại.");
            int changed=data.db.update("UPDATE scenario_operations SET status='CANCELLED',version=version+1 WHERE id=? AND status='PENDING' AND submitted=FALSE AND version=?",id,request.expectedVersion());
            if (changed!=1) throw new ScenarioFailure(409,"OPERATION_ALREADY_DISPATCHED","Tác vụ vừa được gửi đi.");
            failed(op,"FAILED"); return operations.get(user,id);
        }));
    }

    /** Called after remote I/O, committing only while the original lease and owner still exist. */
    void accept(long user,String id,String lease,Map<String,Object> op,JsonNode result) {
        String kind=(String)op.get("kind"), resource=(String)op.get("resource_id"); JsonNode input=tree((String)op.get("payload_json"));
        ScenarioContent.check("1".equals(result.path("schemaVersion").asText()) && id.equals(result.path("requestId").asText()));
        if (!kind.equals("TTS")) ScenarioContent.check(id.equals(result.path("generationId").asText()));
        else ScenarioContent.check(result.path("generationId").asText().matches("[a-f0-9-]{36}"));
        String provider=result.path("provider").asText();
        ScenarioContent.check(kind.equals("TTS") ? provider.equals("cartesia") : ai.scenarioProvider().equals(provider));
        for (String f : List.of("model","modelDigest","promptVersion","promptHash")) ScenarioContent.text(result,f,128);
        ScenarioContent.check(result.path("promptHash").asText().matches("[a-f0-9]{64}"));
        if (!kind.equals("TTS")) {
            boolean personalized="scenario-job.v2".equals(input.path("contractVersion").asText());
            // Previously submitted jobs may settle only with their exact historical fingerprint.
            boolean legacy=Set.of("f3a1a93382df50b9e798d9ae9d11e502d3e8eb0c347751195c6d00b7ba88b8a1",
                    "0d40fae31e57eef602c6f1e8402ea6116f8beeee56ac011c7c3d018f7f0152c2").contains(input.path("promptHash").asText());
            boolean previousV3=Set.of("3391407dddc9762afe3774cb30ee60e4a9f0936e70597e8a057b8bdfe03395fe",
                    "c8e817015d97850288b8bbe377934602cc1f4df1a4b7ce18a2d5b5de8b28c2b5").contains(input.path("promptHash").asText());
            boolean previousV4="e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b".equals(input.path("promptHash").asText());
            boolean previousV5="6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f".equals(input.path("promptHash").asText());
            boolean previousPersonalized="bd0e89b4391becdf84e4389595ff6fa9a4368f4323ac50eebaaf4dffb5b4c2aa".equals(input.path("promptHash").asText());
            boolean previousFewshot="54f9fdeda01717f5c8853239357dad6dff2e0fa94df929f6c82e6d2497af52a1".equals(input.path("promptHash").asText());
            boolean previousFewshotV2="6a857865dddd79783a50c6dbf45bfdd281feeb56b681bdff34abd24c4df5b2e3".equals(input.path("promptHash").asText());
            boolean previousFewshotV3="f5f347bcac1dd0767f3af409704778357cd0ce39dd2a2d7f325671bc22abee1f".equals(input.path("promptHash").asText());
            boolean previousFewshotV4="aa79b767fee0970408e4fe420d1d97615595fdd195e16d8e919f28881f265485".equals(input.path("promptHash").asText());
            boolean previousFewshotV5="358201f08eb87c7d4d2306220c3828a92cda57ac93d57faed80a508d892fa1c1".equals(input.path("promptHash").asText());
            boolean previousFewshotV6="c1dcc2e3cc29847ba09b6d53a447a45e8bfe48ae13e9dfb4e2e131173f1e6a18".equals(input.path("promptHash").asText());
            boolean previousFewshotV7="defd36c45a53aa6ac5002d0acb50996a5790fd87554646e36f9830dd3a225504".equals(input.path("promptHash").asText());
            boolean harnessV9=personalized && "df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303".equals(input.path("promptHash").asText());
            boolean harness=harnessV9 || personalized && "a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b".equals(input.path("promptHash").asText());
            ScenarioContent.check(personalized ? previousPersonalized || previousFewshot || previousFewshotV2 || previousFewshotV3 || previousFewshotV4 || previousFewshotV5 || previousFewshotV6 || previousFewshotV7 || harness
                    : legacy || previousV3 || previousV4 || previousV5 || release.promptHash().equals(input.path("promptHash").asText()));
            ScenarioContent.check(input.path("promptHash").asText().equals(result.path("promptHash").asText())
                    && (personalized ? (previousPersonalized ? "SCENARIO_PERSONALIZED_V1_GRADE_V4" : previousFewshot ? "SCENARIO_PERSONALIZED_FEWSHOT_V1_GRADE_V4" : previousFewshotV2 ? "SCENARIO_PERSONALIZED_FEWSHOT_V2_GRADE_V4" : previousFewshotV3 ? "SCENARIO_PERSONALIZED_FEWSHOT_V3_GRADE_V4" : previousFewshotV4 ? "SCENARIO_PERSONALIZED_FEWSHOT_V4_GRADE_V4" : previousFewshotV5 ? "SCENARIO_PERSONALIZED_FEWSHOT_V5_GRADE_V5" : previousFewshotV6 ? "SCENARIO_PERSONALIZED_FEWSHOT_V6_GRADE_V5" : previousFewshotV7 ? "SCENARIO_PERSONALIZED_FEWSHOT_V7_GRADE_V5" : harnessV9 ? "SCENARIO_PERSONALIZED_FEWSHOT_V9_HARNESS_V2_GRADE_V5" : "SCENARIO_PERSONALIZED_FEWSHOT_V8_HARNESS_V1_GRADE_V5") : legacy ? "SCENARIO_LISTENING_V1" : previousV3 ? "SCENARIO_LISTENING_V3" : previousV4 ? "SCENARIO_LISTENING_V4" : previousV5 ? "SCENARIO_LISTENING_V5_CANDIDATE_1" : "SCENARIO_LISTENING_V5_GRADE_V4_1").equals(result.path("promptVersion").asText())
                    && input.path("programHash").asText().equals(result.path("programHash").asText()));
            var expected=kind.equals("GENERATE") ? (legacy ? List.of("GENERATE","TRANSLATE","REVIEW")
                    : previousV3 ? List.of("GENERATE","FACTS","QUESTIONS","TRANSLATE","REVIEW")
                    : List.of("GENERATE","QUESTIONS","REVIEW_QUESTIONS","TRANSLATE","REVIEW")) : List.of(kind);
            boolean harnessGeneration=harness && kind.equals("GENERATE");
            ScenarioContent.list(result.path("calls"),expected.size(),harnessGeneration ? 9 : expected.size()); int n=0;
            for (var call : result.path("calls")) {
                ScenarioContent.fields(call,"purpose","provider","model");
                ScenarioContent.check((harnessGeneration || expected.get(n++).equals(call.path("purpose").asText())) && provider.equals(call.path("provider").asText())
                        && result.path("model").asText().equals(call.path("model").asText()));
            }
            if (harnessGeneration) validateHarnessCalls(result);
        }
        JsonNode task=result.path("task"); byte[] wav=null; String url=null;
        if (kind.equals("EXTRACT")) ScenarioContent.specification(task,"scenario-job.v2".equals(input.path("contractVersion").asText()));
        else if (kind.equals("GENERATE")) {
            String pin=input.path("promptHash").asText();
            boolean advisory="scenario-job.v2".equals(input.path("contractVersion").asText()) && Set.of("aa79b767fee0970408e4fe420d1d97615595fdd195e16d8e919f28881f265485", "358201f08eb87c7d4d2306220c3828a92cda57ac93d57faed80a508d892fa1c1", "c1dcc2e3cc29847ba09b6d53a447a45e8bfe48ae13e9dfb4e2e131173f1e6a18", "defd36c45a53aa6ac5002d0acb50996a5790fd87554646e36f9830dd3a225504", "a98960f21fabe1f652f68fac72794c41e5c18870a400059cef4b03f50a9a838b", "df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303").contains(pin);
            boolean flexible=advisory || "scenario-job.v2".equals(input.path("contractVersion").asText()) && "f5f347bcac1dd0767f3af409704778357cd0ce39dd2a2d7f325671bc22abee1f".equals(pin);
            ScenarioContent.content(task,input.path("specification"),input.path("profile"),input.has("learningContext") ? input.path("learningContext") : null,flexible,advisory);
        }
        else if (kind.equals("GRADE")) ScenarioContent.grade(task,input.path("answers"));
        else {
            ScenarioContent.check("TTS_WAV_V1".equals(result.path("promptVersion").asText()));
            try { wav=ai.audio(result); } catch (IllegalStateException invalid) { throw new IllegalArgumentException("Invalid audio result"); }
            if (media.s3Enabled()) url=media.storeConversationAudio(wav);
        }
        final byte[] audio=wav; final String audioUrl=url;
        data.tx.executeWithoutResult(tx -> {
            if (data.db.queryForList("SELECT id FROM app_users WHERE id=? FOR UPDATE",user).isEmpty()) return;
            if (data.db.queryForObject("SELECT COUNT(*) FROM scenario_operations WHERE id=? AND lease_id=? AND status='RUNNING'",Integer.class,id,lease)!=1) return;
            switch (kind) {
                case "EXTRACT" -> data.db.update("UPDATE scenario_drafts SET specification_json=?,status=?,version=version+1 WHERE id=? AND user_id=? AND status='PENDING'",
                        task.toString(),task.path("allowed").asBoolean() ? "READY" : "FAILED",resource,user);
                case "GENERATE" -> {
                    var translations=task.path("translations"); int ordinal=0;
                    for (var activity : task.path("activities")) {
                        var privateData=object(); privateData.set("activity",activity); var ts=privateData.putArray("translations");
                        for (var t : translations) if (t.path("activityId").equals(activity.path("id"))) ts.add(t);
                        data.db.update("INSERT INTO scenario_activities(id,session_id,ordinal,private_json,content_hash) VALUES (?,?,?,?,?)",id(),resource,++ordinal,privateData.toString(),ScenarioJson.hash(ScenarioJson.canonical(privateData)));
                    }
                    var provenance=result.deepCopy(); ((ObjectNode)provenance).remove("task");
                    data.db.update("UPDATE scenario_sessions SET status='ACTIVE',provenance_json=?,version=version+1 WHERE id=? AND user_id=? AND status='PREPARING'",provenance.toString(),resource,user);
                }
                case "GRADE" -> {
                    data.db.update("UPDATE scenario_attempts SET status='GRADED',result_json=? WHERE id=? AND status='PENDING'",task.toString(),resource);
                    data.db.update("UPDATE scenario_sessions SET version=version+1 WHERE id IN (SELECT a.session_id FROM scenario_activities a JOIN scenario_attempts t ON t.activity_id=a.id WHERE t.id=?)",resource);
                }
                case "TTS" -> {
                    if (audioUrl!=null) lifecycle.retain(audioUrl);
                    data.db.update("UPDATE scenario_media SET status='READY',s3_audio_url=?,wav=? WHERE id=? AND status='PENDING'",audioUrl,audioUrl==null ? audio : null,resource);
                }
                default -> throw new IllegalArgumentException("Invalid operation");
            }
            var metadata=result.deepCopy(); ((ObjectNode)metadata).remove("task");
            data.db.update("UPDATE scenario_operations SET status='SUCCEEDED',submitted=TRUE,error_code=NULL,result_metadata_json=?,lease_id=NULL,lease_until=NULL,version=version+1 WHERE id=? AND lease_id=?",metadata.toString(),id,lease);
        });
    }

    /** Finite V8/V9 protocol: one patch and one format retry per dependent review stage. */
    static void validateHarnessCalls(JsonNode result) {
        var purposes=new ArrayList<String>();
        for (var call : result.path("calls")) purposes.add(call.path("purpose").asText());
        ScenarioContent.check(purposes.size()>=5 && purposes.size()<=9
                && purposes.get(0).equals("GENERATE") && purposes.get(1).equals("QUESTIONS"));
        int cursor=2, extra=0;
        for (String stage : List.of("QUESTIONS","TRANSLATIONS")) {
            if (stage.equals("TRANSLATIONS")) {
                ScenarioContent.check(cursor<purposes.size() && purposes.get(cursor++).equals("TRANSLATE"));
            }
            String patch="REPAIR_"+stage, review=stage.equals("QUESTIONS") ? "REVIEW_QUESTIONS" : "REVIEW";
            if (cursor<purposes.size() && purposes.get(cursor).equals(patch)) {
                // Deterministic repair reserves both the patch and its first semantic review.
                cursor++; extra+=2;
                ScenarioContent.check(cursor<purposes.size() && purposes.get(cursor++).equals(review));
            } else {
                ScenarioContent.check(cursor<purposes.size() && purposes.get(cursor++).equals(review));
                if (cursor<purposes.size() && purposes.get(cursor).equals(review)) { cursor++; extra++; }
                if (cursor<purposes.size() && purposes.get(cursor).equals(patch)) {
                    cursor++; extra++;
                    // Invalid patches retain the original without a semantic recheck.
                    if (cursor<purposes.size() && purposes.get(cursor).equals(review)) { cursor++; extra++; }
                }
            }
        }
        var report=result.path("qualityHarness");
        boolean v9="df832a8cb71a8229b95d02be4acaacd6a56b78ed7a9f747b51c4a222864cc303".equals(result.path("promptHash").asText());
        ScenarioContent.check(cursor==purposes.size() && extra<=4
                && report.path("extraCalls").isIntegralNumber() && report.path("extraCalls").asInt()==extra
                && report.path("policyVersion").asText().equals(v9 ? "scenario-quality-harness.v2" : "scenario-quality-harness.v1")
                && report.path("policyHash").asText().equals(v9 ? "e15c2b30a59f2b505b047b1920ebdff9c4cb7ccb2ae87cf9cf24803e2e8c2a66" : "ac1a80c90c9f24149f14a9221c0c7382e653d94fccbd823b3862c55fa043bc6b"));
    }

    void failed(Map<String,Object> op,String status) {
        String resource=(String)op.get("resource_id");
        switch ((String)op.get("kind")) {
            case "EXTRACT" -> data.db.update("UPDATE scenario_drafts SET status='FAILED',version=version+1 WHERE id=? AND status='PENDING'",resource);
            case "GENERATE" -> data.db.update("UPDATE scenario_sessions SET status='PREPARATION_FAILED',version=version+1 WHERE id=? AND status='PREPARING'",resource);
            case "GRADE" -> {
                data.db.update("UPDATE scenario_attempts SET status=? WHERE id=? AND status='PENDING'",status.equals("UNKNOWN") ? "UNKNOWN" : "FAILED",resource);
                data.db.update("UPDATE scenario_sessions SET version=version+1 WHERE id IN (SELECT a.session_id FROM scenario_activities a JOIN scenario_attempts t ON t.activity_id=a.id WHERE t.id=?)",resource);
            }
            case "TTS" -> data.db.update("UPDATE scenario_media SET status=? WHERE id=? AND status='PENDING'",status.equals("UNKNOWN") ? "UNKNOWN" : "FAILED",resource);
            default -> throw new IllegalArgumentException("Invalid operation");
        }
    }

    public JsonNode suggestions() {
        var result=object(); var groups=result.putArray("suggestions");
        for (var suggestion : com.dictalab.service.ConversationService.scenarioSuggestions()) groups.add(ScenarioJson.MAPPER.valueToTree(suggestion));
        return result;
    }

    public JsonNode recommendations(long user) {
        // First unaided graded attempt per activity; repeated assisted attempts never inflate evidence.
        var rows=data.db.queryForList("""
                SELECT t.result_json,t.support_json,a.private_json FROM scenario_attempts t
                JOIN scenario_activities a ON a.id=t.activity_id JOIN scenario_sessions s ON s.id=a.session_id
                WHERE s.user_id=? AND t.ordinal=1 AND t.status='GRADED' ORDER BY t.created_at DESC,t.id DESC LIMIT 20
                """,user);
        var counts=new TreeMap<String,Integer>(); int facts=0;
        for (var r : rows) {
            var support=tree((String)r.get("support_json"));
            if (support.path("transcriptOpened").asBoolean() || support.path("hintOpened").asBoolean()) continue;
            var questions=tree((String)r.get("private_json")).path("activity").path("questions");
            for (var fact : tree((String)r.get("result_json")).path("facts")) {
                if (fact.path("status").asText().equals("UNCERTAIN")) continue;
                facts++;
                if (!fact.path("status").asText().equals("CAPTURED")) for (var q : questions) if (q.path("id").equals(fact.path("questionId"))) counts.merge(q.path("skill").asText(),1,Integer::sum);
            }
        }
        var result=object().put("basis",facts<6 ? "COLD_START" : "RECENT_UNAIDED_FIRST_ATTEMPTS").put("observedFacts",facts)
                .put("isAssessedScore",false).put("messageVi",facts<6 ? "Chưa đủ dữ liệu. Hãy chọn tình huống gần nhu cầu của bạn." : "Gợi ý dựa trên tối đa 20 lần trả lời đầu, không mở gợi ý hoặc bản chép.");
        if (facts>=6) result.set("practiceSignals",ScenarioJson.MAPPER.valueToTree(counts));
        var suggested=suggestions().path("suggestions").deepCopy();
        if (facts>=6 && !counts.isEmpty()) {
            String focus=counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            for (var item : suggested) ((ObjectNode)item).put("focusSkill",focus).put("reasonVi",focus.equals("detail") ? "Luyện bắt chi tiết đã bỏ lỡ" : "Luyện hiểu ý chính")
                    .put("inputVi",item.path("inputVi").asText()+(focus.equals("detail") ? " Tôi muốn chú ý thông tin cụ thể trong lời nói." : " Tôi muốn tập trung vào ý chính của cuộc trao đổi."));
        }
        result.set("suggestions",suggested); return result;
    }
}
