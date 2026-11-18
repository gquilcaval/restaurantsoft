package com.restos.menu;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expone el menú activo asociado a la organización de una sesión de mesa. */
@RestController
@RequestMapping("/api/public/sessions/{sessionId}/menu")
public class MenuController {
    private final JdbcClient jdbc;
    private final MenuCatalog catalog;

    public MenuController(JdbcClient jdbc, MenuCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @GetMapping
    List<MenuCatalog.MenuItemView> menu(@PathVariable UUID sessionId) {
        return jdbc.sql("SELECT organization_id,branch_id FROM table_sessions WHERE id=:session AND status='OPEN'")
                .param("session", sessionId)
                .query((rs, row) -> new Scope(rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class)))
                .optional().map(scope -> catalog.menu(scope.organizationId(), scope.branchId()))
                .orElse(List.of());
    }

    private record Scope(UUID organizationId, UUID branchId) {}
}
