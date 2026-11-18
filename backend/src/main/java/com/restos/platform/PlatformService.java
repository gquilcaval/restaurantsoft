package com.restos.platform;

import com.restos.audit.AuditService;
import com.restos.security.CurrentUser;
import com.restos.security.SubscriptionGate;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ejecuta el onboarding multiempresa como una sola transacción para no dejar altas incompletas. */
@Service
public class PlatformService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SubscriptionGate gate;
    private final SubscriptionService subscriptions;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public PlatformService(JdbcClient jdbc, PasswordEncoder passwordEncoder, SubscriptionGate gate,
                           SubscriptionService subscriptions, CurrentUser currentUser, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.gate = gate;
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    public List<OrganizationView> list() {
        return jdbc.sql(summarySql() + " ORDER BY o.created_at DESC")
                .query((rs, row) -> map(rs)).list();
    }

    @Transactional
    public OrganizationView create(PlatformController.CreateOrganization request) {
        var slug = request.slug().trim().toLowerCase();
        var email = request.adminEmail().trim().toLowerCase();
        if (exists("SELECT count(*) FROM organizations WHERE slug=:value", slug)) {
            throw new ApiException(HttpStatus.CONFLICT, "El identificador web ya está registrado");
        }
        if (exists("SELECT count(*) FROM app_users WHERE lower(email)=:value", email)) {
            throw new ApiException(HttpStatus.CONFLICT, "El correo del administrador ya está registrado");
        }

        var organizationId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO organizations(id,name,slug,primary_color,default_theme,trial_ends_at)
                VALUES (:id,:name,:slug,:color,:theme,CURRENT_DATE + :trialDays)
                """).param("id", organizationId).param("name", request.name().trim())
                .param("slug", slug).param("color", request.primaryColor().toUpperCase())
                .param("theme", request.defaultTheme()).param("trialDays", gate.trialDays()).update();
        jdbc.sql("""
                INSERT INTO branches(id,organization_id,name,address)
                VALUES (:id,:organization,:name,:address)
                """).param("id", branchId).param("organization", organizationId)
                .param("name", request.branchName().trim()).param("address", normalize(request.address())).update();
        jdbc.sql("""
                INSERT INTO app_users(organization_id,branch_id,full_name,email,password_hash,role)
                VALUES (:organization,:branch,:name,:email,:password,'ORGANIZATION_ADMIN')
                """).param("organization", organizationId).param("branch", branchId)
                .param("name", request.adminName().trim()).param("email", email)
                .param("password", passwordEncoder.encode(request.adminPassword())).update();

        for (int number = 1; number <= request.initialTables(); number++) {
            jdbc.sql("""
                    INSERT INTO dining_tables(organization_id,branch_id,label,capacity)
                    VALUES (:organization,:branch,:label,4)
                    """).param("organization", organizationId).param("branch", branchId)
                    .param("label", String.format("Mesa %02d", number)).update();
        }
        if (request.createMenuTemplate()) createMenuTemplate(organizationId);
        return find(organizationId);
    }

    @Transactional
    public OrganizationView updateStatus(UUID id, boolean active) {
        if (!active) {
            var openSessions = jdbc.sql("""
                    SELECT count(*) FROM table_sessions WHERE organization_id=:id AND status='OPEN'
                    """).param("id", id).query(Integer.class).single();
            if (openSessions > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "No se puede suspender un restaurante con mesas abiertas");
            }
        }
        var updated = jdbc.sql("UPDATE organizations SET active=:active,updated_at=NOW() WHERE id=:id")
                .param("active", active).param("id", id).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado");
        if (!active) {
            jdbc.sql("""
                    UPDATE refresh_tokens SET revoked=TRUE,last_used_at=NOW()
                    WHERE user_id IN (SELECT id FROM app_users WHERE organization_id=:id) AND NOT revoked
                    """).param("id", id).update();
        }
        return find(id);
    }

    /**
     * Activa o desactiva los módulos adicionales de un restaurante. Desactivar no borra nada:
     * las marcas y horarios quedan guardados y vuelven a verse si se reactiva.
     */
    @Transactional
    public OrganizationView updateModules(UUID id, boolean attendance) {
        var before = find(id);
        if (before.modules().attendance() != attendance) {
            jdbc.sql("UPDATE organizations SET attendance_enabled=:attendance,updated_at=NOW() WHERE id=:id")
                    .param("attendance", attendance).param("id", id).update();
            // Queda en la auditoría de cada sucursal del restaurante, firmado por quien lo hizo desde la plataforma.
            var by = currentUser.require().userId();
            var summary = "Módulo de asistencia " + (attendance ? "activado" : "desactivado") + " por RestOS";
            jdbc.sql("SELECT id FROM branches WHERE organization_id=:id").param("id", id).query(UUID.class).list()
                    .forEach(branch -> audit.record(id, branch, by, "MODULE_CHANGED", summary, null, null));
        }
        return find(id);
    }

    private OrganizationView find(UUID id) {
        return jdbc.sql(summarySql() + " WHERE o.id=:id").param("id", id)
                .query((rs, row) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado"));
    }

    private String summarySql() {
        return """
                SELECT o.id,o.name,o.slug,o.logo_url,o.primary_color,o.default_theme,o.active,o.created_at,o.attendance_enabled,
                  o.plan,o.monthly_fee,o.max_branches,o.trial_ends_at,o.paid_until,o.billing_note,
                  (SELECT count(*) FROM branches b WHERE b.organization_id=o.id AND b.active) active_branches,
                  (SELECT count(*) FROM branches b WHERE b.organization_id=o.id) branch_count,
                  (SELECT count(*) FROM app_users u WHERE u.organization_id=o.id AND u.active) user_count,
                  (SELECT count(*) FROM dining_tables t WHERE t.organization_id=o.id AND t.active) table_count
                FROM organizations o
                """;
    }

    private OrganizationView map(ResultSet rs) throws SQLException {
        return new OrganizationView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("logo_url"), rs.getString("primary_color"), rs.getString("default_theme"),
                rs.getBoolean("active"), rs.getInt("branch_count"), rs.getInt("user_count"),
                rs.getInt("table_count"), rs.getObject("created_at", OffsetDateTime.class),
                subscriptions.build(rs.getBoolean("active"), rs.getString("plan"), rs.getBigDecimal("monthly_fee"),
                        rs.getInt("max_branches"), rs.getObject("trial_ends_at", java.time.LocalDate.class),
                        rs.getObject("paid_until", java.time.LocalDate.class), rs.getString("billing_note"),
                        rs.getInt("active_branches")),
                new Modules(rs.getBoolean("attendance_enabled")));
    }

    private boolean exists(String sql, String value) {
        return jdbc.sql(sql).param("value", value).query(Integer.class).single() > 0;
    }

    private void createMenuTemplate(UUID organizationId) {
        var categoryId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO menu_categories(id,organization_id,name,display_order)
                VALUES (:id,:organization,'Platos principales',1)
                """).param("id", categoryId).param("organization", organizationId).update();
        insertMenuItem(organizationId, categoryId, "Plato de la casa",
                "Edita este producto desde la administración del menú", new BigDecimal("20.00"));
        insertMenuItem(organizationId, categoryId, "Bebida de la casa",
                "Producto inicial de ejemplo", new BigDecimal("8.00"));
    }

    private void insertMenuItem(UUID organizationId, UUID categoryId, String name,
            String description, BigDecimal price) {
        jdbc.sql("""
                INSERT INTO menu_items(organization_id,category_id,name,description,price)
                VALUES (:organization,:category,:name,:description,:price)
                """).param("organization", organizationId).param("category", categoryId)
                .param("name", name).param("description", description).param("price", price).update();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record OrganizationView(UUID id, String name, String slug, String logoUrl,
            String primaryColor, String defaultTheme, boolean active, int branchCount,
            int userCount, int tableCount, OffsetDateTime createdAt,
            SubscriptionService.SubscriptionView subscription, Modules modules) {}

    /** Módulos adicionales que se venden aparte del sistema base. */
    public record Modules(boolean attendance) {}
}
