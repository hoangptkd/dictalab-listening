package com.dictalab.vocabulary;

import com.dictalab.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyRepository.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;

@Service
@Transactional(readOnly=true)
public class VocabularyCatalogService {
    public record Node(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.:-]{1,80}") String id, String parentId,
                       @NotBlank @Size(max=120) String title, @NotNull @Size(max=80) String level,
                       @Min(0) int order, boolean hasDeck) {}
    public record Entry(@Pattern(regexp="[a-f0-9]{64}") @NotNull String sourceKey, @NotBlank String nodeId,
                        @NotNull @Size(max=200) String subtopic, @Valid @NotNull NoteEdit note,
                        @NotBlank @Size(max=4000) String sourceJson,
                        @NotBlank @Size(max=4000) String classificationJson) {}
    public record Bundle(@NotBlank String collectionId, @NotNull @Size(min=1,max=400) List<@NotNull @Valid Node> nodes,
                         @NotNull @Size(min=1,max=1000) List<@NotNull @Valid Entry> entries,
                         @Pattern(regexp="[a-f0-9]{64}") @NotNull String sourceSha256,
                         @NotBlank @Size(max=1000) String attribution) {}
    public record Apply(@NotNull @Valid Bundle bundle,@NotBlank String previewHash) {}
    public record Metadata(@NotBlank String nodeId,@NotNull @Size(max=200) String subtopic,
                           @Min(0) long revision) {}
    private final VocabularyRepository repo;
    private final VocabularyContentService content;
    private final Validator validator;
    public VocabularyCatalogService(VocabularyRepository repo,VocabularyContentService content,Validator validator) {
        this.repo=repo;this.content=content;this.validator=validator;
    }
    private String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(repo.encode(value).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private void validate(Bundle bundle) {
        if(!validator.validate(bundle).isEmpty()) throw invalid("Gói catalog thiếu trường, quá dài hoặc vượt giới hạn.");
        Map<String,Node> nodes=new LinkedHashMap<>();
        for(var node:bundle.nodes()) {
            if(nodes.put(node.id(),node)!=null) throw invalid("Trùng định danh nhóm.");
            if(node.parentId()!=null && !nodes.containsKey(node.parentId())) throw invalid("Nhóm cha phải đứng trước nhóm con.");
            if(node.parentId()==null && !node.id().equals(bundle.collectionId())) throw invalid("Gói phải có một nhóm gốc.");
        }
        if(!nodes.containsKey(bundle.collectionId()) || nodes.get(bundle.collectionId()).parentId()!=null
                || !nodes.get(bundle.collectionId()).hasDeck()) throw invalid("Thiếu bộ catalog gốc.");
        Set<String> keys=new HashSet<>();
        for(var entry:bundle.entries()) {
            if(!keys.add(entry.sourceKey())) throw invalid("Trùng định danh mục nguồn.");
            if(!nodes.containsKey(entry.nodeId()) || !nodes.get(entry.nodeId()).hasDeck()) throw invalid("Mục từ cần nhóm có bộ học.");
            Map<String,Object> classification;
            try {repo.object(entry.sourceJson());classification=repo.object(entry.classificationJson());}
            catch(RuntimeException error) {throw invalid("JSON nguồn hoặc phân loại không hợp lệ.");}
            if(!(classification.get("needs_review") instanceof Boolean) || !classification.containsKey("version")
                    || !classification.containsKey("model") || !classification.containsKey("method")) throw invalid("Thiếu nguồn phân loại.");
        }
    }
    public Map<String,Object> preview(Bundle bundle) {
        validate(bundle);
        var existing=repo.rows("SELECT i.*,n.revision FROM vocab_catalog_items i JOIN vocab_notes n ON n.id=i.note_id WHERE i.collection_id=? ORDER BY i.source_key",bundle.collectionId());
        Map<String,Map<String,Object>> byKey=new HashMap<>();
        for(var row:existing) byKey.put((String)row.get("source_key"),row);
        List<String> added=new ArrayList<>(),changed=new ArrayList<>(),protectedItems=new ArrayList<>();
        for(var entry:bundle.entries()) {
            var old=byKey.remove(entry.sourceKey());
            if(old==null) added.add(entry.sourceKey());
            else {
                if(!repo.encode(entry.note()).equals(old.get("import_note_json")) || !entry.sourceJson().equals(old.get("source_json")) || !entry.classificationJson().equals(old.get("classification_json"))
                        || !entry.nodeId().equals(old.get("node_id")) || !entry.subtopic().equals(old.get("subtopic"))) changed.add(entry.sourceKey());
                if(number(old,"revision")!=number(old,"baseline_revision") || Boolean.TRUE.equals(old.get("admin_override"))) protectedItems.add(entry.sourceKey());
            }
        }
        var state=repo.rows("SELECT id,deck_id,title,parent_id,baseline_revision FROM vocab_catalog_nodes WHERE collection_id=? ORDER BY id",bundle.collectionId());
        var decks=repo.rows("SELECT d.id,d.revision,d.status FROM vocab_decks d JOIN vocab_catalog_nodes g ON g.deck_id=d.id WHERE g.collection_id=? ORDER BY d.id",bundle.collectionId());
        return Map.of("versionHash",hash(bundle),"previewHash",hash(List.of(bundle,existing,state,decks)),
                "added",added,"changed",changed,"missing",new TreeSet<>(byKey.keySet()),"protected",protectedItems,"count",bundle.entries().size());
    }
    @Transactional(timeout=60)
    public Map<String,Object> apply(long user,String key,Apply request) {
        return repo.receipt(user,key,"catalog-import",request,Map.class,()->{
            var bundle=request.bundle();validate(bundle);
            // Same global order for imports by different administrators; personal writes lock user first.
            repo.jdbc().queryForList("SELECT id FROM vocab_decks WHERE owner_user_id IS NULL ORDER BY id FOR UPDATE");
            var preview=preview(bundle);
            String version=(String)preview.get("versionHash");
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_catalog_versions WHERE collection_id=? AND version_hash=?",Integer.class,bundle.collectionId(),version)>0)
                return Map.of("count",bundle.entries().size(),"alreadyApplied",true);
            if(!request.previewHash().equals(preview.get("previewHash"))) throw conflict("Catalog đã thay đổi. Hãy xem trước lại.");
            Map<String,String> decks=new HashMap<>();
            for(var node:bundle.nodes()) {
                var old=repo.rows("SELECT * FROM vocab_catalog_nodes WHERE id=?",node.id());
                if(!old.isEmpty() && !bundle.collectionId().equals(old.get(0).get("collection_id"))) throw conflict("Định danh nhóm thuộc bộ khác.");
                if(old.isEmpty()) {
                    String deck=null;
                    if(node.hasDeck()) deck=(String)content.createDeck(user,"catalog-deck-"+hash(node.id()).substring(0,48),new DeckEdit(node.title(),bundle.attribution(),node.level(),0),true).get("id");
                    repo.jdbc().update("INSERT INTO vocab_catalog_nodes(id,parent_id,collection_id,deck_id,title,sort_order,level_label) VALUES (?,?,?,?,?,?,?)",node.id(),node.parentId(),bundle.collectionId(),deck,node.title(),node.order(),node.level());
                    decks.put(node.id(),deck);
                } else {
                    var row=old.get(0);String deck=(String)row.get("deck_id");decks.put(node.id(),deck);
                    if(!Objects.equals(node.parentId(),row.get("parent_id")) || node.hasDeck()!=(deck!=null)) throw conflict("Cấu trúc nhóm cũ thay đổi; cần migration riêng.");
                    if(deck!=null) {
                        var current=repo.deck(null,deck);
                        if(number(current,"revision")==number(row,"baseline_revision")) {
                            var updated=content.updateDeck(user,"catalog-node-"+hash(List.of(version,node.id())).substring(0,48),deck,
                                    new DeckEdit(node.title(),bundle.attribution(),node.level(),number(current,"revision")),true);
                            repo.jdbc().update("UPDATE vocab_catalog_nodes SET title=?,sort_order=?,level_label=?,baseline_revision=? WHERE id=?",node.title(),node.order(),node.level(),updated.get("revision"),node.id());
                        }
                    }
                }
            }
            Set<String> protectedKeys=new HashSet<>((List<String>)preview.get("protected"));
            for(var entry:bundle.entries()) {
                var old=repo.rows("SELECT * FROM vocab_catalog_items WHERE collection_id=? AND source_key=?",bundle.collectionId(),entry.sourceKey());
                if(old.isEmpty()) {
                    String note=content.insertNote(null,decks.get(entry.nodeId()),entry.note(),"MANUAL",null,null);
                    link(decks.get(bundle.collectionId()),note);
                    repo.jdbc().update("INSERT INTO vocab_catalog_items(collection_id,source_key,note_id,node_id,subtopic,classification_json,source_json,import_note_json) VALUES (?,?,?,?,?,?,?,?)",bundle.collectionId(),entry.sourceKey(),note,entry.nodeId(),entry.subtopic(),entry.classificationJson(),entry.sourceJson(),repo.encode(entry.note()));
                } else if(!protectedKeys.contains(entry.sourceKey())) {
                    var row=old.get(0);String note=(String)row.get("note_id");
                    if(!repo.encode(entry.note()).equals(row.get("import_note_json")) || !entry.sourceJson().equals(row.get("source_json")) || !entry.classificationJson().equals(row.get("classification_json"))
                            || !entry.nodeId().equals(row.get("node_id")) || !entry.subtopic().equals(row.get("subtopic"))) {
                        var edit=entry.note();
                        content.updateNote(user,"catalog-note-"+hash(List.of(version,entry.sourceKey())).substring(0,48),note,
                                new NoteEdit(edit.term(),edit.meaningVi(),edit.partOfSpeech(),edit.exampleEn(),edit.exampleVi(),edit.tags(),edit.alternatives(),number(row,"baseline_revision"),false,edit.usageNotes()),true);
                        String previous=(String)repo.one("SELECT deck_id FROM vocab_catalog_nodes WHERE id=?",row.get("node_id")).get("deck_id");
                        if(!previous.equals(decks.get(entry.nodeId()))) repo.jdbc().update("DELETE FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",previous,note);
                        link(decks.get(entry.nodeId()),note);
                        repo.jdbc().update("UPDATE vocab_catalog_items SET node_id=?,subtopic=?,classification_json=?,source_json=?,import_note_json=?,baseline_revision=baseline_revision+1 WHERE note_id=?",entry.nodeId(),entry.subtopic(),entry.classificationJson(),entry.sourceJson(),repo.encode(entry.note()),note);
                    }
                }
            }
            for(String deck:decks.values()) if(deck!=null && repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=?",Integer.class,deck)>0)
                repo.jdbc().update("UPDATE vocab_decks SET status='PUBLISHED' WHERE id=? AND revision=0",deck);
            repo.jdbc().update("INSERT INTO vocab_catalog_versions(collection_id,version_hash,source_sha256,bundle_json) VALUES (?,?,?,?)",bundle.collectionId(),version,bundle.sourceSha256(),repo.encode(bundle));
            return Map.of("count",bundle.entries().size(),"alreadyApplied",false,"protected",preview.get("protected"),"missing",preview.get("missing"));
        });
    }
    private void link(String deck,String note) {
        if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",Integer.class,deck,note)==0)
            repo.jdbc().update("INSERT INTO vocab_deck_notes(deck_id,note_id) VALUES (?,?)",deck,note);
    }
    public List<Map<String,Object>> nodes(String collection) {
        return repo.rows("SELECT id,title,level_label FROM vocab_catalog_nodes WHERE collection_id=? AND parent_id IS NOT NULL AND deck_id IS NOT NULL ORDER BY level_label,sort_order,id",collection);
    }
    public Map<String,Object> browse(String id,boolean admin) {
        var parent=repo.one("SELECT * FROM vocab_catalog_nodes WHERE id=?",id);
        var root=repo.one("SELECT * FROM vocab_catalog_nodes WHERE id=?",parent.get("collection_id"));
        var rootDeck=repo.deck(null,(String)root.get("deck_id"));
        if(!admin && !"PUBLISHED".equals(rootDeck.get("status"))) throw new ResourceNotFoundException("Không tìm thấy bộ từ.");
        var nodes=repo.rows("SELECT g.*,d.status,(SELECT COUNT(*) FROM vocab_deck_notes x JOIN vocab_notes n ON n.id=x.note_id WHERE x.deck_id=g.deck_id AND n.archived=FALSE) AS note_count FROM vocab_catalog_nodes g LEFT JOIN vocab_decks d ON d.id=g.deck_id WHERE g.collection_id=? ORDER BY g.sort_order,g.id",parent.get("collection_id"));
        List<Map<String,Object>> children=new ArrayList<>();
        for(var node:nodes) if(id.equals(node.get("parent_id"))) {
            if(node.get("deck_id")!=null && !admin && !"PUBLISHED".equals(node.get("status"))) continue;
            if(node.get("deck_id")==null) {
                long count=nodes.stream().filter(n->node.get("id").equals(n.get("parent_id")) && (admin || "PUBLISHED".equals(n.get("status")))).mapToLong(n->number(n,"note_count")).sum();
                node.put("note_count",count);
            }
            children.add(node);
        }
        return Map.of("node",parent,"children",children,"description",rootDeck.get("description"));
    }
    @Transactional(timeout=20)
    public Map<String,Object> metadata(long user,String key,String noteId,Metadata value) {
        return repo.receipt(user,key,"catalog-metadata/"+noteId,value,Map.class,()->{
            var note=repo.note(null,noteId);
            if(number(note,"revision")!=value.revision()) throw conflict("Mục từ đã thay đổi.");
            var item=repo.one("SELECT * FROM vocab_catalog_items WHERE note_id=?",noteId);
            var node=repo.one("SELECT * FROM vocab_catalog_nodes WHERE id=? AND collection_id=? AND deck_id IS NOT NULL AND parent_id IS NOT NULL",value.nodeId(),item.get("collection_id"));
            var old=repo.one("SELECT * FROM vocab_catalog_nodes WHERE id=?",item.get("node_id"));
            repo.jdbc().update("DELETE FROM vocab_deck_notes WHERE deck_id=? AND note_id=?",old.get("deck_id"),noteId);
            link((String)node.get("deck_id"),noteId);
            var classification=repo.object((String)item.get("classification_json"));
            classification.put("level",node.get("level_label"));classification.put("needs_review",false);classification.put("method","ADMIN");
            repo.jdbc().update("UPDATE vocab_catalog_items SET node_id=?,subtopic=?,classification_json=?,admin_override=TRUE WHERE note_id=?",value.nodeId(),value.subtopic(),repo.encode(classification),noteId);
            repo.jdbc().update("UPDATE vocab_notes SET revision=revision+1 WHERE id=?",noteId);
            return Map.of("saved",true);
        });
    }
}
