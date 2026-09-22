package com.dictalab.web;

import com.dictalab.dto.AdminDtos;
import com.dictalab.service.AdminContentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/lessons")
public class AdminContentController {

    private final AdminContentService adminContentService;

    public AdminContentController(AdminContentService adminContentService) {
        this.adminContentService = adminContentService;
    }

    @GetMapping
    public List<AdminDtos.LessonListItem> lessons() {
        return adminContentService.listLessons();
    }

    @GetMapping("/{lessonId}")
    public AdminDtos.LessonResponse lesson(@PathVariable Long lessonId) {
        return adminContentService.lesson(lessonId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.LessonResponse createLesson(@Valid @RequestBody AdminDtos.CreateLessonRequest request) {
        return adminContentService.createLesson(request);
    }

    @PutMapping("/{lessonId}")
    public AdminDtos.LessonResponse updateLesson(@PathVariable Long lessonId,
                                                  @Valid @RequestBody AdminDtos.UpdateLessonRequest request) {
        return adminContentService.updateLesson(lessonId, request);
    }

    @PostMapping("/{lessonId}/sentences")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.LessonResponse addSentence(@PathVariable Long lessonId,
                                                 @Valid @RequestBody AdminDtos.SentenceRequest request) {
        return adminContentService.addSentence(lessonId, request);
    }

    @PutMapping("/{lessonId}/sentences/{sentenceId}")
    public AdminDtos.LessonResponse updateSentence(@PathVariable Long lessonId,
                                                    @PathVariable Long sentenceId,
                                                    @Valid @RequestBody AdminDtos.SentenceRequest request) {
        return adminContentService.updateSentence(lessonId, sentenceId, request);
    }

    @DeleteMapping("/{lessonId}/sentences/{sentenceId}")
    public AdminDtos.LessonResponse deleteSentence(@PathVariable Long lessonId,
                                                    @PathVariable Long sentenceId) {
        return adminContentService.deleteSentence(lessonId, sentenceId);
    }

    @PutMapping("/{lessonId}/sentences/order")
    public AdminDtos.LessonResponse reorderSentences(
            @PathVariable Long lessonId,
            @Valid @RequestBody AdminDtos.ReorderSentencesRequest request) {
        return adminContentService.reorderSentences(lessonId, request);
    }

    @PostMapping(path = "/{lessonId}/sentences/{sentenceId}/audio",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminDtos.LessonResponse uploadAudio(@PathVariable Long lessonId,
                                                 @PathVariable Long sentenceId,
                                                 @RequestPart("file") MultipartFile file) {
        return adminContentService.uploadAudio(lessonId, sentenceId, file);
    }

    @PutMapping("/{lessonId}/publication")
    public AdminDtos.LessonResponse setPublication(@PathVariable Long lessonId,
                                                    @RequestBody AdminDtos.PublicationRequest request) {
        return adminContentService.setPublication(lessonId, request.published());
    }
}
