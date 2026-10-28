package com.dictalab.vocabulary;

import com.dictalab.service.TranscriptTokenizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;

@Service
@Transactional(readOnly=true)
public class VocabularySourceService {
    private final VocabularyRepository repo;
    private final VocabularyContentService content;
    private final TranscriptTokenizer tokenizer;
    public VocabularySourceService(VocabularyRepository repo,VocabularyContentService content,TranscriptTokenizer tokenizer) {
        this.repo=repo; this.content=content; this.tokenizer=tokenizer;
    }
    public Page<Map<String,Object>> suggestions(long user,int page) {
        var rows=repo.rows("""
                SELECT w.id AS word_attempt_id,w.word_index,w.first_answer,w.learner_reported_error_type,
                    t.transcript,l.title AS lesson_title,p.id AS session_id,
                    CASE WHEN w.learner_reported_error_type='UNKNOWN' THEN 'UNKNOWN_WORD' ELSE 'LISTENING_MISS' END AS evidence
                FROM word_attempts w JOIN sentence_attempts a ON a.id=w.sentence_attempt_id
                JOIN lesson_sentences t ON t.id=a.sentence_id JOIN lessons l ON l.id=t.lesson_id
                JOIN practice_sessions p ON p.id=a.practice_session_id
                WHERE p.user_id=? AND a.completed=TRUE AND w.first_attempt_correct=FALSE
                ORDER BY w.id DESC LIMIT 21 OFFSET ?
                """,user,(VocabularyContentService.page(page)-1)*20);
        for(var row:rows) {
            var words=tokenizer.words((String)row.get("transcript")); int index=((Number)row.get("word_index")).intValue();
            row.put("term",index<words.size()?words.get(index):"");
        }
        return new Page<>(rows.stream().limit(20).toList(),page,20,rows.size()>20);
    }
    @Transactional(timeout=20)
    public Map<String,Object> save(long user,String key,String deckId,SourceNote request) {
        return repo.receipt(user,key,"source-note/"+deckId,request,Map.class,()-> {
            repo.deck(user,deckId);
            var source=repo.one("""
                    SELECT w.word_index,t.transcript,l.title AS lesson_title FROM word_attempts w
                    JOIN sentence_attempts a ON a.id=w.sentence_attempt_id JOIN practice_sessions p ON p.id=a.practice_session_id
                    JOIN lesson_sentences t ON t.id=a.sentence_id JOIN lessons l ON l.id=t.lesson_id
                    WHERE w.id=? AND p.user_id=? AND a.completed=TRUE
                    """,request.wordAttemptId(),user);
            String sourceKey="word:"+request.wordAttemptId();
            var prior=repo.rows("SELECT note_id FROM vocab_note_sources WHERE user_id=? AND source_key=?",user,sourceKey);
            String noteId=prior.isEmpty()?request.existingNoteId():(String)prior.get(0).get("note_id");
            if(noteId==null) {
                var matches=repo.rows("SELECT id FROM vocab_notes WHERE owner_user_id=? AND term=? AND meaning_vi=? AND archived=FALSE ORDER BY id LIMIT 1",user,request.note().term().strip(),request.note().meaningVi().strip());
                if(!matches.isEmpty()) noteId=(String)matches.get(0).get("id");
            }
            if(noteId!=null) {
                repo.note(user,noteId);
                if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",Integer.class,deckId,noteId)==0)
                    repo.jdbc().update("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",deckId,noteId);
            } else noteId=content.insertNote(user,deckId,request.note(),"PRACTICE",null,null);
            if(prior.isEmpty()) repo.jdbc().update("INSERT INTO vocab_note_sources(id,user_id,note_id,word_attempt_id,source_key,transcript,lesson_title) VALUES (?,?,?,?,?,?,?)",
                    id(),user,noteId,request.wordAttemptId(),sourceKey,source.get("transcript"),source.get("lesson_title"));
            return content.noteView(repo.note(user,noteId));
        });
    }
}
