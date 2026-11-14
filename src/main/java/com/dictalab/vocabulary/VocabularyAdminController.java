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
    public VocabularyAdminController(VocabularyContentService content,VocabularyStudyService study,ContextExerciseService contexts) { this.content=content; this.study=study; this.contexts=contexts; }
    @ModelAttribute public void enabled() { study.requireEnabled(); }
    @GetMapping("/decks") public Page<Map<String,Object>> list(@AuthenticationPrincipal DictaLabPrincipal user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String tag) { return content.decks(user.id(),false,true,page,q,tag); }
    @GetMapping("/tags") public List<String> tags(@AuthenticationPrincipal DictaLabPrincipal user) { return content.tags(user.id(),false,true); }
    @GetMapping("/decks/{id}") public Map<String,Object> detail(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id,@RequestParam(defaultValue="1") int page) { return content.detail(user.id(),id,false,true,page); }
    @PostMapping("/decks") public Map<String,Object> create(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeckEdit value) { return content.createDeck(user.id(),key,value,true); }
    @PatchMapping("/decks/{id}") public Map<String,Object> edit(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody DeckEdit value) { return content.updateDeck(user.id(),key,id,value,true); }
    @PostMapping("/decks/{id}/notes") public Map<String,Object> addNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.addNote(user.id(),key,id,value,true); }
    @PatchMapping("/notes/{id}") public Map<String,Object> editNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.updateNote(user.id(),key,id,value,true); }
    @PostMapping("/decks/{id}/publication") public Map<String,Object> publish(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Publication value) { return content.publish(user.id(),key,id,value); }
    @GetMapping("/contexts") public Page<Map<String,Object>> contexts(@RequestParam(defaultValue="1") int page) { return contexts.list(page); }
    @PostMapping("/contexts") public Map<String,Object> context(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ContextEdit value) { return contexts.save(user.id(),key,null,value); }
    @PatchMapping("/contexts/{id}") public Map<String,Object> editContext(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody ContextEdit value) { return contexts.save(user.id(),key,id,value); }
}
