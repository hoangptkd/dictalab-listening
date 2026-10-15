package com.dictalab.web;

import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.AiUsageBudget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai")
public class AiOperationsController {
    private final AiUsageBudget budget;
    public AiOperationsController(AiUsageBudget budget) { this.budget=budget; }
    @GetMapping("/budget") public Map<String,Object> status() { return budget.status(); }
    public record Switch(@NotNull Boolean enabled) {}
    @PutMapping("/enabled") public Map<String,Object> set(@AuthenticationPrincipal DictaLabPrincipal actor,
                                                        @Valid @RequestBody Switch request) {
        budget.setEnabled(actor.id(),request.enabled());
        return budget.status();
    }
}
