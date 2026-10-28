package com.dictalab.vocabulary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;

@Service
@Transactional(readOnly=true)
public class VocabularyStudyService {
    private final VocabularyRepository repo;
    private final VocabularyProperties config;
    private final ReviewScheduler scheduler;
    private final AnswerEvaluator evaluator;
    private final ContextExerciseProvider contexts;
    private final Clock clock;
    private static final ZoneId ZONE=ZoneId.of("Asia/Bangkok");
    @Autowired public VocabularyStudyService(VocabularyRepository repo,VocabularyProperties config,ReviewScheduler scheduler,
                                            AnswerEvaluator evaluator,ContextExerciseProvider contexts) {
        this(repo,config,scheduler,evaluator,contexts,Clock.systemUTC());
    }
    VocabularyStudyService(VocabularyRepository repo,VocabularyProperties config,ReviewScheduler scheduler,
                           AnswerEvaluator evaluator,ContextExerciseProvider contexts,Clock clock) {
        this.repo=repo; this.config=config; this.scheduler=scheduler; this.evaluator=evaluator; this.contexts=contexts; this.clock=clock;
    }
    public void requireEnabled() {
        if(!config.enabled()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Tính năng từ vựng chưa được bật.");
    }
    public Map<String,Object> summary(long user) {
        Instant now=clock.instant();
        var count=repo.one("""
                SELECT COUNT(*) AS total,
                COALESCE(SUM(CASE WHEN c.state='NEW' THEN 1 ELSE 0 END),0) AS new_cards,
                COALESCE(SUM(CASE WHEN c.state<>'NEW' AND c.due_at<=? AND (c.buried_until IS NULL OR c.buried_until<=?) THEN 1 ELSE 0 END),0) AS due,
                COALESCE(SUM(CASE WHEN c.state='RELEARNING' THEN 1 ELSE 0 END),0) AS relearning
                FROM vocab_cards c JOIN vocab_notes n ON n.id=c.note_id WHERE c.user_id=? AND c.suspended=FALSE AND n.archived=FALSE
                """,Timestamp.from(now),Timestamp.from(now),user);
        count.put("newLimit",config.newCardsPerDay()); count.put("timezone",ZONE.getId());
        count.put("reviewedToday",repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_review_logs WHERE user_id=? AND learning_day=?",Integer.class,user,day()));
        count.put("recent",repo.rows("SELECT grade,grading_source,assisted,schedule_changed,reviewed_at FROM vocab_review_logs WHERE user_id=? ORDER BY reviewed_at DESC,id DESC LIMIT 20",user));
        count.put("activeSessions",repo.rows("SELECT id,mode,expires_at FROM vocab_study_sessions WHERE user_id=? AND status='ACTIVE' AND expires_at>? ORDER BY created_at DESC LIMIT 3",user,Timestamp.from(now)));
        return count;
    }
    private LocalDate day() { return clock.instant().atZone(ZONE).toLocalDate(); }
    @Transactional(timeout=20)
    public Session start(long user,String key,SessionStart request) {
        return repo.receipt(user,key,"start-session",request,Session.class,()-> {
            if(request.deckId()!=null) repo.deck(user,request.deckId());
            Instant now=clock.instant();
            repo.jdbc().update("UPDATE vocab_study_sessions SET status='ABANDONED' WHERE user_id=? AND status='ACTIVE' AND expires_at<=?",user,Timestamp.from(now));
            var active=repo.rows("SELECT id FROM vocab_study_sessions WHERE user_id=? AND mode=? AND status='ACTIVE' ORDER BY created_at LIMIT 1",user,request.mode());
            if(!active.isEmpty()) return session(user,(String)active.get(0).get("id"));
            List<Map<String,Object>> cards=List.of();
            List<ContextExerciseProvider.Exercise> exercises=List.of();
            if(request.mode().equals("CONTEXT_TRANSLATION")) exercises=contexts.select(user,request.deckId(),Math.min(10,config.sessionSize()));
            else {
                int learned=repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_review_logs WHERE user_id=? AND learning_day=? AND was_new=TRUE AND schedule_changed=TRUE",Integer.class,user,day());
                int newLeft=Math.max(0,config.newCardsPerDay()-learned);
                var candidates=repo.rows("""
                        SELECT c.*,n.term,n.meaning_vi,n.alternatives_json,n.example_en,n.example_vi FROM vocab_cards c
                        JOIN vocab_notes n ON n.id=c.note_id WHERE c.user_id=? AND c.suspended=FALSE AND n.archived=FALSE
                        AND (c.buried_until IS NULL OR c.buried_until<=?)
                        AND (?='PRACTICE' OR c.due_at<=?)
                        AND (CAST(? AS VARCHAR(36)) IS NULL OR EXISTS (SELECT 1 FROM vocab_deck_notes x WHERE x.note_id=n.id AND x.deck_id=?))
                        ORDER BY CASE c.state WHEN 'RELEARNING' THEN 0 WHEN 'REVIEW' THEN 1 ELSE 2 END,c.due_at,c.id LIMIT 500
                        """,user,Timestamp.from(now),request.mode(),Timestamp.from(now),request.deckId(),request.deckId());
                var picked=new ArrayList<Map<String,Object>>(); var notes=new HashSet<String>();
                for(var card:candidates) {
                    if(notes.contains((String)card.get("note_id"))) continue;
                    if(request.mode().equals("SRS") && "NEW".equals(card.get("state"))) {
                        if(newLeft==0) continue; newLeft--;
                    }
                    notes.add((String)card.get("note_id")); picked.add(card);
                    if(picked.size()==config.sessionSize()) break;
                }
                cards=picked;
            }
            if(cards.isEmpty() && exercises.isEmpty()) return new Session("",request.mode(),"COMPLETED",now,List.of(),
                    request.mode().equals("CONTEXT_TRANSLATION") ? "Chưa có câu phù hợp. Hãy thêm từ từ bộ có sẵn hoặc bổ sung ví dụ và bản dịch cho từ của bạn." : "Chưa có thẻ phù hợp hoặc đã đạt giới hạn từ mới hôm nay. Bạn có thể chọn Luyện thêm.");
            String session=id();
            repo.jdbc().update("INSERT INTO vocab_study_sessions(id,user_id,deck_id,mode,created_at,expires_at) VALUES (?,?,?,?,?,?)",
                    session,user,request.deckId(),request.mode(),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
            int position=0;
            for(var card:cards) {
                boolean word=card.get("card_type").equals("WORD_RECALL");
                List<String> accepted=new ArrayList<>(); accepted.add((String)card.get(word?"term":"meaning_vi"));
                if(word) accepted.addAll(repo.strings((String)card.get("alternatives_json")));
                var feedback=new LinkedHashMap<String,Object>();
                for(String field:List.of("term","meaning_vi","example_en","example_vi")) feedback.put(field,card.get(field));
                feedback.put("noteId",card.get("note_id")); feedback.put("kind",card.get("card_type")); feedback.put("strict",word);
                insertItem(session,position++,(String)card.get("id"),number(card,"revision"),null,
                        (String)card.get(word?"meaning_vi":"term"),accepted,feedback);
            }
            for(var exercise:exercises) insertItem(session,position++,null,null,exercise.id(),exercise.prompt(),exercise.translations(),
                    Map.of("kind","CONTEXT_TRANSLATION","targets",exercise.targets(),"translationVi",exercise.translations().get(0),"source",exercise.source(),"strict",false));
            return session(user,session);
        });
    }
    private void insertItem(String session,int position,String card,Long revision,String exercise,String prompt,List<String> accepted,Map<String,Object> feedback) {
        repo.jdbc().update("INSERT INTO vocab_session_items(id,session_id,position_index,card_id,card_revision,exercise_key,prompt,expected_json,feedback_json) VALUES (?,?,?,?,?,?,?,?,?)",
                id(),session,position,card,revision,exercise,prompt,repo.encode(accepted),repo.encode(feedback));
    }
    public Session session(long user,String id) {
        var session=repo.one("SELECT * FROM vocab_study_sessions WHERE id=? AND user_id=?",id,user);
        var rows=repo.rows("SELECT i.*,r.after_json,r.grade,r.grading_source,r.target_results_json FROM vocab_session_items i LEFT JOIN vocab_review_logs r ON r.item_id=i.id WHERE i.session_id=? ORDER BY i.position_index",id);
        List<Item> items=rows.stream().map(row-> {
            boolean answered=row.get("answered_at")!=null;
            var feedback=repo.object((String)row.get("feedback_json"));
            Map<String,Object> result=new LinkedHashMap<>();
            if(row.get("grade")!=null) { result.put("grade",row.get("grade")); result.put("schedule",repo.object((String)row.get("after_json"))); result.put("gradingSource",row.get("grading_source")); }
            return new Item((String)row.get("id"),((Number)row.get("position_index")).intValue(),(String)feedback.get("kind"),
                    (String)row.get("prompt"),(String)row.get("answer"),(String)row.get("evaluation"),Boolean.TRUE.equals(row.get("assisted")),instant(row.get("graded_at")),answered?feedback:Map.of(),result);
        }).toList();
        String status=(String)session.get("status");
        if(status.equals("ACTIVE") && !instant(session.get("expires_at")).isAfter(clock.instant())) status="ABANDONED";
        return new Session(id,(String)session.get("mode"),status,instant(session.get("expires_at")),items,"");
    }
    private Map<String,Object> editableItem(long user,String itemId) {
        var item=repo.one("SELECT i.*,s.mode,s.user_id,s.status,s.expires_at FROM vocab_session_items i JOIN vocab_study_sessions s ON s.id=i.session_id WHERE i.id=? AND s.user_id=?",itemId,user);
        if(!"ACTIVE".equals(item.get("status")) || !instant(item.get("expires_at")).isAfter(clock.instant())) throw conflict("Phiên đã kết thúc hoặc hết hạn. Hãy bắt đầu phiên mới.");
        return item;
    }
    @Transactional(timeout=20)
    public Map<String,Object> answer(long user,String key,String itemId,Answer request,boolean reveal) {
        return repo.receipt(user,key,(reveal?"reveal/":"answer/")+itemId,request,Map.class,()-> {
            var item=editableItem(user,itemId);
            if(item.get("answered_at")!=null) {
                if(reveal || Objects.equals(request.answer(),item.get("answer"))) return feedback(item);
                throw conflict("Đã lưu lần trả lời đầu tiên. Hãy đánh giá để tiếp tục.");
            }
            if(!reveal && request.answer().isBlank()) throw invalid("Hãy nhập đáp án hoặc chọn Không nhớ.");
            var detail=repo.object((String)item.get("feedback_json"));
            String evaluation=reveal?"INCORRECT":evaluator.evaluate(request.answer(),repo.strings((String)item.get("expected_json")),Boolean.TRUE.equals(detail.get("strict")));
            repo.jdbc().update("UPDATE vocab_session_items SET answer=?,evaluation=?,assisted=?,answered_at=? WHERE id=?",
                    request.answer(),evaluation,reveal,Timestamp.from(clock.instant()),itemId);
            burySiblings(user,item,detail);
            item.put("answer",request.answer()); item.put("evaluation",evaluation); item.put("assisted",reveal);
            return feedback(item);
        });
    }
    @SuppressWarnings("unchecked") private void burySiblings(long user,Map<String,Object> item,Map<String,Object> detail) {
        Instant until=day().plusDays(1).atStartOfDay(ZONE).toInstant();
        if(detail.containsKey("noteId")) repo.jdbc().update("UPDATE vocab_cards SET buried_until=? WHERE user_id=? AND note_id=? AND id<>?",Timestamp.from(until),user,detail.get("noteId"),item.get("card_id"));
        if(detail.containsKey("targets")) for(var target:(List<Map<String,Object>>)detail.get("targets"))
            repo.jdbc().update("UPDATE vocab_cards SET buried_until=? WHERE user_id=? AND note_id=?",Timestamp.from(until),user,target.get("noteId"));
    }
    private Map<String,Object> feedback(Map<String,Object> item) {
        return Map.of("evaluation",item.get("evaluation"),"assisted",item.get("assisted"),"feedback",repo.object((String)item.get("feedback_json")));
    }
    @Transactional(timeout=20)
    public Map<String,Object> grade(long user,String key,String itemId,GradeRequest request) {
        return repo.receipt(user,key,"grade/"+itemId,request,Map.class,()-> {
            var existing=repo.rows("SELECT r.after_json,r.grade,r.grading_source,r.target_results_json FROM vocab_review_logs r JOIN vocab_session_items i ON i.id=r.item_id JOIN vocab_study_sessions s ON s.id=i.session_id WHERE i.id=? AND s.user_id=?",itemId,user);
            if(!existing.isEmpty()) {
                if(!existing.get(0).get("grade").equals(request.grade().name()) || !existing.get(0).get("target_results_json").equals(repo.encode(request.targets()))) throw conflict("Lượt ôn đã được đánh giá.");
                return Map.of("grade",request.grade(),"schedule",repo.object((String)existing.get(0).get("after_json")),"gradingSource",existing.get(0).get("grading_source"));
            }
            var item=editableItem(user,itemId);
            if(item.get("answered_at")==null) throw conflict("Hãy trả lời trước khi đánh giá.");
            boolean assisted=Boolean.TRUE.equals(item.get("assisted"));
            if((assisted || "INCORRECT".equals(item.get("evaluation"))) && request.grade()!=ReviewScheduler.Grade.AGAIN)
                throw invalid("Lượt chưa nhớ hoặc có hỗ trợ phải chọn Quên.");
            var detail=repo.object((String)item.get("feedback_json"));
            validateTargets(detail,request.targets());
            Object before=Map.of(),after=Map.of(); boolean changed=false,wasNew=false; String note=null;
            if(item.get("card_id")!=null) {
                var card=repo.one("SELECT c.* FROM vocab_cards c JOIN vocab_notes n ON n.id=c.note_id WHERE c.id=? AND c.user_id=? AND n.archived=FALSE",item.get("card_id"),user);
                note=(String)card.get("note_id"); var old=repo.schedule(card); before=old; after=old;
                if("SRS".equals(item.get("mode"))) {
                    if(Boolean.TRUE.equals(card.get("suspended")) || number(card,"revision")!=number(item,"card_revision")) throw conflict("Lịch ôn đã thay đổi. Hãy kết thúc phiên và tải lại.");
                    var next=scheduler.next(old,request.grade(),clock.instant()); after=next; changed=true; wasNew=old.state().equals("NEW");
                    int updates=repo.jdbc().update("UPDATE vocab_cards SET state=?,scheduler_version=?,due_at=?,last_reviewed_at=?,interval_days=?,ease_factor=?,successful_repetitions=?,lapse_count=?,revision=revision+1 WHERE id=? AND revision=?",
                            next.state(),next.version(),Timestamp.from(next.dueAt()),Timestamp.from(clock.instant()),next.intervalDays(),next.ease(),next.repetitions(),next.lapses(),card.get("id"),card.get("revision"));
                    if(updates!=1) throw conflict("Lịch ôn đã thay đổi.");
                }
            }
            String source="UNDETERMINED".equals(item.get("evaluation"))?"SELF":"RULES";
            repo.jdbc().update("INSERT INTO vocab_review_logs(id,user_id,item_id,note_id,card_id,grade,grading_source,answer,assisted,schedule_changed,was_new,learning_day,before_json,after_json,target_results_json,reviewed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id(),user,itemId,note,item.get("card_id"),request.grade().name(),source,item.get("answer"),assisted,changed,wasNew,day(),repo.encode(before),repo.encode(after),repo.encode(request.targets()),Timestamp.from(clock.instant()));
            repo.jdbc().update("UPDATE vocab_session_items SET graded_at=? WHERE id=?",Timestamp.from(clock.instant()),itemId);
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_session_items WHERE session_id=? AND graded_at IS NULL",Integer.class,item.get("session_id"))==0)
                repo.jdbc().update("UPDATE vocab_study_sessions SET status='COMPLETED' WHERE id=?",item.get("session_id"));
            return Map.of("grade",request.grade(),"schedule",after,"gradingSource",source);
        });
    }
    @SuppressWarnings("unchecked") private void validateTargets(Map<String,Object> detail,List<TargetResult> targets) {
        Set<String> allowed=new HashSet<>();
        if(detail.containsKey("targets")) for(var target:(List<Map<String,Object>>)detail.get("targets")) allowed.add((String)target.get("noteId"));
        Set<String> supplied=new HashSet<>();
        for(var target:targets) if(!allowed.contains(target.noteId()) || !supplied.add(target.noteId())) throw invalid("Từ mục tiêu không hợp lệ hoặc bị trùng.");
        if(!allowed.equals(supplied)) throw invalid("Hãy đánh giá từng từ trong câu.");
    }
    @Transactional(timeout=20)
    public Map<String,Object> abandon(long user,String key,String sessionId) {
        return repo.receipt(user,key,"abandon/"+sessionId,Map.of(),Map.class,()-> {
            repo.one("SELECT id FROM vocab_study_sessions WHERE id=? AND user_id=?",sessionId,user);
            repo.jdbc().update("UPDATE vocab_study_sessions SET status='ABANDONED' WHERE id=? AND status='ACTIVE'",sessionId);
            return Map.of("saved",true);
        });
    }
}
