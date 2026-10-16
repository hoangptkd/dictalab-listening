package com.dictalab.web;

import com.dictalab.dto.AdminDtos;
import com.dictalab.service.AdminContentService;
import com.dictalab.service.AdminQueryService;
import com.dictalab.security.DictaLabPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
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
@Transactional
public class AdminContentController {

    private final AdminContentService adminContentService;
    private final AdminQueryService queries;

    public AdminContentController(AdminContentService adminContentService, AdminQueryService queries) {
        this.adminContentService = adminContentService;
        this.queries = queries;
    }

    @GetMapping("/page")
    public AdminQueryService.Page page(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String level,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String topic,
            @RequestParam(defaultValue="") String origin, @RequestParam(defaultValue="updated") String sort,
            @RequestParam(defaultValue="0") int page) { return queries.lessons(q,level,status,topic,origin,sort,page); }
    @GetMapping("/filters")
    public List<Map<String,Object>> filters() { return queries.lessonFilters(); }
    @GetMapping("/{lessonId}/events")
    public List<Map<String,Object>> events(@PathVariable long lessonId) { adminContentService.lesson(lessonId); return queries.events("LESSON",lessonId); }
    @PostMapping("/{lessonId}/copy")
    public AdminDtos.LessonResponse copy(@PathVariable long lessonId,@RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result=adminContentService.copyLesson(lessonId);
        queries.event(actor.id(),"LESSON",result.id(),"COPY_DRAFT","Từ bài #"+lessonId); return result;
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
    public AdminDtos.LessonResponse createLesson(@Valid @RequestBody AdminDtos.CreateLessonRequest request,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        var result = adminContentService.createLesson(request);
        queries.event(actor.id(),"LESSON",result.id(),"CREATE_DRAFT",result.title()); return result;
    }

    @PutMapping("/{lessonId}")
    public AdminDtos.LessonResponse updateLesson(@PathVariable Long lessonId,
            @Valid @RequestBody AdminDtos.UpdateLessonRequest request, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.updateLesson(lessonId, request);
        queries.event(actor.id(),"LESSON",lessonId,"UPDATE_METADATA",result.title()); return result;
    }

    @PostMapping("/{lessonId}/sentences")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.LessonResponse addSentence(@PathVariable Long lessonId,
            @Valid @RequestBody AdminDtos.SentenceRequest request, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.addSentence(lessonId, request);
        queries.event(actor.id(),"LESSON",lessonId,"ADD_SENTENCE",null); return result;
    }

    @PutMapping("/{lessonId}/sentences/{sentenceId}")
    public AdminDtos.LessonResponse updateSentence(@PathVariable Long lessonId,
                                                    @PathVariable Long sentenceId,
            @Valid @RequestBody AdminDtos.SentenceRequest request, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.updateSentence(lessonId, sentenceId, request);
        queries.event(actor.id(),"LESSON",lessonId,"UPDATE_SENTENCE","Câu #"+sentenceId); return result;
    }

    @DeleteMapping("/{lessonId}/sentences/{sentenceId}")
    public AdminDtos.LessonResponse deleteSentence(@PathVariable Long lessonId,
            @PathVariable Long sentenceId, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.deleteSentence(lessonId, sentenceId);
        queries.event(actor.id(),"LESSON",lessonId,"DELETE_SENTENCE","Câu #"+sentenceId); return result;
    }

    @PutMapping("/{lessonId}/sentences/order")
    public AdminDtos.LessonResponse reorderSentences(
            @PathVariable Long lessonId,
            @Valid @RequestBody AdminDtos.ReorderSentencesRequest request, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.reorderSentences(lessonId, request);
        queries.event(actor.id(),"LESSON",lessonId,"REORDER_SENTENCES",null); return result;
    }

    @PostMapping(path = "/{lessonId}/sentences/{sentenceId}/audio",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public AdminDtos.LessonResponse uploadAudio(@PathVariable Long lessonId,
                                                 @PathVariable Long sentenceId,
            @RequestPart("file") MultipartFile file, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        return adminContentService.uploadAudio(lessonId, sentenceId, file, revision, actor.id());
    }

    @PutMapping("/{lessonId}/publication")
    public AdminDtos.LessonResponse setPublication(@PathVariable Long lessonId,
            @RequestBody AdminDtos.PublicationRequest request, @RequestHeader(value="X-Edit-Version",required=false) String revision,
            @AuthenticationPrincipal DictaLabPrincipal actor) {
        queries.verifyLesson(lessonId,revision);
        var result = adminContentService.setPublication(lessonId, request.published());
        queries.event(actor.id(),"LESSON",lessonId,request.published()?"PUBLISH":"UNPUBLISH",result.title()); return result;
    }
}
