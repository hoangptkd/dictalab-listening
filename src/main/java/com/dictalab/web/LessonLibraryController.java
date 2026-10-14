package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.LessonLibrary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class LessonLibraryController {
    private final LessonLibrary library;
    public LessonLibraryController(LessonLibrary library) { this.library = library; }

    @GetMapping("/api/v1/library")
    public LessonLibrary.Snapshot catalog(@AuthenticationPrincipal DictaLabPrincipal user) {
        return library.snapshot(user == null ? 0L : user.id(), user != null && "ADMIN".equals(user.role()));
    }

    @GetMapping("/api/admin/library")
    public LessonLibrary.Snapshot admin(@AuthenticationPrincipal DictaLabPrincipal user) {
        requireAdmin(user); return library.snapshot(user.id(), true);
    }

    @PutMapping("/api/admin/library/courses")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void course(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody LessonLibrary.CourseEdit body) {
        requireAdmin(user); library.saveCourse(body);
    }
    @PutMapping("/api/admin/library/groups")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void group(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody LessonLibrary.GroupEdit body) {
        requireAdmin(user); library.saveGroup(body);
    }
    @PutMapping("/api/admin/library/lessons")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void link(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody LessonLibrary.LinkEdit body) {
        requireAdmin(user); library.saveLink(body);
    }
    @PutMapping("/api/admin/library/metadata")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void metadata(@AuthenticationPrincipal DictaLabPrincipal user, @Valid @RequestBody LessonLibrary.MetadataEdit body) {
        requireAdmin(user); library.saveMetadata(body);
    }
    @DeleteMapping("/api/admin/library/courses/{courseId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal DictaLabPrincipal user, @PathVariable String courseId, @PathVariable long lessonId) {
        requireAdmin(user); library.unlink(courseId, lessonId);
    }
    private static void requireAdmin(DictaLabPrincipal user) {
        if (user == null || !"ADMIN".equals(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
