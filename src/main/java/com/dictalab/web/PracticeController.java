package com.dictalab.web;

import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.PracticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/practice-sessions")
@Validated
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping
    public ApiDtos.PracticeStartResponse start(@AuthenticationPrincipal DictaLabPrincipal principal,
                                                @Valid @RequestBody ApiDtos.StartSessionRequest request) {
        return practiceService.start(principal.id(), request);
    }

    @GetMapping("/{sessionId}")
    public ApiDtos.PracticeStartResponse current(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId) {
        return practiceService.current(principal.id(), sessionId);
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/replay-events")
    public ApiDtos.SentenceState replay(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.ReplayRequest request) {
        return practiceService.replay(principal.id(), sessionId, sentenceId, request);
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/words/{wordIndex}/checks")
    public ApiDtos.SentenceState checkWord(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @PathVariable @Min(0) int wordIndex,
            @Valid @RequestBody ApiDtos.CheckWordRequest request) {
        return practiceService.checkWord(principal.id(), sessionId, sentenceId, wordIndex, request);
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/hints")
    public ApiDtos.SentenceState hint(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.HintRequest request) {
        return practiceService.useHint(principal.id(), sessionId, sentenceId, request);
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/classifications")
    public ApiDtos.SentenceState classify(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.ClassifyWordRequest request) {
        return practiceService.classifyWord(principal.id(), sessionId, sentenceId, request);
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/complete")
    public ApiDtos.CompleteSentenceResponse completeSentence(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.CompleteSentenceRequest request) {
        return practiceService.completeSentence(principal.id(), sessionId, sentenceId, request);
    }
}
