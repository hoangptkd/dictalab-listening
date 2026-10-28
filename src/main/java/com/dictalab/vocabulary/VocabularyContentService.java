package com.dictalab.vocabulary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;

@Service
@Transactional(readOnly=true)
public class VocabularyContentService {
    final VocabularyRepository repo;
    public VocabularyContentService(VocabularyRepository repo) { this.repo=repo; }
    static int page(int page) { if (page<1 || page>10000) throw invalid("Trang không hợp lệ."); return page; }
    static String ownerClause(Long owner) { return owner==null ? "owner_user_id IS NULL" : "owner_user_id=?"; }
    public Page<Map<String,Object>> decks(long user,boolean catalog,boolean admin,int page) {
        int offset=(page(page)-1)*20;
        String filter=catalog ? "d.owner_user_id IS NULL AND d.status='PUBLISHED'"
                : admin ? "d.owner_user_id IS NULL AND d.status<>'ARCHIVED'" : "d.owner_user_id=? AND d.status<>'ARCHIVED'";
        var args=new ArrayList<Object>(); if (!catalog && !admin) args.add(user); args.add(offset);
        var rows=repo.rows("SELECT d.*,(SELECT COUNT(*) FROM vocab_deck_notes x JOIN vocab_notes n ON n.id=x.note_id WHERE x.deck_id=d.id AND n.archived=FALSE) AS note_count FROM vocab_decks d WHERE "
                +filter+" ORDER BY d.created_at DESC,d.id LIMIT 21 OFFSET ?",args.toArray());
        return new Page<>(rows.stream().limit(20).toList(),page,20,rows.size()>20);
    }
    public Map<String,Object> detail(long user,String id,boolean catalog,boolean admin,int page) {
        var deck=repo.deck(catalog || admin ? null : user,id);
        if (catalog && !"PUBLISHED".equals(deck.get("status"))) throw new com.dictalab.exception.ResourceNotFoundException("Không tìm thấy bộ từ.");
        var notes=repo.rows("""
                SELECT n.* FROM vocab_notes n JOIN vocab_deck_notes x ON x.note_id=n.id
                WHERE x.deck_id=? AND n.archived=FALSE ORDER BY n.created_at,n.id LIMIT 51 OFFSET ?
                """,id,(page(page)-1)*50);
        return Map.of("deck",deck,"notes",new Page<>(notes.stream().limit(50).map(this::noteView).toList(),page,50,notes.size()>50));
    }
    Map<String,Object> noteView(Map<String,Object> note) {
        var result=new LinkedHashMap<>(note);
        result.put("alternatives",repo.strings((String)result.remove("alternatives_json")));
        return result;
    }
    public Map<String,Object> note(long user,String id) {
        var result=noteView(repo.note(user,id));
        result.put("cards",repo.rows("SELECT * FROM vocab_cards WHERE user_id=? AND note_id=? ORDER BY card_type",user,id));
        result.put("sources",repo.rows("SELECT transcript,lesson_title,created_at FROM vocab_note_sources WHERE user_id=? AND note_id=? ORDER BY created_at DESC LIMIT 30",user,id));
        result.put("reviews",repo.rows("SELECT grade,grading_source,assisted,schedule_changed,reviewed_at FROM vocab_review_logs WHERE user_id=? AND note_id=? ORDER BY reviewed_at DESC,id DESC LIMIT 30",user,id));
        return result;
    }
    @Transactional(timeout=20)
    public Map<String,Object> createDeck(long user,String key,DeckEdit edit,boolean system) {
        return repo.receipt(user,key,"create-deck/"+system,edit,Map.class,()-> {
            Long owner=system ? null : user;
            if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_decks WHERE "+ownerClause(owner),Integer.class,owner==null ? new Object[]{}:new Object[]{owner})>=200)
                throw conflict("Đã đạt giới hạn 200 bộ từ.");
            String id=id();
            repo.jdbc().update("INSERT INTO vocab_decks(id,owner_user_id,title,description,level) VALUES (?,?,?,?,?)",id,owner,edit.title().strip(),edit.description().strip(),edit.level());
            return repo.deck(owner,id);
        });
    }
    @Transactional(timeout=20)
    public Map<String,Object> updateDeck(long user,String key,String id,DeckEdit edit,boolean system) {
        return repo.receipt(user,key,"edit-deck/"+id,edit,Map.class,()-> {
            Long owner=system ? null : user; var deck=repo.deck(owner,id);
            if (number(deck,"revision")!=edit.revision()) throw conflict("Bộ từ đã thay đổi. Hãy tải lại.");
            if(repo.jdbc().update("UPDATE vocab_decks SET title=?,description=?,level=?,revision=revision+1 WHERE id=? AND revision=?",edit.title().strip(),edit.description().strip(),edit.level(),id,edit.revision())!=1)
                throw conflict("Bộ từ đã thay đổi.");
            return repo.deck(owner,id);
        });
    }
    @Transactional(timeout=20)
    public Map<String,Object> publish(long user,String key,String id,Publication edit) {
        return repo.receipt(user,key,"publish/"+id,edit,Map.class,()-> {
            var deck=repo.deck(null,id);
            if (number(deck,"revision")!=edit.revision()) throw conflict("Bộ từ đã thay đổi.");
            if (edit.published() && repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_deck_notes x JOIN vocab_notes n ON n.id=x.note_id WHERE x.deck_id=? AND n.archived=FALSE",Integer.class,id)==0)
                throw invalid("Hãy thêm từ trước khi xuất bản.");
            if(repo.jdbc().update("UPDATE vocab_decks SET status=?,revision=revision+1 WHERE id=? AND revision=?",edit.published()?"PUBLISHED":"DRAFT",id,edit.revision())!=1)
                throw conflict("Bộ từ đã thay đổi.");
            return repo.deck(null,id);
        });
    }
    @Transactional(timeout=20)
    public Map<String,Object> addNote(long user,String key,String deckId,NoteEdit edit,boolean system) {
        return repo.receipt(user,key,"new-note/"+deckId,edit,Map.class,()-> {
            Long owner=system ? null : user; repo.deck(owner,deckId);
            return noteView(repo.note(owner,insertNote(owner,deckId,edit,"MANUAL",null,null)));
        });
    }
    String insertNote(Long owner,String deckId,NoteEdit edit,String source,String sourceId,Long sourceRevision) {
        if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_notes WHERE "+ownerClause(owner),Integer.class,owner==null?new Object[]{}:new Object[]{owner})>=10000)
            throw conflict("Đã đạt giới hạn 10.000 mục từ.");
        String note=id();
        repo.jdbc().update("INSERT INTO vocab_notes(id,owner_user_id,term,meaning_vi,part_of_speech,example_en,example_vi,tags,alternatives_json,source_kind,source_note_id,source_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                note,owner,edit.term().strip(),edit.meaningVi().strip(),edit.partOfSpeech().strip(),edit.exampleEn().strip(),edit.exampleVi().strip(),edit.tags().strip(),repo.encode(edit.alternatives()),source,sourceId,sourceRevision);
        repo.jdbc().update("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",deckId,note);
        if (owner!=null) { ensureCard(owner,note,"WORD_RECALL"); if (edit.meaningRecall()) ensureCard(owner,note,"MEANING_RECALL"); }
        return note;
    }
    void ensureCard(long user,String note,String type) {
        if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_cards WHERE user_id=? AND note_id=? AND card_type=?",Integer.class,user,note,type)==0)
            repo.jdbc().update("INSERT INTO vocab_cards(id,user_id,note_id,card_type) VALUES (?,?,?,?)",id(),user,note,type);
    }
    record NewNote(String id,NoteEdit note,String source,String sourceId,Long sourceRevision) {}
    void insertBatch(long user,String deck,List<NewNote> notes) {
        if(notes.isEmpty()) return;
        int current=repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_notes WHERE owner_user_id=?",Integer.class,user);
        if(current+notes.size()>10000) throw conflict("Đã đạt giới hạn 10.000 mục từ.");
        List<Object[]> values=new ArrayList<>(),links=new ArrayList<>(),cards=new ArrayList<>();
        for(var item:notes) {
            var note=item.note();
            values.add(new Object[]{item.id(),user,note.term().strip(),note.meaningVi().strip(),note.partOfSpeech().strip(),note.exampleEn().strip(),note.exampleVi().strip(),note.tags().strip(),repo.encode(note.alternatives()),item.source(),item.sourceId(),item.sourceRevision()});
            links.add(new Object[]{deck,item.id()}); cards.add(new Object[]{id(),user,item.id(),"WORD_RECALL"});
            if(note.meaningRecall()) cards.add(new Object[]{id(),user,item.id(),"MEANING_RECALL"});
        }
        repo.jdbc().batchUpdate("INSERT INTO vocab_notes(id,owner_user_id,term,meaning_vi,part_of_speech,example_en,example_vi,tags,alternatives_json,source_kind,source_note_id,source_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",values);
        repo.jdbc().batchUpdate("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",links);
        repo.jdbc().batchUpdate("INSERT INTO vocab_cards(id,user_id,note_id,card_type) VALUES (?,?,?,?)",cards);
    }
    @Transactional(timeout=20)
    public Map<String,Object> updateNote(long user,String key,String noteId,NoteEdit edit,boolean system) {
        return repo.receipt(user,key,"edit-note/"+noteId,edit,Map.class,()-> {
            Long owner=system?null:user; var old=repo.note(owner,noteId);
            if (number(old,"revision")!=edit.revision()) throw conflict("Mục từ đã thay đổi.");
            // Existing challenges keep their immutable prompt/answer snapshots. A changed sense needs a new note.
            if(repo.jdbc().update("UPDATE vocab_notes SET term=?,meaning_vi=?,part_of_speech=?,example_en=?,example_vi=?,tags=?,alternatives_json=?,revision=revision+1 WHERE id=? AND revision=?",
                    edit.term().strip(),edit.meaningVi().strip(),edit.partOfSpeech().strip(),edit.exampleEn().strip(),edit.exampleVi().strip(),edit.tags().strip(),repo.encode(edit.alternatives()),noteId,edit.revision())!=1)
                throw conflict("Mục từ đã thay đổi.");
            if (owner!=null && edit.meaningRecall()) ensureCard(user,noteId,"MEANING_RECALL");
            return noteView(repo.note(owner,noteId));
        });
    }
    @Transactional(timeout=20)
    public Map<String,Object> membership(long user,String key,String deckId,String noteId,boolean remove) {
        return repo.receipt(user,key,"membership/"+deckId+"/"+noteId,Map.of("remove",remove),Map.class,()-> {
            repo.deck(user,deckId); repo.note(user,noteId);
            if (remove) repo.jdbc().update("DELETE FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",deckId,noteId);
            else if (repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",Integer.class,deckId,noteId)==0)
                repo.jdbc().update("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",deckId,noteId);
            return Map.of("saved",true);
        });
    }
    @Transactional(timeout=20)
    public Map<String,Object> copy(long user,String key,String sourceDeck,String targetDeck) {
        return repo.receipt(user,key,"copy/"+sourceDeck,Map.of("deckId",targetDeck),Map.class,()-> {
            repo.deck(user,targetDeck);
            var source=repo.deck(null,sourceDeck);
            if (!"PUBLISHED".equals(source.get("status"))) throw new com.dictalab.exception.ResourceNotFoundException("Không tìm thấy bộ từ.");
            var notes=repo.rows("SELECT n.* FROM vocab_notes n JOIN vocab_deck_notes x ON x.note_id=n.id WHERE x.deck_id=? AND n.archived=FALSE ORDER BY n.id LIMIT 1001",sourceDeck);
            if (notes.size()>1000) throw invalid("Bộ từ vượt giới hạn 1.000 mục mỗi lần thêm.");
            Map<String,String> copies=new HashMap<>();
            for(var row:repo.rows("SELECT id,source_note_id FROM vocab_notes WHERE owner_user_id=? AND source_note_id IS NOT NULL",user))
                copies.put((String)row.get("source_note_id"),(String)row.get("id"));
            Set<String> members=new HashSet<>(repo.jdbc().queryForList("SELECT note_id FROM vocab_deck_notes WHERE deck_id=?",String.class,targetDeck));
            List<NewNote> additions=new ArrayList<>(); List<Object[]> links=new ArrayList<>();
            for (var note:notes) {
                String copy=copies.get((String)note.get("id"));
                if(copy==null) additions.add(new NewNote(id(),editFrom(note),"CATALOG",(String)note.get("id"),number(note,"revision")));
                else if(members.add(copy)) links.add(new Object[]{targetDeck,copy});
            }
            insertBatch(user,targetDeck,additions);
            if(!links.isEmpty()) repo.jdbc().batchUpdate("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",links);
            return Map.of("added",notes.size(),"deckId",targetDeck);
        });
    }
    NoteEdit editFrom(Map<String,Object> row) {
        return new NoteEdit((String)row.get("term"),(String)row.get("meaning_vi"),(String)row.get("part_of_speech"),
                (String)row.get("example_en"),(String)row.get("example_vi"),(String)row.get("tags"),repo.strings((String)row.get("alternatives_json")),0,false);
    }
    @Transactional(timeout=20)
    public Map<String,Object> suspend(long user,String key,String cardId,Suspension value) {
        return repo.receipt(user,key,"suspend/"+cardId,value,Map.class,()-> {
            var card=repo.one("SELECT * FROM vocab_cards WHERE id=? AND user_id=?",cardId,user);
            if (number(card,"revision")!=value.revision()) throw conflict("Thẻ đã thay đổi.");
            repo.jdbc().update("UPDATE vocab_cards SET suspended=?,revision=revision+1 WHERE id=?",value.suspended(),cardId);
            return Map.of("saved",true);
        });
    }
}
