package com.dictalab.vocabulary;

import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;

@Service
@Transactional(readOnly=true)
public class VocabularyImportService {
    private final VocabularyRepository repo;
    private final VocabularyContentService content;
    private final Validator validator;
    public VocabularyImportService(VocabularyRepository repo,VocabularyContentService content,Validator validator) {
        this.repo=repo; this.content=content; this.validator=validator;
    }
    @Transactional(timeout=20)
    public Map<String,Object> preview(long user,String key,ImportCsv request) {
        return repo.receipt(user,key,"preview-import",request,Map.class,()-> {
            repo.deck(user,request.deckId());
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_imports WHERE user_id=? AND expires_at>CURRENT_TIMESTAMP AND result_json IS NULL",Integer.class,user)>=10)
                throw conflict("Hãy xác nhận hoặc chờ hết hạn các bản nhập đang chờ.");
            var rows=VocabularyCsv.parse(request.csv()); List<NoteEdit> notes=new ArrayList<>(); List<String> errors=new ArrayList<>(); Set<String> seen=new HashSet<>();
            int line=1;
            for(var row:rows) {
                line++;
                if(row.size()!=6) { errors.add("Dòng "+line+": cần đúng 6 cột."); continue; }
                var note=new NoteEdit(row.get(0).strip(),row.get(1).strip(),row.get(2).strip(),row.get(3).strip(),row.get(4).strip(),row.get(5).strip(),List.of(),0,false);
                if(!validator.validate(note).isEmpty()) { errors.add("Dòng "+line+": nội dung trống hoặc quá dài."); continue; }
                if(!seen.add(RuleAnswerEvaluator.normalize(note.term())+"\u0000"+RuleAnswerEvaluator.normalize(note.meaningVi()))) { errors.add("Dòng "+line+": trùng từ và nghĩa trong CSV."); continue; }
                notes.add(note);
            }
            if(rows.isEmpty()) errors.add("CSV chưa có mục từ.");
            if(!errors.isEmpty()) return Map.of("id","","errors",errors,"count",notes.size(),"preview",notes.stream().limit(20).toList());
            String id=id();
            repo.jdbc().update("INSERT INTO vocab_imports(id,user_id,deck_id,payload_json,expires_at) VALUES (?,?,?,?,?)",id,user,request.deckId(),repo.encode(notes),Timestamp.from(Instant.now().plusSeconds(86400)));
            return Map.of("id",id,"errors",List.of(),"count",notes.size(),"preview",notes.stream().limit(20).toList());
        });
    }
    @Transactional(timeout=30)
    public Map<String,Object> confirm(long user,String key,String importId) {
        return repo.receipt(user,key,"confirm-import/"+importId,Map.of(),Map.class,()-> {
            var row=repo.one("SELECT * FROM vocab_imports WHERE id=? AND user_id=?",importId,user);
            if(row.get("result_json")!=null) return repo.object((String)row.get("result_json"));
            if(!instant(row.get("expires_at")).isAfter(Instant.now())) throw conflict("Bản nhập đã hết hạn.");
            String deck=(String)row.get("deck_id"); repo.deck(user,deck);
            var notes=repo.mapper().readValue((String)row.get("payload_json"),NoteEdit[].class);
            int added=0,skipped=0;
            Map<String,String> existing=new HashMap<>();
            for(var note:repo.rows("SELECT id,term,meaning_vi FROM vocab_notes WHERE owner_user_id=? AND archived=FALSE ORDER BY id",user))
                existing.putIfAbsent(note.get("term")+"\u0000"+note.get("meaning_vi"),(String)note.get("id"));
            Set<String> members=new HashSet<>(repo.jdbc().queryForList("SELECT note_id FROM vocab_deck_notes WHERE deck_id=?",String.class,deck));
            List<VocabularyContentService.NewNote> additions=new ArrayList<>(); List<Object[]> links=new ArrayList<>();
            for(var note:notes) {
                // Exact text and sense only. Distinct senses are retained.
                String noteId=existing.get(note.term()+"\u0000"+note.meaningVi());
                if(noteId==null) { additions.add(new VocabularyContentService.NewNote(id(),note,"CSV",null,null)); added++; }
                else {
                    if(members.add(noteId)) links.add(new Object[]{deck,noteId});
                    skipped++;
                }
            }
            content.insertBatch(user,deck,additions);
            if(!links.isEmpty()) repo.jdbc().batchUpdate("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",links);
            var result=Map.of("added",added,"reused",skipped,"deckId",deck);
            repo.jdbc().update("UPDATE vocab_imports SET result_json=? WHERE id=?",repo.encode(result),importId);
            return result;
        });
    }
    public String export(long user,String deck) {
        repo.deck(user,deck);
        var notes=repo.rows("SELECT n.* FROM vocab_notes n JOIN vocab_deck_notes x ON x.note_id=n.id WHERE x.deck_id=? AND n.archived=FALSE ORDER BY n.id LIMIT 10001",deck);
        StringBuilder result=new StringBuilder("\uFEFF").append(String.join(",",VocabularyCsv.HEADER)).append("\r\n");
        for(var note:notes) result.append(VocabularyCsv.row(List.of((String)note.get("term"),(String)note.get("meaning_vi"),(String)note.get("part_of_speech"),(String)note.get("example_en"),(String)note.get("example_vi"),(String)note.get("tags"))));
        return result.toString();
    }
}
