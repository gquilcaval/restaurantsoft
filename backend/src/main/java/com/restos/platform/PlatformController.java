package com.restos.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Panel central de RestOS: registra clientes completos y controla su acceso a la plataforma. */
@RestController
@RequestMapping("/api/platform/organizations")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformController {
    private final PlatformService platform;

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    @GetMapping
    List<PlatformService.OrganizationView> list() {
        return platform.list();
    }

    @PostMapping
    PlatformService.OrganizationView create(@Valid @RequestBody CreateOrganization request) {
        return platform.create(request);
    }

    @PatchMapping("/{id}")
    PlatformService.OrganizationView updateStatus(@PathVariable UUID id,
            @Valid @RequestBody UpdateOrganization request) {
        return platform.updateStatus(id, request.active());
    }

    public record CreateOrganization(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") @Size(max = 80) String slug,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
            @NotBlank @Pattern(regexp = "LIGHT|DARK|SYSTEM") String defaultTheme,
            @NotBlank @Size(max = 120) String branchName,
            @Size(max = 250) String address,
            @NotBlank @Size(max = 120) String adminName,
            @NotBlank @Email @Size(max = 180) String adminEmail,
            @NotBlank @Size(min = 8, max = 100) String adminPassword,
            @Min(1) @Max(100) int initialTables,
            boolean createMenuTemplate) {}

    @PutMapping("/{id}/modules")
    PlatformService.OrganizationView updateModules(@PathVariable UUID id, @Valid @RequestBody UpdateModules request) {
        return platform.updateModules(id, request.attendance());
    }

    public record UpdateOrganization(boolean active) {}
    public record UpdateModules(boolean attendance) {}
}
