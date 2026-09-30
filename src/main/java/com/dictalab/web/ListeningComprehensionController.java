package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ListeningComprehensionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/listening")
public class ListeningComprehensionController {
    private final ListeningComprehensionService service;

    public ListeningComprehensionController(ListeningComprehensionService service) {
        this.service = service;
    }

    public record Start(@NotNull @Positive Long unitId, @NotBlank String mode, boolean heardBefore) {}
    public record Recall(@NotNull @Size(max = 6000) String text) {}
    public record Answers(@NotNull Map<String, Integer> answers) {}
    public record Playback(@NotBlank String token) {}
    public record Mark(@NotNull @Positive Long sentenceId, @NotBlank String type, @Size(max = 500) String note) {}

    @GetMapping("/units")
    public List<Map<String, Object>> units(@AuthenticationPrincipal DictaLabPrincipal user) {
        return service.units(user.id());
    }

    @PostMapping("/sessions")
    public Map<String, Object> start(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody Start body) {
        return service.start(user.id(), body.unitId(), body.mode(), body.heardBefore());
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> state(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id) {
        return service.state(user.id(), id);
    }

    @PostMapping("/sessions/{id}/play")
    public Map<String, Object> play(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id) {
        return service.play(user.id(), id);
    }

    @PostMapping("/sessions/{id}/play/complete")
    public Map<String, Object> finishAudio(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                           @Valid @RequestBody Playback body) {
        return service.finishAudio(user.id(), id, body.token());
    }

    @PutMapping("/sessions/{id}/recall")
    public Map<String, Object> saveRecall(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                           @Valid @RequestBody Recall body) {
        return service.saveRecall(user.id(), id, body.text());
    }

    @PostMapping("/sessions/{id}/recall")
    public Map<String, Object> submitRecall(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                             @Valid @RequestBody Recall body) {
        return service.submitRecall(user.id(), id, body.text());
    }

    @PostMapping("/sessions/{id}/answers")
    public Map<String, Object> submitAnswers(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                              @Valid @RequestBody Answers body) {
        return service.submitAnswers(user.id(), id, body.answers());
    }

    @PutMapping("/sessions/{id}/answers")
    public Map<String, Object> saveAnswers(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                            @Valid @RequestBody Answers body) {
        return service.saveAnswers(user.id(), id, body.answers());
    }

    @PostMapping("/sessions/{id}/grading/{pass}/retry")
    public Map<String, Object> retryGrading(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                             @PathVariable int pass) {
        return service.retryGrading(user.id(), id, pass);
    }

    @GetMapping("/sessions/{id}/pass-1-result")
    public Map<String, Object> passOneResult(@AuthenticationPrincipal DictaLabPrincipal user,
                                             @PathVariable String id) {
        return service.passOneResult(user.id(), id);
    }

    @PostMapping("/sessions/{id}/second-pass")
    public Map<String, Object> startSecondPass(@AuthenticationPrincipal DictaLabPrincipal user,
                                               @PathVariable String id) {
        return service.startSecondPass(user.id(), id);
    }

    @PostMapping("/sessions/{id}/finish-after-pass-1")
    public Map<String, Object> finishAfterFirstPass(@AuthenticationPrincipal DictaLabPrincipal user,
                                                    @PathVariable String id) {
        return service.finishAfterFirstPass(user.id(), id);
    }

    @PostMapping("/sessions/{id}/abandon")
    public Map<String, Object> abandon(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id) {
        return service.abandon(user.id(), id);
    }

    @GetMapping("/sessions/{id}/report")
    public Map<String, Object> report(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id) {
        return service.report(user.id(), id);
    }

    @GetMapping("/sessions/{id}/review")
    public Map<String, Object> review(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id) {
        return service.review(user.id(), id);
    }

    @PostMapping("/sessions/{id}/review-marks")
    public Map<String, Object> mark(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String id,
                                    @Valid @RequestBody Mark body) {
        return service.addMark(user.id(), id, body.sentenceId(), body.type(), body.note());
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@AuthenticationPrincipal DictaLabPrincipal user) {
        return service.history(user.id());
    }
}
