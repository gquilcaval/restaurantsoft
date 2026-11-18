package com.restos.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Alertas proactivas para cocina y salón, pronóstico de insumos y lista de compras. */
@RestController
public class OpsController {
    private final OpsService ops;

    public OpsController(OpsService ops) {
        this.ops = ops;
    }

    @GetMapping("/api/staff/ops/stations")
    @PreAuthorize("hasAnyRole('KITCHEN','WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OpsService.StationBoard stations() { return ops.stations(); }

    @GetMapping("/api/admin/inventory/forecast")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<OpsService.StockForecast> forecast() { return ops.forecast(); }

    @GetMapping("/api/admin/inventory/shopping-list")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<OpsService.ShoppingItem> shoppingList() { return ops.shoppingList(); }

    @PutMapping("/api/admin/inventory/ingredients/{id}/supplier")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void supplier(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        ops.updateSupplier(id, request.name(), request.phone());
    }

    record SupplierRequest(@Size(max = 100) String name, @Size(max = 30) String phone) {}
}
