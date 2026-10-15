package com.dictalab.web;

import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.PracticeService;
import com.dictalab.service.PracticeSubmissions;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final PracticeSubmissions submissions;

    public PracticeController(PracticeService practiceService, PracticeSubmissions submissions) {
        this.practiceService = practiceService;
        this.submissions = submissions;
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
            @Valid @RequestBody ApiDtos.ReplayRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/replay", request,
                ApiDtos.SentenceState.class, () -> practiceService.replay(principal.id(), sessionId, sentenceId, request));
    }

    @PostMapping(value = "/{sessionId}/sentences/{sentenceId}/words/{wordIndex}/checks", params = "!compact")
    public ApiDtos.SentenceState checkWord(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @PathVariable @Min(0) int wordIndex,
            @Valid @RequestBody ApiDtos.CheckWordRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/checks/" + wordIndex, request,
                ApiDtos.SentenceState.class, locked -> practiceService.checkLockedWord(locked, sentenceId, wordIndex, request));
    }

    @PostMapping(value = "/{sessionId}/sentences/{sentenceId}/words/{wordIndex}/checks", params = "compact=true")
    public ApiDtos.WordCheckResult checkWordResult(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @PathVariable @Min(0) int wordIndex,
            @Valid @RequestBody ApiDtos.CheckWordRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        // Version the receipt shape without changing retries from older, still-open tabs.
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/word-checks/" + wordIndex, request,
                ApiDtos.WordCheckResult.class, locked -> practiceService.checkLockedWordResult(locked, sentenceId, wordIndex, request));
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/hints")
    public ApiDtos.SentenceState hint(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.HintRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/hints", request,
                ApiDtos.SentenceState.class, () -> practiceService.useHint(principal.id(), sessionId, sentenceId, request));
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/classifications")
    public ApiDtos.SentenceState classify(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.ClassifyWordRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/classify", request,
                ApiDtos.SentenceState.class, () -> practiceService.classifyWord(principal.id(), sessionId, sentenceId, request));
    }

    @PostMapping("/{sessionId}/sentences/{sentenceId}/complete")
    public ApiDtos.CompleteSentenceResponse completeSentence(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @PathVariable String sessionId,
            @PathVariable Long sentenceId,
            @Valid @RequestBody ApiDtos.CompleteSentenceRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return submissions.execute(principal.id(), sessionId, key, sentenceId + "/complete", request,
                ApiDtos.CompleteSentenceResponse.class, () -> practiceService.completeSentence(principal.id(), sessionId, sentenceId, request));
    }
}
