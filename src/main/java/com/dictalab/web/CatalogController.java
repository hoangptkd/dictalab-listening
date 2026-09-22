package com.dictalab.web;

import com.dictalab.domain.CefrLevel;
import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.CatalogService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@Validated
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/levels")
    public List<ApiDtos.LevelOption> levels() {
        return catalogService.levels();
    }

    @GetMapping("/lessons")
    public List<ApiDtos.LessonSummary> lessons(
            @AuthenticationPrincipal DictaLabPrincipal principal,
            @RequestParam CefrLevel level) {
        boolean administrator = principal != null && "ADMIN".equals(principal.role());
        return catalogService.lessons(principal == null ? null : principal.id(), level, administrator);
    }
}
