package com.restos.platform;

import com.restos.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identidad visual persistente de cada restaurante de la plataforma. */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class OrganizationSettingsController {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public OrganizationSettingsController(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    SettingsView get() {
        return find(currentUser.require().organizationId());
    }

    @PatchMapping
    SettingsView update(@Valid @RequestBody UpdateSettings request) {
        var organizationId = currentUser.require().organizationId();
        jdbc.sql("""
                UPDATE organizations SET name=:name,primary_color=:color,default_theme=:theme,logo_url=:logo,updated_at=NOW()
                WHERE id=:id
                """).param("name", request.name().trim()).param("color", request.primaryColor()).param("theme", request.defaultTheme())
                .param("logo", request.logoUrl()).param("id", organizationId).update();
        return find(organizationId);
    }

    private SettingsView find(UUID id) {
        return jdbc.sql("SELECT name,logo_url,primary_color,default_theme FROM organizations WHERE id=:id")
                .param("id", id).query((rs, row) -> new SettingsView(rs.getString("name"),
                        rs.getString("logo_url"), rs.getString("primary_color"),
                        rs.getString("default_theme"))).single();
    }

    /** Estilo de la carta QR: clásico, moderno oscuro, cevichería fresca o cafetería cálida. */
    @GetMapping("/menu-theme")
    java.util.Map<String, String> menuTheme() {
        var org = currentUser.require().organizationId();
        return java.util.Map.of("theme", jdbc.sql("SELECT menu_theme FROM organizations WHERE id=:id").param("id", org)
                .query(String.class).single());
    }

    @org.springframework.web.bind.annotation.PutMapping("/menu-theme")
    java.util.Map<String, String> saveMenuTheme(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) {
        var theme = body.getOrDefault("theme", "CLASSIC");
        if (!java.util.List.of("CLASSIC", "MODERN", "FRESH", "WARM").contains(theme)) {
            throw new com.restos.shared.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Estilo inválido");
        }
        jdbc.sql("UPDATE organizations SET menu_theme=:theme,updated_at=NOW() WHERE id=:id")
                .param("theme", theme).param("id", currentUser.require().organizationId()).update();
        return java.util.Map.of("theme", theme);
    }

    record UpdateSettings(@NotBlank @Size(max = 120) String name,
                          @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
                          @Pattern(regexp = "LIGHT|DARK|SYSTEM") String defaultTheme,
                          @Size(max = 500) String logoUrl) {}
    public record SettingsView(String name, String logoUrl, String primaryColor, String defaultTheme) {}
}
