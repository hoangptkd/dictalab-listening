package com.dictalab.vocabulary;

import org.springframework.stereotype.Component;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;

@Component
public class CuratedContextProvider implements ContextExerciseProvider {
    private final VocabularyRepository repo;
    public CuratedContextProvider(VocabularyRepository repo) { this.repo=repo; }
    @Override public List<Exercise> select(long user,String deckId,int limit) {
        var notes=repo.rows("""
                SELECT n.*,c.lapse_count,c.state,c.due_at
                FROM vocab_cards c JOIN vocab_notes n ON n.id=c.note_id
                WHERE c.user_id=? AND c.card_type='WORD_RECALL' AND c.suspended=FALSE AND n.archived=FALSE
                  AND (CAST(? AS VARCHAR(36)) IS NULL OR EXISTS (SELECT 1 FROM vocab_deck_notes x WHERE x.note_id=n.id AND x.deck_id=?))
                ORDER BY CASE WHEN c.state='RELEARNING' THEN 0 ELSE 1 END,c.lapse_count DESC,c.due_at,c.id LIMIT 300
                """,user,deckId,deckId);
        var exercises=repo.rows("""
                SELECT e.* FROM vocab_context_exercises e WHERE e.published=TRUE
                AND NOT EXISTS (SELECT 1 FROM vocab_session_items i JOIN vocab_study_sessions s ON s.id=i.session_id
                    WHERE s.user_id=? AND i.exercise_key=e.id AND s.created_at>CURRENT_TIMESTAMP - INTERVAL '1' DAY)
                ORDER BY e.id LIMIT 200
                """,user);
        var targets=repo.rows("""
                SELECT t.*,n.term,n.meaning_vi FROM vocab_context_targets t
                JOIN vocab_notes n ON n.id=t.note_id JOIN vocab_context_exercises e ON e.id=t.exercise_id
                WHERE e.published=TRUE ORDER BY t.exercise_id,t.note_id
                """);
        Map<String,List<Map<String,Object>>> byExercise=new HashMap<>();
        for(var target:targets) byExercise.computeIfAbsent((String)target.get("exercise_id"),ignored->new ArrayList<>()).add(target);
        Map<String,Integer> priority=new HashMap<>();
        for(int i=0;i<notes.size();i++) priority.put((String)notes.get(i).get("id"),notes.size()-i);
        // Recent explicit recall evidence affects selection, never the SRS schedule.
        Set<String> assessed=new HashSet<>();
        for(var log:repo.rows("SELECT target_results_json FROM vocab_review_logs WHERE user_id=? AND target_results_json<>'[]' ORDER BY reviewed_at DESC,id DESC LIMIT 1000",user)) {
            for(var target:repo.mapper().readValue((String)log.get("target_results_json"),VocabularyDtos.TargetResult[].class)) {
                if(assessed.add(target.noteId()) && priority.containsKey(target.noteId()))
                    priority.compute(target.noteId(),(id,value)->value+switch(target.result()) { case "FORGOTTEN"->1000; case "UNSURE"->500; default->0; });
            }
        }
        Map<String,List<String>> sources=new HashMap<>();
        for(var row:repo.rows("SELECT note_id,lesson_title FROM vocab_note_sources WHERE user_id=? ORDER BY created_at DESC LIMIT 1000",user))
            sources.computeIfAbsent((String)row.get("note_id"),ignored->new ArrayList<>()).add((String)row.get("lesson_title"));
        List<Exercise> candidates=new ArrayList<>(),result=new ArrayList<>();
        Map<String,Map<String,Object>> bySense=new HashMap<>();
        for(var note:notes) bySense.putIfAbsent(sense(note),note);
        Set<String> usedNotes=new HashSet<>();
        for(var exercise:exercises) {
            List<Map<String,Object>> matched=new ArrayList<>();
            Set<String> matchedIds=new HashSet<>();
            for(var target:byExercise.getOrDefault((String)exercise.get("id"),List.of())) {
                var match=bySense.get(sense(target));
                if(match!=null && matchedIds.add((String)match.get("id"))) matched.add(Map.of("noteId",match.get("id"),"term",target.get("term"),"meaningVi",target.get("meaning_vi"),"surfaceForm",target.get("surface_form"),"sourceLessons",sources.getOrDefault((String)match.get("id"),List.of())));
            }
            int required=byExercise.getOrDefault((String)exercise.get("id"),List.of()).size();
            if(matched.size()!=required || matched.size()<2) continue;
            List<String> translations=new ArrayList<>(); translations.add((String)exercise.get("translation_vi"));
            translations.addAll(repo.strings((String)exercise.get("alternatives_json")));
            candidates.add(new Exercise((String)exercise.get("id"),(String)exercise.get("prompt"),translations,matched,"CURATED"));
        }
        candidates.sort(Comparator.comparingInt((Exercise e)->e.targets().stream().mapToInt(t->priority.getOrDefault((String)t.get("noteId"),0)).sum()).reversed());
        for(var exercise:candidates) {
            if(exercise.targets().stream().anyMatch(t->usedNotes.contains((String)t.get("noteId")))) continue;
            result.add(exercise); exercise.targets().forEach(t->usedNotes.add((String)t.get("noteId")));
            if(result.size()==limit) return result;
        }
        // Personal examples are an explicit single-word fallback, never an invented combination.
        for(var note:notes) {
            if(usedNotes.contains((String)note.get("id")) || ((String)note.get("example_en")).isBlank() || ((String)note.get("example_vi")).isBlank()) continue;
            result.add(new Exercise("personal:"+note.get("id"),(String)note.get("example_en"),List.of((String)note.get("example_vi")),
                    List.of(Map.of("noteId",note.get("id"),"term",note.get("term"),"meaningVi",note.get("meaning_vi"))),"PERSONAL_EXAMPLE"));
            if(result.size()==limit) break;
        }
        return result;
    }
    private static String sense(Map<String,Object> note) {
        return RuleAnswerEvaluator.normalize((String)note.get("term"))+"\u0000"+RuleAnswerEvaluator.normalize((String)note.get("meaning_vi"));
    }
}
