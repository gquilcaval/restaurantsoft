package com.restos.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expone los indicadores reales de la sucursal para el resumen administrativo. */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    DashboardService.DashboardView summary() {
        return dashboard.summary();
    }
}
