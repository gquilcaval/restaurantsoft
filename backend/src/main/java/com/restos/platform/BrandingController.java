package com.restos.platform;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Expone la identidad visual correcta sin duplicar reglas entre paneles y experiencia QR. */
@RestController
public class BrandingController {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public BrandingController(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/staff/branding")
    @PreAuthorize("isAuthenticated()")
    BrandingView staffBranding() {
        var organizationId = currentUser.require().organizationId();
        if (organizationId == null) return new BrandingView("RestOS", null, "#44D62C", "SYSTEM");
        return findByOrganization(organizationId);
    }

    @GetMapping("/api/public/tables/{qrPublicId}/branding")
    TableBrandingView tableBranding(@PathVariable UUID qrPublicId) {
        var table = jdbc.sql("""
                        SELECT organization_id,label FROM dining_tables
                        WHERE qr_public_id=:qr AND active
                        """).param("qr", qrPublicId)
                .query((rs, row) -> new TableContext(rs.getObject("organization_id", UUID.class),
                        rs.getString("label"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mesa no encontrada"));
        var brand = findByOrganization(table.organizationId());
        var menuTheme = jdbc.sql("SELECT menu_theme FROM organizations WHERE id=:id").param("id", table.organizationId())
                .query(String.class).single();
        return new TableBrandingView(brand.name(), brand.logoUrl(), brand.primaryColor(),
                brand.defaultTheme(), table.label(), menuTheme);
    }

    private BrandingView findByOrganization(UUID organizationId) {
        return jdbc.sql("SELECT name,logo_url,primary_color,default_theme FROM organizations WHERE id=:id AND active")
                .param("id", organizationId)
                .query((rs, row) -> new BrandingView(rs.getString("name"), rs.getString("logo_url"),
                        rs.getString("primary_color"), rs.getString("default_theme")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado"));
    }

    public record BrandingView(String name, String logoUrl, String primaryColor, String defaultTheme) {}
    public record TableBrandingView(String name, String logoUrl, String primaryColor,
                                    String defaultTheme, String tableLabel, String menuTheme) {}
    private record TableContext(UUID organizationId, String label) {}
}
