package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversation")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service = service; }
    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
    public record Create(@NotBlank @Size(max=30) String scenario, @NotBlank @Size(max=10) String difficulty) {}
    public record Next(@Min(0) @Max(4) int turnNumber) {}
    public record Answer(@Min(1) @Max(4) int turnNumber, @NotNull UUID requestId, @NotBlank @Size(max=1500) String answerVi) {}
    public record Help(@Min(1) @Max(4) int turnNumber, @NotBlank @Size(max=20) String kind) {}
    public record Play(@Min(1) @Max(4) int turnNumber, @NotBlank @Size(max=12) String variant, boolean slow) {}

    @GetMapping public Map<String,Object> catalog(@AuthenticationPrincipal DictaLabPrincipal user) { return service.catalog(user.id()); }
    @PostMapping("/sessions") public ObjectNode create(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody Create input) {
        return service.create(user.id(), input.scenario(), input.difficulty());
    }
    @GetMapping("/sessions/{id}") public ObjectNode get(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable UUID id) {
        return service.get(user.id(), id.toString());
    }
    @PostMapping("/sessions/{id}/next") public ObjectNode next(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable UUID id, @Valid @RequestBody Next input) {
        return service.next(user.id(), id.toString(), input.turnNumber());
    }
    @PostMapping("/sessions/{id}/answer") public ObjectNode answer(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable UUID id, @Valid @RequestBody Answer input) {
        return service.answer(user.id(), id.toString(), input.turnNumber(), input.requestId(), input.answerVi());
    }
    @PostMapping("/sessions/{id}/help") public ObjectNode help(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable UUID id, @Valid @RequestBody Help input) {
        return service.help(user.id(), id.toString(), input.turnNumber(), input.kind());
    }
    @PostMapping("/sessions/{id}/play") public ObjectNode play(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable UUID id, @Valid @RequestBody Play input) {
        return service.play(user.id(), id.toString(), input.turnNumber(), input.variant(), input.slow());
    }
    @GetMapping("/sessions/{id}/audio-status") public Map<String,String> audioStatus(@AuthenticationPrincipal DictaLabPrincipal user,
            @PathVariable UUID id, @RequestParam int turnNumber, @RequestParam String variant) {
        return service.audioStatus(user.id(), id.toString(), turnNumber, variant);
    }
    @GetMapping("/sessions/{id}/audio/{turn}/{variant}") public ResponseEntity<?> audio(@AuthenticationPrincipal DictaLabPrincipal user,
            @PathVariable UUID id, @PathVariable int turn, @PathVariable String variant,
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if(service.serveS3Audio(user.id(),id.toString(),turn,variant,request,response)) return null;
        var location = service.audioLocation(user.id(), id.toString(), turn, variant);
        if (location.isPresent()) return ResponseEntity.status(307).location(location.get())
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("audio/wav"))
                .body(service.audio(user.id(), id.toString(), turn, variant));
    }
}
