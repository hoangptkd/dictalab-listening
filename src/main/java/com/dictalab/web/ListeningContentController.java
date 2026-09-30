package com.dictalab.web;

import com.dictalab.service.ListeningComprehensionService;
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
public class ListeningContentController {
    private final ListeningComprehensionService service;

    public ListeningContentController(ListeningComprehensionService service) {
        this.service = service;
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

    @GetMapping("/sources")
    public List<Map<String, Object>> sources() { return service.adminSources(); }

    @PostMapping("/units/manual")
    public Map<String, Object> manual(@Valid @RequestBody ManualCreate body) {
        return service.createManualUnit(body.lessonId(), body.audioUrl(), body.title(), body.content());
    }

    @PostMapping("/units/{id}/copy")
    public Map<String, Object> copy(@PathVariable long id) { return service.copyDraft(id); }

    @PutMapping("/units/{id}/retire")
    public Map<String, Object> retire(@PathVariable long id) { return service.retireUnit(id); }

    @PostMapping("/units")
    public Map<String, Object> create(@Valid @RequestBody Create body) {
        return service.createUnit(body.lessonId(), body.audioUrl(), body.durationSeconds());
    }

    @PutMapping("/units/{id}")
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody Draft body) {
        return service.updateDraft(id, body.title(), body.content());
    }

    @PutMapping("/units/{id}/publication")
    public Map<String, Object> publish(@PathVariable long id) {
        return service.publishUnit(id);
    }
}
