package com.restos.floor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Plano del salón: lo consulta todo el personal de sala y lo diseña la administración. */
@RestController
public class FloorController {
    private final FloorService floor;
    private final FloorStudioService studio;

    public FloorController(FloorService floor, FloorStudioService studio) {
        this.floor = floor;
        this.studio = studio;
    }

    @PutMapping("/api/admin/floor/zones/{zoneId}/settings")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void settings(@PathVariable UUID zoneId, @RequestBody SettingsRequest request) {
        studio.settings(zoneId, new FloorStudioService.SettingsData(request.backgroundUrl(), request.backgroundOpacity(),
                request.widthMeters(), request.maxCapacity()));
    }

    @GetMapping("/api/admin/floor/layouts")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<FloorStudioService.Layout> layouts() { return studio.layouts(); }

    @PostMapping("/api/admin/floor/zones/{zoneId}/layouts")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    java.util.Map<String, UUID> createLayout(@PathVariable UUID zoneId, @Valid @RequestBody LayoutRequest request) {
        return java.util.Map.of("id", studio.createLayout(zoneId, request.name()));
    }

    @PutMapping("/api/admin/floor/layouts/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void updateLayout(@PathVariable UUID id) { studio.updateLayout(id); }

    @PostMapping("/api/admin/floor/layouts/{id}/apply")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void applyLayout(@PathVariable UUID id) { studio.applyLayout(id); }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/admin/floor/layouts/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void deleteLayout(@PathVariable UUID id) { studio.deleteLayout(id); }

    @GetMapping("/api/admin/floor/staff")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<FloorStudioService.StaffMember> staff() { return studio.staff(); }

    @PutMapping("/api/admin/floor/waiters")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void assignWaiter(@Valid @RequestBody WaiterRequest request) { studio.assignWaiter(request.tableIds(), request.waiterId()); }

    @GetMapping("/api/staff/floor")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    FloorService.FloorPlan plan() { return floor.plan(); }

    @PutMapping("/api/admin/floor")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    FloorService.FloorPlan save(@Valid @RequestBody SaveRequest request) {
        return floor.save(request.zoneId(), request.tables() == null ? List.of() : request.tables(),
                request.elements() == null ? List.of() : request.elements());
    }

    @PostMapping("/api/admin/floor/plans")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    java.util.Map<String, UUID> createPlan(@Valid @RequestBody PlanRequest request) {
        return java.util.Map.of("id", floor.createPlan(request.name(), request.color(), request.baseName()));
    }

    @PostMapping("/api/staff/sessions/{sessionId}/move")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    FloorService.MoveResult move(@PathVariable UUID sessionId, @Valid @RequestBody MoveRequest request) {
        return floor.move(sessionId, request.tableId());
    }

    record SaveRequest(UUID zoneId, @Size(max = 300) List<FloorService.TablePlacement> tables,
                       @Size(max = 200) List<FloorService.FloorElement> elements) {}
    record PlanRequest(@jakarta.validation.constraints.NotBlank @Size(max = 60) String name,
                       @jakarta.validation.constraints.Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
                       @Size(max = 60) String baseName) {}
    record MoveRequest(@NotNull UUID tableId) {}
    record SettingsRequest(@Size(max = 500) String backgroundUrl, int backgroundOpacity, double widthMeters, Integer maxCapacity) {}
    record LayoutRequest(@jakarta.validation.constraints.NotBlank @Size(max = 60) String name) {}
    record WaiterRequest(@NotNull @Size(max = 300) List<UUID> tableIds, UUID waiterId) {}
}
