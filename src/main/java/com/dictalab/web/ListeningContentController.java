package com.dictalab.web;

import com.dictalab.service.ListeningComprehensionService;
import com.dictalab.service.AdminQueryService;
import com.dictalab.security.DictaLabPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/admin/listening")
@Transactional
public class ListeningContentController {
    private final ListeningComprehensionService service;
    private final AdminQueryService queries;

    public ListeningContentController(ListeningComprehensionService service, AdminQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    public record Create(@NotNull @Positive Long lessonId, @NotBlank @Size(max = 500) String audioUrl,
                         double durationSeconds) {}
    public record Draft(@NotBlank String title, @NotNull JsonNode content) {}
    public record ManualCreate(@NotNull @Positive Long lessonId, @NotBlank String audioUrl,
                               @NotBlank String title, @NotNull JsonNode content) {}

    @GetMapping("/units")
    public List<Map<String, Object>> units() {
        return service.adminUnits();
    }
    @GetMapping("/units/page")
    public AdminQueryService.Page page(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String level,
            @RequestParam(defaultValue="") String status, @RequestParam(required=false) Long lessonId,
            @RequestParam(defaultValue="updated") String sort, @RequestParam(defaultValue="0") int page) {
        return queries.units(q,level,status,lessonId,sort,page);
    }
    @GetMapping("/units/{id}")
    public Map<String,Object> detail(@PathVariable long id) { return queries.unit(id); }
    @GetMapping("/units/{id}/events")
    public List<Map<String,Object>> events(@PathVariable long id) { queries.unit(id); return queries.events("LISTENING",id); }

    @GetMapping("/sources")
    public List<Map<String, Object>> sources() { return service.adminSources(); }

    @PostMapping("/units/manual")
    public Map<String, Object> manual(@Valid @RequestBody ManualCreate body, @AuthenticationPrincipal DictaLabPrincipal actor) {
        var result=service.createManualUnit(body.lessonId(), body.audioUrl(), body.title(), body.content());
        queries.event(actor.id(),"LISTENING",((Number)result.get("id")).longValue(),"CREATE_MANUAL_DRAFT",body.title()); return result;
    }

    @PostMapping("/units/{id}/copy")
    public Map<String, Object> copy(@PathVariable long id, @AuthenticationPrincipal DictaLabPrincipal actor) {
        var result=service.copyDraft(id);
        queries.event(actor.id(),"LISTENING",((Number)result.get("id")).longValue(),"COPY_DRAFT","Từ bản #"+id); return result;
    }

    @PutMapping("/units/{id}/retire")
    public Map<String, Object> retire(@PathVariable long id, @RequestHeader(value="X-Edit-Version",required=false) Long revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyUnit(id,revision); var result=service.retireUnit(id);
        queries.event(actor.id(),"LISTENING",id,"RETIRE",null); return result;
    }

    @PostMapping("/units")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Map<String, Object> create(@Valid @RequestBody Create body, @AuthenticationPrincipal DictaLabPrincipal actor) {
        var result=service.createUnit(body.lessonId(), body.audioUrl(), body.durationSeconds());
        queries.event(actor.id(),"LISTENING",((Number)result.get("id")).longValue(),"CREATE_AI_DRAFT",null); return result;
    }

    @PutMapping("/units/{id}")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody Draft body,
            @RequestHeader(value="X-Edit-Version",required=false) Long revision, @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyUnit(id,revision); var result=service.updateDraft(id, body.title(), body.content());
        queries.event(actor.id(),"LISTENING",id,"UPDATE_DRAFT",body.title()); return result;
    }

    @PutMapping("/units/{id}/publication")
    public Map<String, Object> publish(@PathVariable long id, @RequestHeader(value="X-Edit-Version",required=false) Long revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyUnit(id,revision); var result=service.publishUnit(id);
        queries.event(actor.id(),"LISTENING",id,"PUBLISH","Thay thế bản đã phát hành cùng audio; giữ lịch sử học."); return result;
    }
}
