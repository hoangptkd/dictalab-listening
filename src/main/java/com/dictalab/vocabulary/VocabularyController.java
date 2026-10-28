package com.dictalab.vocabulary;

import com.dictalab.security.DictaLabPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import static com.dictalab.vocabulary.VocabularyDtos.*;

@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyController {
    private final VocabularyContentService content;
    private final VocabularyStudyService study;
    private final VocabularySourceService sources;
    private final VocabularyImportService imports;
    public VocabularyController(VocabularyContentService content,VocabularyStudyService study,VocabularySourceService sources,VocabularyImportService imports) {
        this.content=content; this.study=study; this.sources=sources; this.imports=imports;
    }
    @ModelAttribute public void enabled() { study.requireEnabled(); }
    @GetMapping("/summary") public Map<String,Object> summary(@AuthenticationPrincipal DictaLabPrincipal user) { return study.summary(user.id()); }
    @GetMapping("/decks") public Page<Map<String,Object>> decks(@AuthenticationPrincipal DictaLabPrincipal user,@RequestParam(defaultValue="1") int page) { return content.decks(user.id(),false,false,page); }
    @GetMapping("/catalog/decks") public Page<Map<String,Object>> catalog(@AuthenticationPrincipal DictaLabPrincipal user,@RequestParam(defaultValue="1") int page) { return content.decks(user.id(),true,false,page); }
    @GetMapping("/decks/{id}") public Map<String,Object> deck(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id,@RequestParam(defaultValue="1") int page) { return content.detail(user.id(),id,false,false,page); }
    @GetMapping("/catalog/decks/{id}") public Map<String,Object> catalogDeck(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id,@RequestParam(defaultValue="1") int page) { return content.detail(user.id(),id,true,false,page); }
    @PostMapping("/decks") public Map<String,Object> create(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeckEdit value) { return content.createDeck(user.id(),key,value,false); }
    @PatchMapping("/decks/{id}") public Map<String,Object> editDeck(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody DeckEdit value) { return content.updateDeck(user.id(),key,id,value,false); }
    public record Copy(@jakarta.validation.constraints.NotBlank String deckId) {}
    @PostMapping("/catalog/decks/{id}/add") public Map<String,Object> copy(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Copy value) { return content.copy(user.id(),key,id,value.deckId()); }
    @PostMapping("/decks/{id}/notes") public Map<String,Object> addNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.addNote(user.id(),key,id,value,false); }
    @GetMapping("/notes/{id}") public Map<String,Object> note(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id) { return content.note(user.id(),id); }
    @PatchMapping("/notes/{id}") public Map<String,Object> editNote(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody NoteEdit value) { return content.updateNote(user.id(),key,id,value,false); }
    @PutMapping("/decks/{id}/notes/{noteId}") public Map<String,Object> membership(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable String noteId) { return content.membership(user.id(),key,id,noteId,false); }
    @DeleteMapping("/decks/{id}/notes/{noteId}") public Map<String,Object> remove(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@PathVariable String noteId) { return content.membership(user.id(),key,id,noteId,true); }
    @PatchMapping("/cards/{id}") public Map<String,Object> suspend(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Suspension value) { return content.suspend(user.id(),key,id,value); }
    @GetMapping("/suggestions") public Page<Map<String,Object>> suggestions(@AuthenticationPrincipal DictaLabPrincipal user,@RequestParam(defaultValue="1") int page) { return sources.suggestions(user.id(),page); }
    @PostMapping("/decks/{id}/from-practice") public Map<String,Object> fromPractice(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody SourceNote value) { return sources.save(user.id(),key,id,value); }
    @PostMapping("/sessions") public Session start(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SessionStart value) { return study.start(user.id(),key,value); }
    @GetMapping("/sessions/{id}") public Session session(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id) { return study.session(user.id(),id); }
    @PostMapping("/sessions/{id}/abandon") public Map<String,Object> abandon(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id) { return study.abandon(user.id(),key,id); }
    @PostMapping("/session-items/{id}/answer") public Map<String,Object> answer(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody Answer value) { return study.answer(user.id(),key,id,value,false); }
    @PostMapping("/session-items/{id}/reveal") public Map<String,Object> reveal(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id) { return study.answer(user.id(),key,id,new Answer(""),true); }
    @PostMapping("/session-items/{id}/grade") public Map<String,Object> grade(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id,@Valid @RequestBody GradeRequest value) { return study.grade(user.id(),key,id,value); }
    @PostMapping("/imports/preview") public Map<String,Object> preview(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ImportCsv value) { return imports.preview(user.id(),key,value); }
    @PostMapping("/imports/{id}/confirm") public Map<String,Object> confirm(@AuthenticationPrincipal DictaLabPrincipal user,@RequestHeader("Idempotency-Key") String key,@PathVariable String id) { return imports.confirm(user.id(),key,id); }
    @GetMapping("/decks/{id}/export") public ResponseEntity<String> export(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"vocabulary.csv\"").body(imports.export(user.id(),id));
    }
}
