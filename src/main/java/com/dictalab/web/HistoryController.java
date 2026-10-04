package com.dictalab.web;

import com.dictalab.dto.ApiDtos;
import com.dictalab.domain.CefrLevel;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.HistoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/history")
@Validated
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ApiDtos.HistoryPage list(@AuthenticationPrincipal DictaLabPrincipal principal,
                                    @RequestParam(required = false) CefrLevel level,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return historyService.list(principal.id(), level, page, size);
    }

    @GetMapping("/{sessionId}")
    public ApiDtos.LessonResult detail(@AuthenticationPrincipal DictaLabPrincipal principal,
                                        @PathVariable String sessionId) {
        return historyService.detail(principal.id(), sessionId);
    }
}
