package com.dictalab.web;

import com.dictalab.dto.ApiDtos;
import com.dictalab.security.DictaLabPrincipal;
import com.dictalab.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Validated
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ApiDtos.DashboardResponse dashboard(@AuthenticationPrincipal DictaLabPrincipal principal) {
        return dashboardService.dashboard(principal.id());
    }
}
