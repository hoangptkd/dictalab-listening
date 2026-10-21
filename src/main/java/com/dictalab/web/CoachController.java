package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.CoachAnalysis;
import com.dictalab.service.CoachService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CoachController {
    private final CoachService coach;

    public CoachController(CoachService coach) { this.coach = coach; }

    @GetMapping("/practice-sessions/{sessionId}/coach")
    public CoachAnalysis.Report report(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String sessionId) {
        return coach.report(user.id(), sessionId, false);
    }

    @PostMapping("/practice-sessions/{sessionId}/coach")
    public CoachAnalysis.Report generate(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String sessionId) {
        return coach.report(user.id(), sessionId, true);
    }

    public record Event(
            @NotBlank @Pattern(regexp = "coach_result_viewed|coach_details_opened|coach_quick_review_started|coach_quick_review_completed") String name,
            @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String reviewRunId) {}

    @PostMapping("/practice-sessions/{sessionId}/coach/events")
    public void event(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String sessionId,
                      @Valid @RequestBody Event event) {
        if (event.name().startsWith("coach_quick_review_") && event.reviewRunId() == null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        coach.recordEvent(user.id(), sessionId, event.name(), event.reviewRunId());
    }

    public record SaveError(@NotBlank String sessionId, @Pattern(regexp = "[0-9]+:[0-9]+") @NotBlank String itemId) {}

    @PostMapping("/error-bank")
    public void save(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody SaveError request) {
        coach.saveError(user.id(), request.sessionId(), request.itemId());
    }

    @GetMapping("/error-bank")
    public List<CoachService.BankEntry> bank(@AuthenticationPrincipal DictaLabPrincipal user) {
        return coach.bank(user.id());
    }
}
