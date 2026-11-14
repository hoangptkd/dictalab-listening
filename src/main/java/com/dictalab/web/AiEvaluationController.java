package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AiEvaluationStudio;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** SecurityConfig protects the entire admin namespace, including CSRF on mutations. */
@RestController
@Profile("local")
@RequestMapping("/api/admin/ai-evaluation")
public class AiEvaluationController {
    private final AiEvaluationStudio studio;
    public AiEvaluationController(AiEvaluationStudio studio) { this.studio = studio; }
    @ModelAttribute void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping public JsonNode snapshot() { return studio.snapshot(); }
    @PostMapping("/versions") public JsonNode version(@RequestBody JsonNode body, @AuthenticationPrincipal DictaLabPrincipal actor) {
        return studio.createVersion(body, actor.id());
    }
    @PostMapping("/runs") public JsonNode start(@RequestBody JsonNode body, @AuthenticationPrincipal DictaLabPrincipal actor) {
        return studio.start(body, actor.id());
    }
    @GetMapping("/runs/{id}") public JsonNode detail(@PathVariable String id) { return studio.detail(id); }
    @PostMapping("/runs/{id}/stop") public JsonNode stop(@PathVariable String id) { return studio.stop(id); }
    @GetMapping("/compare") public JsonNode compare(@RequestParam String left, @RequestParam String right) { return studio.compare(left, right); }
}
