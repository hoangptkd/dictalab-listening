package com.dictalab.vocabulary;

import com.dictalab.security.DictaLabPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"dictalab.vocabulary.enabled=true","dictalab.operational-limits-enabled=true"})
@AutoConfigureMockMvc
class VocabularyCatalogIntegrationTest {
    @Autowired VocabularyCatalogService catalog;
    @Autowired VocabularyContentService content;
    @Autowired VocabularyRepository repo;
    @Autowired VocabularyStudyService study;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    String key() {return UUID.randomUUID().toString();}
    long learner() {
        String email=key()+"@vocabulary.test";
        db.update("INSERT INTO app_users(email,display_name,password_hash,created_at,updated_at) VALUES (?,'Vocabulary','unused',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",email);
        return db.queryForObject("SELECT id FROM app_users WHERE email=?",Long.class,email);
    }
    VocabularyCatalogService.Bundle bundle() throws Exception {
        return repo.mapper().readValue(new ClassPathResource("vocabulary/daily-conversation/bundle.json").getInputStream().readAllBytes(),VocabularyCatalogService.Bundle.class);
    }
    @Test void fullBundleImportsRepeatablyAndNavigationDoesNotMixLevels() throws Exception {
        long owner=learner();var bundle=bundle();
        var preview=catalog.preview(bundle);
        assertThat(preview.get("count")).isEqualTo(719);
        catalog.apply(owner,key(),new VocabularyCatalogService.Apply(bundle,(String)preview.get("previewHash")));
        var after=catalog.preview(bundle);
        assertThat((List<?>)after.get("added")).isEmpty();assertThat((List<?>)after.get("changed")).isEmpty();
        catalog.apply(owner,key(),new VocabularyCatalogService.Apply(bundle,(String)after.get("previewHash")));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_catalog_items WHERE collection_id=?",Integer.class,bundle.collectionId())).isEqualTo(719);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_catalog_versions WHERE collection_id=?",Integer.class,bundle.collectionId())).isEqualTo(1);
        @SuppressWarnings("unchecked") var levels=(List<Map<String,Object>>)catalog.browse(bundle.collectionId(),false).get("children");
        assertThat(levels.stream().map(n->n.get("title"))).containsExactly("A1","A2","B1","B2","C1","C2");
        assertThat(levels.stream().mapToLong(n->((Number)n.get("note_count")).longValue()).sum()).isEqualTo(719);
        String level=(String)levels.get(0).get("id");
        @SuppressWarnings("unchecked") var topics=(List<Map<String,Object>>)catalog.browse(level,false).get("children");
        assertThat(topics).allMatch(n->"A1".equals(n.get("level_label")));
        var topic=topics.get(0);String source=(String)topic.get("deck_id");long student=learner();
        var filtered=content.detail(student,source,true,false,1,"Chào hỏi");
        assertThat(((VocabularyDtos.Page<?>)filtered.get("notes")).items()).isNotEmpty();
        assertThat(((VocabularyDtos.Page<?>)content.detail(student,source,true,false,1,"no-matching-subtopic").get("notes")).items()).isEmpty();
        String personal=(String)content.enroll(student,key(),source).get("id");
        var session=study.start(student,key(),new SessionStart("SRS",personal));
        assertThat(session.items()).isNotEmpty();
        var snapshots=repo.rows("SELECT * FROM vocab_session_items WHERE session_id=? ORDER BY id",session.id());
        var schedules=repo.rows("SELECT * FROM vocab_cards WHERE user_id=? ORDER BY id",student);
        String note=db.queryForObject("SELECT id FROM vocab_notes WHERE owner_user_id=? LIMIT 1",String.class,student);
        content.updateNote(student,key(),note,new NoteEdit("personal edit","nghĩa riêng","","","","",List.of(),0,false),false);
        var entry=bundle.entries().get(0);
        var target=bundle.nodes().stream().filter(n->n.hasDeck() && n.level().equals("B1")).findFirst().orElseThrow();
        String systemNote=db.queryForObject("SELECT note_id FROM vocab_catalog_items WHERE source_key=?",String.class,entry.sourceKey());
        catalog.metadata(owner,key(),systemNote,new VocabularyCatalogService.Metadata(target.id(),"Đã biên tập",0));
        var changedEntries=new ArrayList<>(bundle.entries());
        changedEntries.set(0,new VocabularyCatalogService.Entry(entry.sourceKey(),entry.nodeId(),entry.subtopic(),entry.note(),entry.sourceJson()+" ",entry.classificationJson()));
        var second=changedEntries.get(1);var edit=second.note();
        changedEntries.set(1,new VocabularyCatalogService.Entry(second.sourceKey(),second.nodeId(),second.subtopic(),new NoteEdit(edit.term(),edit.meaningVi(),edit.partOfSpeech(),edit.exampleEn(),edit.exampleVi(),edit.tags(),edit.alternatives(),0,false,"Updated source usage"),second.sourceJson()+" ",second.classificationJson()));
        var changed=new VocabularyCatalogService.Bundle(bundle.collectionId(),bundle.nodes(),changedEntries,bundle.sourceSha256(),bundle.attribution());
        var update=catalog.preview(changed);assertThat((List<String>)update.get("protected")).contains(entry.sourceKey());
        catalog.apply(owner,key(),new VocabularyCatalogService.Apply(changed,(String)update.get("previewHash")));
        assertThat(repo.one("SELECT * FROM vocab_catalog_items WHERE note_id=?",systemNote)).containsEntry("node_id",target.id());
        assertThat(repo.note(student,note)).containsEntry("term","personal edit");
        assertThat(repo.rows("SELECT * FROM vocab_cards WHERE user_id=? ORDER BY id",student)).isEqualTo(schedules);
        assertThat(repo.rows("SELECT * FROM vocab_session_items WHERE session_id=? ORDER BY id",session.id())).isEqualTo(snapshots);
        String updatedNote=db.queryForObject("SELECT note_id FROM vocab_catalog_items WHERE source_key=?",String.class,second.sourceKey());
        assertThat(repo.note(null,updatedNote).get("usage_notes")).isEqualTo("Updated source usage");
        var withoutLast=new ArrayList<>(changedEntries);withoutLast.remove(withoutLast.size()-1);
        var missing=new VocabularyCatalogService.Bundle(bundle.collectionId(),bundle.nodes(),withoutLast,bundle.sourceSha256(),bundle.attribution());
        var missingPreview=catalog.preview(missing);assertThat((Collection<?>)missingPreview.get("missing")).hasSize(1);
        catalog.apply(owner,key(),new VocabularyCatalogService.Apply(missing,(String)missingPreview.get("previewHash")));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_catalog_items WHERE collection_id=?",Integer.class,bundle.collectionId())).isEqualTo(719);
        var principal=new DictaLabPrincipal(student,db.queryForObject("SELECT email FROM app_users WHERE id=?",String.class,student),"Vocabulary","unused","USER",true);
        mvc.perform(post("/api/admin/vocabulary/catalog-imports/preview").with(user(principal)).with(csrf()).contentType("application/json").content(repo.encode(bundle)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/vocabulary/catalog-imports/apply").with(user(principal)).with(csrf()).header("Idempotency-Key",key()).contentType("application/json").content(repo.encode(new VocabularyCatalogService.Apply(bundle,"x"))))
                .andExpect(status().isForbidden());
        db.update("UPDATE vocab_decks SET status='DRAFT' WHERE id=?",source);
        @SuppressWarnings("unchecked") var visible=(List<Map<String,Object>>)catalog.browse(level,false).get("children");
        assertThat(visible).noneMatch(n->source.equals(n.get("deck_id")));
        assertThat(content.detail(student,personal,false,false,1)).isNotEmpty();
        String rootDeck=(String)repo.one("SELECT deck_id FROM vocab_catalog_nodes WHERE id=?",bundle.collectionId()).get("deck_id");
        int hidden=db.queryForObject("SELECT COUNT(*) FROM vocab_deck_notes WHERE deck_id=?",Integer.class,source);
        assertThat(((Map<?,?>)content.detail(student,rootDeck,true,false,1).get("deck")).get("note_count")).isEqualTo(719-hidden);
        db.update("UPDATE vocab_decks SET status='DRAFT' WHERE id=?",rootDeck);
        String otherTopic=(String)topics.get(1).get("deck_id");
        assertThatThrownBy(()->content.detail(student,otherTopic,true,false,1)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
        assertThatThrownBy(()->content.enroll(student,key(),otherTopic)).isInstanceOf(com.dictalab.exception.ResourceNotFoundException.class);
    }
    @Test void invalidOrStaleBundleDoesNotChangeTheCatalog() throws Exception {
        var original=bundle();long owner=learner();
        var invalid=new VocabularyCatalogService.Bundle("missing",original.nodes(),original.entries(),original.sourceSha256(),original.attribution());
        int before=db.queryForObject("SELECT COUNT(*) FROM vocab_notes",Integer.class);
        assertThatThrownBy(()->catalog.preview(invalid)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var changed=new VocabularyCatalogService.Bundle(original.collectionId(),original.nodes(),original.entries(),original.sourceSha256(),original.attribution()+" updated");
        assertThatThrownBy(()->catalog.apply(owner,key(),new VocabularyCatalogService.Apply(changed,"stale")))
                .isInstanceOf(com.dictalab.exception.InvalidPracticeStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM vocab_notes",Integer.class)).isEqualTo(before);
    }
}
