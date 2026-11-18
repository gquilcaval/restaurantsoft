package com.restos.menu;

import com.restos.audit.AuditService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Carta operativa del salón y la cocina: tomar pedidos y marcar productos agotados en la sucursal. */
@RestController
@RequestMapping("/api/staff/menu")
@PreAuthorize("hasAnyRole('WAITER','KITCHEN','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
public class StaffMenuController {
    private final JdbcClient jdbc;
    private final MenuCatalog catalog;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final AuditService audit;
    private final AvailabilityService availability;

    public StaffMenuController(JdbcClient jdbc, MenuCatalog catalog, CurrentUser currentUser,
                               RealtimePublisher realtime, AuditService audit, AvailabilityService availability) {
        this.audit = audit;
        this.availability = availability;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.currentUser = currentUser;
        this.realtime = realtime;
    }

    @GetMapping
    List<MenuCatalog.MenuItemView> menu() {
        var scope = currentUser.require();
        return catalog.menu(scope.organizationId(), scope.branchId());
    }

    /** Estaciones activas para que cada pantalla de cocina muestre solo lo suyo. */
    @GetMapping("/stations")
    List<Station> stations() {
        return jdbc.sql("""
                        SELECT id,name FROM kitchen_stations WHERE organization_id=:org AND active
                        ORDER BY display_order,name
                        """).param("org", currentUser.require().organizationId())
                .query((rs, row) -> new Station(rs.getObject("id", UUID.class), rs.getString("name"))).list();
    }

    @PutMapping("/items/{itemId}/availability")
    @Transactional
    AvailabilityChanged availability(@PathVariable UUID itemId, @RequestBody AvailabilityRequest request) {
        var scope = currentUser.require();
        var name = jdbc.sql("SELECT name FROM menu_items WHERE id=:item AND organization_id=:org")
                .param("item", itemId).param("org", scope.organizationId()).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        availability.setManual(scope.branchId(), itemId, request.available(), scope.userId());
        var event = new AvailabilityChanged(itemId, name, request.available());
        audit.record("AVAILABILITY_CHANGED", name + (request.available() ? " vuelve a estar disponible" : " marcado como agotado"),
                null, itemId);
        return event;
    }

    record AvailabilityRequest(boolean available) {}
    public record Station(UUID id, String name) {}
    public record AvailabilityChanged(UUID menuItemId, String name, boolean available) {}
}
