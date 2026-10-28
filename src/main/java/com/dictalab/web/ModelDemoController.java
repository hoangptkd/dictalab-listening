package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.ModelDemoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@RestController
@RequestMapping("/api/v1/model-demo")
public class ModelDemoController {
    private final ModelDemoService service;
    public ModelDemoController(ModelDemoService service) { this.service=service; }
    @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
    }
    public record Submit(@NotNull UUID requestId, UUID sessionId,
            @NotBlank @Size(max=30) String scenario, @Pattern(regexp="A1|A2|B1") @NotNull String level,
            @Pattern(regexp="practice|compare") @NotNull String mode,
            @Pattern(regexp="base|full07|cloud") @NotNull String variant, boolean allowFallback) {}
    @GetMapping public Map<String,Object> catalog(@AuthenticationPrincipal DictaLabPrincipal user) {
        return service.catalog(user.id());
    }
    @PostMapping("/jobs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ObjectNode submit(@AuthenticationPrincipal DictaLabPrincipal user,@Valid @RequestBody Submit input) {
        return service.submit(user.id(),input.requestId(),input.sessionId(),input.scenario(),input.level(),input.mode(),input.variant(),input.allowFallback());
    }
    @GetMapping("/jobs/{id}") public ObjectNode get(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable UUID id) {
        return service.get(user.id(),id.toString());
    }
    @PostMapping("/jobs/{id}/accept") public ObjectNode accept(@AuthenticationPrincipal DictaLabPrincipal user,@PathVariable UUID id) {
        return service.accept(user.id(),id.toString());
    }
}
