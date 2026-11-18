package com.restos.zones;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Zonas: el salón las consulta; la administración las crea, abre o cierra y asigna mesas y meseros. */
@RestController
public class ZoneController {
    private final ZoneService zones;

    public ZoneController(ZoneService zones) {
        this.zones = zones;
    }

    @GetMapping("/api/staff/zones")
    @PreAuthorize("hasAnyRole('WAITER','KITCHEN','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ZoneService.StaffZones staff() { return zones.staff(); }

    @GetMapping("/api/admin/zones")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<ZoneService.ZoneView> list() { return zones.list(); }

    @PostMapping("/api/admin/zones")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ZoneService.ZoneView create(@Valid @RequestBody ZoneRequest request) {
        return zones.save(null, request.name(), request.color(), request.displayOrder(), request.open(), request.active());
    }

    @PatchMapping("/api/admin/zones/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ZoneService.ZoneView update(@PathVariable UUID id, @Valid @RequestBody ZoneRequest request) {
        return zones.save(id, request.name(), request.color(), request.displayOrder(), request.open(), request.active());
    }

    @PutMapping("/api/admin/zones/{id}/tables")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ZoneService.ZoneView tables(@PathVariable UUID id, @Valid @RequestBody IdsRequest request) {
        return zones.assignTables(id, request.ids());
    }

    @PutMapping("/api/admin/zones/{id}/waiters")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ZoneService.ZoneView waiters(@PathVariable UUID id, @Valid @RequestBody IdsRequest request) {
        return zones.assignWaiters(id, request.ids());
    }

    record ZoneRequest(@NotBlank @Size(max = 60) String name, @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
                       @Min(0) @Max(999) int displayOrder, boolean open, boolean active) {}
    record IdsRequest(@NotNull @Size(max = 200) List<UUID> ids) {}
}
