package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.TrainingStudio;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/training")
public class TrainingStudioController {
    private final TrainingStudio studio;
    public TrainingStudioController(TrainingStudio studio) { this.studio = studio; }

    @ModelAttribute
    void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    public JsonNode snapshot() { return studio.request("snapshot", null, null, null); }

    @GetMapping("/runs/{id}")
    public JsonNode detail(@PathVariable String id) { return studio.request("detail", id, null, null); }

    @PostMapping("/runs")
    public JsonNode create(@RequestBody Map<String, Object> body,
                           @AuthenticationPrincipal DictaLabPrincipal actor) {
        return studio.request("create", null, body, actor.id());
    }

    @PostMapping("/runs/{id}/{action:start|resume|pause|stop}")
    public JsonNode control(@PathVariable String id, @PathVariable String action,
                            @RequestBody Map<String, Object> body,
                            @AuthenticationPrincipal DictaLabPrincipal actor) {
        return studio.request(action, id, body, actor.id());
    }
}
