package com.restos.audit;

import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta de la bitácora para dueños y encargados; siempre limitada a la sucursal del JWT. */
@RestController
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/admin/audit")
    AuditService.Page list(@RequestParam LocalDate from, @RequestParam LocalDate to,
                           @RequestParam(defaultValue = "ALL") String action,
                           @RequestParam(required = false) java.util.UUID userId,
                           @RequestParam(defaultValue = "") String q,
                           @RequestParam(defaultValue = "0") int page) {
        return audit.list(from, to, action, userId, q.length() > 80 ? q.substring(0, 80) : q, page);
    }

    @GetMapping("/api/admin/audit/summary")
    AuditService.Summary summary(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return audit.summary(from, to);
    }
}
