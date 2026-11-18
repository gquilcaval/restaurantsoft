package com.restos.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.restos.security.JwtService;

/** Endpoints del administrador organizacional para gestionar y seleccionar sucursales. */
@RestController
public class BranchController {
    private final BranchService branches;
    public BranchController(BranchService branches) { this.branches = branches; }

    @GetMapping("/api/admin/branches")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    List<BranchService.BranchView> list() { return branches.list(); }

    @PostMapping("/api/admin/branches")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    BranchService.BranchView create(@Valid @RequestBody CreateBranch request) {
        return branches.create(request.name(), request.address(), request.initialTables());
    }

    @PatchMapping("/api/admin/branches/{id}")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    BranchService.BranchView update(@PathVariable UUID id, @Valid @RequestBody UpdateBranch request) {
        return branches.update(id, request.name(), request.address(), request.active());
    }

    @PostMapping("/api/auth/switch-branch/{id}")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    JwtService.TokenResponse switchBranch(@PathVariable UUID id, @Valid @RequestBody SwitchBranch request) {
        return branches.switchBranch(id, request.refreshToken());
    }

    record CreateBranch(@NotBlank @Size(max=120) String name, @Size(max=250) String address,
                        @Min(0) @Max(100) int initialTables) {}
    record UpdateBranch(@NotBlank @Size(max=120) String name, @Size(max=250) String address,
                        boolean active) {}
    record SwitchBranch(@NotBlank String refreshToken) {}
}
