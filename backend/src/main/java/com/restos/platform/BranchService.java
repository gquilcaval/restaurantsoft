package com.restos.platform;

import com.restos.security.CurrentUser;
import com.restos.security.JwtService;
import com.restos.security.RefreshTokenService;
import com.restos.security.RestosUserDetailsService;
import com.restos.shared.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gestiona sedes de una organización y emite un alcance JWT nuevo al cambiar de sucursal. */
@Service
public class BranchService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RestosUserDetailsService users;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final SubscriptionService subscriptions;

    public BranchService(JdbcClient jdbc, CurrentUser currentUser, RestosUserDetailsService users,
            JwtService jwt, RefreshTokenService refreshTokens, SubscriptionService subscriptions) {
        this.jdbc = jdbc; this.currentUser = currentUser; this.users = users;
        this.jwt = jwt; this.refreshTokens = refreshTokens; this.subscriptions = subscriptions;
    }

    public List<BranchView> list() {
        var organizationId = currentUser.require().organizationId();
        return jdbc.sql("""
                        SELECT b.id,b.name,b.address,b.active,
                          (SELECT count(*) FROM dining_tables t WHERE t.branch_id=b.id AND t.active) tables,
                          (SELECT count(*) FROM app_users u WHERE u.branch_id=b.id AND u.active) users,
                          (SELECT count(*) FROM table_sessions ts WHERE ts.branch_id=b.id AND ts.status='OPEN') open_sessions
                        FROM branches b WHERE b.organization_id=:org ORDER BY b.name
                        """).param("org", organizationId).query((rs, row) -> map(rs)).list();
    }

    @Transactional
    public BranchView create(String name, String address, int initialTables) {
        var organizationId = currentUser.require().organizationId();
        subscriptions.ensureBranchAvailable(organizationId);
        ensureNameAvailable(organizationId, name, null);
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO branches(id,organization_id,name,address) VALUES (:id,:org,:name,:address)")
                .param("id", id).param("org", organizationId).param("name", name.trim())
                .param("address", normalize(address)).update();
        for (int number = 1; number <= initialTables; number++) {
            jdbc.sql("""
                    INSERT INTO dining_tables(organization_id,branch_id,label,capacity)
                    VALUES (:org,:branch,:label,4)
                    """).param("org", organizationId).param("branch", id)
                    .param("label", String.format("Mesa %02d", number)).update();
        }
        return find(id, organizationId);
    }

    @Transactional
    public BranchView update(UUID id, String name, String address, boolean active) {
        if (active) {
            // Reactivar una sucursal cuenta para el límite del plan igual que crear una nueva.
            var wasActive = jdbc.sql("SELECT active FROM branches WHERE id=:id").param("id", id).query(Boolean.class)
                    .optional().orElse(true);
            if (!wasActive) subscriptions.ensureBranchAvailable(currentUser.require().organizationId());
        }
        var organizationId = currentUser.require().organizationId();
        ensureNameAvailable(organizationId, name, id);
        if (!active) {
            var open = jdbc.sql("SELECT count(*) FROM table_sessions WHERE branch_id=:branch AND status='OPEN'")
                    .param("branch", id).query(Integer.class).single();
            if (open > 0) throw new ApiException(HttpStatus.CONFLICT,
                    "No se puede desactivar una sucursal con mesas abiertas");
            var activeBranches = jdbc.sql("SELECT count(*) FROM branches WHERE organization_id=:org AND active AND id<>:id")
                    .param("org", organizationId).param("id", id).query(Integer.class).single();
            if (activeBranches == 0) throw new ApiException(HttpStatus.CONFLICT,
                    "La organización debe conservar al menos una sucursal activa");
        }
        var updated = jdbc.sql("""
                UPDATE branches SET name=:name,address=:address,active=:active,updated_at=NOW()
                WHERE id=:id AND organization_id=:org
                """).param("name", name.trim()).param("address", normalize(address)).param("active", active)
                .param("id", id).param("org", organizationId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Sucursal no encontrada");
        return find(id, organizationId);
    }

    @Transactional
    public JwtService.TokenResponse switchBranch(UUID branchId, String refreshToken) {
        var scope = currentUser.require();
        var valid = jdbc.sql("""
                SELECT count(*) FROM branches b JOIN organizations o ON o.id=b.organization_id
                WHERE b.id=:branch AND b.organization_id=:org AND b.active AND o.active
                """).param("branch", branchId).param("org", scope.organizationId())
                .query(Integer.class).single();
        if (valid == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Sucursal no disponible");
        var account = users.findAccount(scope.userId());
        var rotatedRefresh = refreshTokens.replaceForBranch(refreshToken, scope.userId(), branchId);
        return jwt.create(account, branchId, rotatedRefresh);
    }

    private BranchView find(UUID id, UUID organizationId) {
        return jdbc.sql("""
                        SELECT b.id,b.name,b.address,b.active,
                          (SELECT count(*) FROM dining_tables t WHERE t.branch_id=b.id AND t.active) tables,
                          (SELECT count(*) FROM app_users u WHERE u.branch_id=b.id AND u.active) users,
                          (SELECT count(*) FROM table_sessions ts WHERE ts.branch_id=b.id AND ts.status='OPEN') open_sessions
                        FROM branches b WHERE b.id=:id AND b.organization_id=:org
                        """).param("id", id).param("org", organizationId)
                .query((rs, row) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
    }

    private void ensureNameAvailable(UUID organizationId, String name, UUID excluded) {
        var count = excluded == null
                ? jdbc.sql("SELECT count(*) FROM branches WHERE organization_id=:org AND lower(name)=lower(:name)")
                    .param("org", organizationId).param("name", name.trim()).query(Integer.class).single()
                : jdbc.sql("SELECT count(*) FROM branches WHERE organization_id=:org AND lower(name)=lower(:name) AND id<>:id")
                    .param("org", organizationId).param("name", name.trim()).param("id", excluded)
                    .query(Integer.class).single();
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe una sucursal con ese nombre");
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private BranchView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BranchView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getBoolean("active"), rs.getInt("tables"), rs.getInt("users"), rs.getInt("open_sessions"));
    }

    public record BranchView(UUID id, String name, String address, boolean active,
                             int tableCount, int userCount, int openSessionCount) {}
}
