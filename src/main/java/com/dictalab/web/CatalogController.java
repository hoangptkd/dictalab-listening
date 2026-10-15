package com.dictalab.web;

import com.dictalab.domain.CefrLevel;
import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.CatalogService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@Validated
public class CatalogController {

    private final CatalogService catalogService;
    private final JdbcTemplate db;

    public CatalogController(CatalogService catalogService, JdbcTemplate db) {
        this.catalogService = catalogService;
        this.db = db;
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
        return catalogService.lessons(principal == null ? 0L : principal.id(), level, administrator);
    }

    public record Preview(@Positive Long lessonId, @NotBlank @jakarta.validation.constraints.Size(max=500) String audioUrl) {}

    @PostMapping("/preview-exposure")
    @org.springframework.transaction.annotation.Transactional(timeout=10)
    public void preview(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody Preview body) {
        db.queryForObject("SELECT id FROM app_users WHERE id=? FOR UPDATE", Long.class, user.id());
        Integer valid = db.queryForObject("""
                SELECT COUNT(*) FROM lesson_sentences s JOIN lessons l ON l.id=s.lesson_id
                WHERE (CAST(? AS BIGINT) IS NULL OR s.lesson_id=?) AND s.audio_url=? AND l.active=TRUE
                    AND (l.content_origin<>'BRITISH_COUNCIL_PERSONAL' OR ?=TRUE)
                """, Integer.class, body.lessonId(), body.lessonId(), body.audioUrl(), "ADMIN".equals(user.role()));
        if (valid == null || valid == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        db.update("""
                INSERT INTO audio_exposure_events(user_id,audio_url,source) SELECT ?,?,?
                WHERE NOT EXISTS (SELECT 1 FROM audio_exposure_events WHERE user_id=? AND audio_url=?)
                """, user.id(), body.audioUrl(), "VOCAB_PREVIEW", user.id(), body.audioUrl());
    }
}
