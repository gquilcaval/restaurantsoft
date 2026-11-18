package com.restos.security;

import com.restos.audit.AuditService;
import com.restos.shared.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tablets compartidas del local. Un encargado autoriza el equipo una vez; desde entonces el personal de
 * esa sucursal entra eligiendo su nombre y escribiendo su PIN. El token del equipo solo se guarda como hash.
 */
@Service
public class DeviceService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RefreshTokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final RestosUserDetailsService users;
    private final JwtService jwt;
    private final LoginAttemptService attempts;
    private final AuditService audit;

    public DeviceService(JdbcClient jdbc, CurrentUser currentUser, RefreshTokenService tokens,
                         PasswordEncoder passwordEncoder, RestosUserDetailsService users, JwtService jwt,
                         LoginAttemptService attempts, AuditService audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.users = users;
        this.jwt = jwt;
        this.attempts = attempts;
        this.audit = audit;
    }

    @Transactional
    public RegisteredDevice register(String name) {
        var scope = currentUser.require();
        var raw = tokens.newToken();
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO staff_devices(id,organization_id,branch_id,name,token_hash,created_by)
                VALUES (:id,:org,:branch,:name,:hash,:user)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("name", name.trim()).param("hash", tokens.hash(raw)).param("user", scope.userId()).update();
        audit.record("DEVICE_REGISTERED", "Tablet autorizada: " + name.trim(), null, id);
        return new RegisteredDevice(id, name.trim(), raw);
    }

    public List<DeviceView> list() {
        var branch = currentUser.require().branchId();
        return jdbc.sql("""
                        SELECT d.id,d.name,d.created_at,d.last_used_at,u.full_name FROM staff_devices d
                        JOIN app_users u ON u.id=d.created_by
                        WHERE d.branch_id=:branch AND d.revoked_at IS NULL ORDER BY d.created_at DESC
                        """).param("branch", branch)
                .query((rs, row) -> new DeviceView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getString("full_name"))).list();
    }

    @Transactional
    public void revoke(UUID id) {
        var branch = currentUser.require().branchId();
        var name = jdbc.sql("""
                UPDATE staff_devices SET revoked_at=NOW() WHERE id=:id AND branch_id=:branch AND revoked_at IS NULL
                RETURNING name
                """).param("id", id).param("branch", branch).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispositivo no encontrado"));
        audit.record("DEVICE_REVOKED", "Tablet desautorizada: " + name, null, id);
    }

    /** Nombres del personal con PIN de la sucursal del equipo, para la pantalla de ingreso rápido. */
    public DeviceStaff staff(String rawToken) {
        var device = device(rawToken);
        var staff = jdbc.sql("""
                        SELECT id,full_name,role FROM app_users
                        WHERE branch_id=:branch AND active AND pin_hash IS NOT NULL
                          AND role IN ('BRANCH_MANAGER','WAITER','KITCHEN')
                        ORDER BY full_name
                        """).param("branch", device.branchId())
                .query((rs, row) -> new StaffMember(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("role"))).list();
        return new DeviceStaff(device.name(), device.branchName(), staff);
    }

    @Transactional
    public JwtService.TokenResponse pinLogin(String rawToken, UUID userId, String pin, String remoteAddress) {
        var device = device(rawToken);
        // Se limita por persona y por equipo: así no se puede probar PINs alternando entre nombres.
        var userKey = "pin|" + device.id() + "|" + userId;
        var deviceKey = "pin-device|" + device.id() + "|" + remoteAddress;
        attempts.assertAllowed(userKey);
        attempts.assertAllowed(deviceKey);
        var hash = jdbc.sql("""
                SELECT pin_hash FROM app_users WHERE id=:user AND branch_id=:branch AND active
                  AND role IN ('BRANCH_MANAGER','WAITER','KITCHEN')
                """).param("user", userId).param("branch", device.branchId()).query(String.class).optional().orElse(null);
        if (hash == null || pin == null || !passwordEncoder.matches(pin, hash)) {
            attempts.failed(userKey);
            attempts.failed(deviceKey);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PIN incorrecto");
        }
        var account = users.findAccount(userId);
        if (!account.active()) throw new ApiException(HttpStatus.FORBIDDEN,
                users.organizationBlocked(account.organizationId()) ? SubscriptionGate.SUSPENDED_MESSAGE
                        : "Tu usuario está desactivado. Habla con el encargado del local");
        attempts.succeeded(userKey);
        attempts.succeeded(deviceKey);
        jdbc.sql("UPDATE staff_devices SET last_used_at=NOW() WHERE id=:id").param("id", device.id()).update();
        audit.record(account.organizationId(), device.branchId(), userId, "PIN_LOGIN",
                account.fullName() + " ingresó con PIN en " + device.name(), null, device.id());
        return jwt.create(account, device.branchId(), tokens.issue(userId, device.branchId()));
    }

    /** Equipo autorizado al que pertenece el token (lo usan otros módulos de la tablet, como asistencia). */
    public ResolvedDevice resolve(String rawToken) {
        var device = device(rawToken);
        var organization = jdbc.sql("SELECT organization_id FROM staff_devices WHERE id=:id").param("id", device.id())
                .query(UUID.class).single();
        return new ResolvedDevice(device.id(), device.name(), device.branchId(), device.branchName(), organization);
    }

    private Device device(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Equipo no autorizado");
        return jdbc.sql("""
                        SELECT d.id,d.name,d.branch_id,b.name branch_name FROM staff_devices d
                        JOIN branches b ON b.id=d.branch_id AND b.active
                        JOIN organizations o ON o.id=d.organization_id AND o.active
                        WHERE d.token_hash=:hash AND d.revoked_at IS NULL
                        """).param("hash", tokens.hash(rawToken))
                .query((rs, row) -> new Device(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "Este equipo ya no está autorizado. Pide a un encargado que lo vuelva a activar"));
    }

    private record Device(UUID id, String name, UUID branchId, String branchName) {}
    public record ResolvedDevice(UUID id, String name, UUID branchId, String branchName, UUID organizationId) {}
    public record RegisteredDevice(UUID id, String name, String deviceToken) {}
    public record DeviceView(UUID id, String name, Instant createdAt, Instant lastUsedAt, String createdBy) {}
    public record StaffMember(UUID id, String fullName, String role) {}
    public record DeviceStaff(String deviceName, String branchName, List<StaffMember> staff) {}
}
