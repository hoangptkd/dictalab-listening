package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ListeningLearning;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/listening-learning")
public class ListeningLearningController {
    private final ListeningLearning learning;
    public ListeningLearningController(ListeningLearning learning) { this.learning=learning; }
    public record Answer(@NotNull @Size(max=200) String answer, boolean reveal) {}
    @GetMapping
    public Map<String,Object> bank(@AuthenticationPrincipal DictaLabPrincipal user) { return learning.bank(user.id()); }
    @PostMapping("/reviews")
    public List<ListeningLearning.Challenge> start(@AuthenticationPrincipal DictaLabPrincipal user) { return learning.startReview(user.id()); }
    @GetMapping("/reviews/{id}")
    public ListeningLearning.ReviewState review(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id) { return learning.review(user.id(),id); }
    @PostMapping("/reviews/{id}/played")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void played(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id) { learning.played(user.id(),id); }
    @PostMapping("/reviews/{id}/answer")
    public ListeningLearning.ReviewResult answer(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable String id,
                                                @Valid @RequestBody Answer request) {
        return learning.answer(user.id(),id,request.answer(),request.reveal());
    }
}
