package com.dictalab.vocabulary;

import com.dictalab.security.DictaLabPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;

@RestController
@RequestMapping("/api/admin/vocabulary")
public class VocabularyAdminController {
    private final VocabularyContentService content;
    private final VocabularyStudyService study;
    private final ContextExerciseService contexts;
    private final VocabularyCatalogService catalog;
    public VocabularyAdminController(VocabularyContentService content,VocabularyStudyService study,ContextExerciseService contexts,VocabularyCatalogService catalog) { this.content=content; this.study=study; this.contexts=contexts;this.catalog=catalog; }
    @ModelAttribute public void enabled() { study.requireEnabled(); }
    @GetMapping("/catalog/groups/{id}") public Map<String,Object> group(@PathVariable String id) { return catalog.browse(id,true); }
    @GetMapping("/catalog/collections/{id}/nodes") public List<Map<String,Object>> nodes(@PathVariable String id) { return catalog.nodes(id); }
    @PostMapping("/catalog-imports/preview") public Map<String,Object> previewCatalog(@Valid @RequestBody VocabularyCatalogService.Bundle bundle) { return catalog.preview(bundle); }
    @PostMapping("/catalog-imports/apply") public Map<String,Object> applyCatalog(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody VocabularyCatalogService.Apply request) { return catalog.apply(user.id(),key,request); }
    @PatchMapping("/catalog-items/{id}/metadata") public Map<String,Object> metadata(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody VocabularyCatalogService.Metadata value) { return catalog.metadata(user.id(),key,id,value); }
    @GetMapping("/decks") public Page<Map<String,Object>> list(@AuthenticationPrincipal DictaLabPrincipal user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String tag) { return content.decks(user.id(),false,true,page,q,tag); }
    @GetMapping("/tags") public List<String> tags(@AuthenticationPrincipal DictaLabPrincipal user) { return content.tags(user.id(),false,true); }
    @GetMapping("/decks/{id}") public Map<String,Object> detail(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String q) { return content.detail(user.id(),id,false,true,page,q); }
    @PostMapping("/decks") public Map<String,Object> create(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeckEdit value) { return content.createDeck(user.id(),key,value,true); }
    @PatchMapping("/decks/{id}") public Map<String,Object> edit(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody DeckEdit value) { return content.updateDeck(user.id(),key,id,value,true); }
    @PostMapping("/decks/{id}/notes") public Map<String,Object> addNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.addNote(user.id(),key,id,value,true); }
    @PatchMapping("/notes/{id}") public Map<String,Object> editNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.updateNote(user.id(),key,id,value,true); }
    @PostMapping("/decks/{id}/publication") public Map<String,Object> publish(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Publication value) { return content.publish(user.id(),key,id,value); }
    @GetMapping("/contexts") public Page<Map<String,Object>> contexts(@RequestParam(defaultValue="1") int page) { return contexts.list(page); }
    @PostMapping("/contexts") public Map<String,Object> context(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ContextEdit value) { return contexts.save(user.id(),key,null,value); }
    @PatchMapping("/contexts/{id}") public Map<String,Object> editContext(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody ContextEdit value) { return contexts.save(user.id(),key,id,value); }
}
