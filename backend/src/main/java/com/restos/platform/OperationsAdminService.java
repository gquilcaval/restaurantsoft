package com.restos.platform;

import com.restos.audit.AuditService;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administración segura de mesas y personal dentro de la sucursal del usuario conectado. */
@Service
public class OperationsAdminService {
    /** STAFF = personal sin acceso al sistema (no inicia sesión; solo figura en el registro y marca asistencia). */
    private static final Set<String> STAFF_ROLES = Set.of("BRANCH_MANAGER", "WAITER", "KITCHEN", "STAFF");
    private static final String NO_ACCESS = "STAFF";
    private static final String MANAGED_ROLES_SQL = "('BRANCH_MANAGER','WAITER','KITCHEN','STAFF')";
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public OperationsAdminService(JdbcClient jdbc, CurrentUser currentUser, PasswordEncoder passwordEncoder,
                                  AuditService audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public List<TableAdminView> tables() {
        var scope = currentUser.require();
        return jdbc.sql("""
                        SELECT t.id,t.label,t.capacity,t.qr_public_id,t.active,t.zone_id,
                               EXISTS(SELECT 1 FROM table_sessions ts WHERE ts.table_id=t.id AND ts.status='OPEN') occupied
                        FROM dining_tables t WHERE t.branch_id=:branch ORDER BY t.label
                        """).param("branch", scope.branchId()).query((rs, row) -> new TableAdminView(
                        rs.getObject("id", UUID.class), rs.getString("label"), rs.getInt("capacity"),
                        rs.getObject("qr_public_id", UUID.class), rs.getBoolean("active"),
                        rs.getBoolean("occupied"), rs.getObject("zone_id", UUID.class))).list();
    }

    @Transactional
    public TableAdminView createTable(String label, int capacity, UUID zoneId) {
        var scope = currentUser.require();
        ensureTableLabelAvailable(label, null, scope.branchId());
        ensureZone(zoneId, scope.branchId());
        var id = UUID.randomUUID(); var qr = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO dining_tables(id,organization_id,branch_id,label,capacity,qr_public_id,zone_id)
                VALUES (:id,:org,:branch,:label,:capacity,:qr,:zone)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("label", label.trim()).param("capacity", capacity).param("qr", qr).param("zone", zoneId).update();
        return new TableAdminView(id, label.trim(), capacity, qr, true, false, zoneId);
    }

    private void ensureZone(UUID zoneId, UUID branchId) {
        if (zoneId == null) return;
        var count = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch AND active")
                .param("id", zoneId).param("branch", branchId).query(Integer.class).single();
        if (count == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Zona inválida");
    }

    @Transactional
    public TableAdminView updateTable(UUID id, String label, int capacity, boolean active, UUID zoneId) {
        var scope = currentUser.require();
        ensureTableLabelAvailable(label, id, scope.branchId());
        ensureZone(zoneId, scope.branchId());
        if (!active && isOccupied(id, scope.branchId())) {
            throw new ApiException(HttpStatus.CONFLICT, "No se puede desactivar una mesa ocupada");
        }
        var updated = jdbc.sql("""
                UPDATE dining_tables SET label=:label,capacity=:capacity,active=:active,zone_id=:zone,updated_at=NOW()
                WHERE id=:id AND branch_id=:branch
                """).param("label", label.trim()).param("capacity", capacity).param("active", active).param("zone", zoneId)
                .param("id", id).param("branch", scope.branchId()).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Mesa no encontrada");
        return table(id, scope.branchId());
    }

    @Transactional
    public TableAdminView regenerateQr(UUID id) {
        var branchId = currentUser.require().branchId();
        if (isOccupied(id, branchId)) throw new ApiException(HttpStatus.CONFLICT,
                "Cierra la atención antes de regenerar el QR");
        var updated = jdbc.sql("UPDATE dining_tables SET qr_public_id=gen_random_uuid(),updated_at=NOW() WHERE id=:id AND branch_id=:branch")
                .param("id", id).param("branch", branchId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Mesa no encontrada");
        return table(id, branchId);
    }

    public List<UserAdminView> users() {
        var branchId = currentUser.require().branchId();
        return jdbc.sql(USER_SELECT + " WHERE branch_id=:branch AND role IN " + MANAGED_ROLES_SQL + " ORDER BY full_name")
                .param("branch", branchId).query(this::mapUser).list();
    }

    private static final String USER_SELECT = """
            SELECT id,full_name,email,role,active,pin_hash IS NOT NULL has_pin,can_counter,can_reservations,
                   password_hash IS NOT NULL has_password,
                   document_number,phone,job_title,hired_on FROM app_users
            """;

    private UserAdminView mapUser(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserAdminView(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("email"),
                rs.getString("role"), rs.getBoolean("active"), rs.getBoolean("has_pin"),
                rs.getBoolean("can_counter"), rs.getBoolean("can_reservations"),
                rs.getString("document_number"), rs.getString("phone"), rs.getString("job_title"),
                rs.getObject("hired_on", java.time.LocalDate.class), !NO_ACCESS.equals(rs.getString("role")),
                rs.getBoolean("has_password"));
    }

    /**
     * Asigna o retira el PIN de ingreso rápido. Dentro de una sucursal no puede repetirse, para que nadie
     * entre por error con la cuenta de otra persona en la tablet compartida.
     */
    @Transactional
    public UserAdminView setPin(UUID id, String pin) {
        var branchId = currentUser.require().branchId();
        var person = jdbc.sql("""
                SELECT full_name,role,password_hash IS NOT NULL has_password FROM app_users WHERE id=:id AND branch_id=:branch
                  AND role IN ('BRANCH_MANAGER','WAITER','KITCHEN','STAFF') FOR UPDATE
                """).param("id", id).param("branch", branchId)
                .query((rs, row) -> new Object[]{rs.getString("full_name"), rs.getString("role"), rs.getBoolean("has_password")})
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        if (pin == null && !NO_ACCESS.equals(person[1]) && !((Boolean) person[2])) {
            throw new ApiException(HttpStatus.CONFLICT, "Sin PIN no podría entrar al sistema. Cámbialo por otro o dale antes un correo y contraseña");
        }
        jdbc.sql("UPDATE app_users SET pin_hash=:hash,updated_at=NOW() WHERE id=:id")
                .param("hash", pin == null ? null : pinHash(pin, branchId, id)).param("id", id).update();
        audit.record("PIN_CHANGED", (pin == null ? "PIN retirado: " : "PIN asignado: ") + person[0], null, id);
        return users().stream().filter(user -> user.id().equals(id)).findFirst().orElseThrow();
    }

    /** Valida un PIN (4 a 6 dígitos, no predecible, único entre los activos de la sucursal) y lo devuelve cifrado. */
    private String pinHash(String pin, UUID branchId, UUID personId) {
        if (!pin.matches("\\d{4,6}")) throw new ApiException(HttpStatus.BAD_REQUEST, "El PIN debe tener de 4 a 6 dígitos");
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Elige un PIN menos predecible");
        }
        var hashes = jdbc.sql("""
                SELECT pin_hash FROM app_users WHERE branch_id=:branch AND id<>:id AND pin_hash IS NOT NULL AND active
                """).param("branch", branchId).param("id", personId).query(String.class).list();
        if (hashes.stream().anyMatch(hash -> passwordEncoder.matches(pin, hash))) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese PIN ya lo usa otra persona de la sucursal");
        }
        return passwordEncoder.encode(pin);
    }

    @Transactional
    public UserAdminView createUser(UserData data) {
        validateRole(data.role());
        var scope = currentUser.require(); var id = UUID.randomUUID();
        var access = !NO_ACCESS.equals(data.role());
        // Con acceso al sistema: entra con PIN en la tablet, con correo y contraseña desde su celular, o ambos.
        var withEmail = access && data.email() != null && !data.email().isBlank();
        var email = withEmail ? requireEmail(data.email(), null) : null;
        if (withEmail && (data.password() == null || data.password().length() < 8)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 8 caracteres");
        }
        var pin = blankToNull(data.pin());
        if (access && !withEmail && pin == null) throw new ApiException(HttpStatus.BAD_REQUEST, NEEDS_ENTRY);
        var document = cleanDocument(data.documentNumber(), null, scope.organizationId());
        jdbc.sql("""
                INSERT INTO app_users(id,organization_id,branch_id,full_name,email,password_hash,pin_hash,role,active,
                  can_counter,can_reservations,document_number,phone,job_title,hired_on)
                VALUES (:id,:org,:branch,:name,:email,:password,:pin,:role,:active,:counter,:reservations,
                  :document,:phone,:job,:hired)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("name", data.fullName().trim()).param("email", email)
                .param("password", withEmail ? passwordEncoder.encode(data.password()) : null)
                .param("pin", pin == null ? null : pinHash(pin, scope.branchId(), id)).param("role", data.role())
                .param("active", data.active()).param("counter", access && data.canCounter())
                .param("reservations", access && data.canReservations())
                .param("document", document).param("phone", blankToNull(data.phone()))
                .param("job", blankToNull(data.jobTitle())).param("hired", data.hiredOn()).update();
        audit.record("USER_CREATED", "Persona registrada: " + data.fullName().trim() + " (" + roleName(data.role())
                + (pin == null ? "" : ", con PIN") + ")", null, id);
        return user(id, scope.branchId());
    }

    @Transactional
    public UserAdminView updateUser(UUID id, UserData data) {
        validateRole(data.role());
        var scope = currentUser.require();
        // Solo se administra al personal de la sucursal; el dueño y la plataforma no se editan desde aquí.
        var current = jdbc.sql("""
                        SELECT role,password_hash IS NOT NULL has_password,pin_hash IS NOT NULL has_pin FROM app_users
                        WHERE id=:id AND branch_id=:branch AND role IN %s FOR UPDATE
                        """.formatted(MANAGED_ROLES_SQL)).param("id", id).param("branch", scope.branchId())
                .query((rs, row) -> new Object[]{rs.getString("role"), rs.getBoolean("has_password"), rs.getBoolean("has_pin")})
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        var access = !NO_ACCESS.equals(data.role());
        var withEmail = access && data.email() != null && !data.email().isBlank();
        var newPassword = withEmail && data.password() != null && !data.password().isBlank();
        var email = withEmail ? requireEmail(data.email(), id) : null;
        if (withEmail && !newPassword && !((Boolean) current[1])) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Junto con el correo, ponle una contraseña de al menos 8 caracteres");
        }
        if (newPassword && data.password().length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 8 caracteres");
        }
        var pin = blankToNull(data.pin());
        if (access && !withEmail && pin == null && !((Boolean) current[2])) throw new ApiException(HttpStatus.BAD_REQUEST, NEEDS_ENTRY);
        var document = cleanDocument(data.documentNumber(), id, scope.organizationId());
        jdbc.sql("""
                UPDATE app_users SET full_name=:name,email=:email,role=:role,active=:active,
                  can_counter=:counter,can_reservations=:reservations,
                  document_number=:document,phone=:phone,job_title=:job,hired_on=:hired,
                  password_hash=CASE WHEN :withEmail THEN COALESCE(CAST(:password AS VARCHAR),password_hash) ELSE NULL END,
                  pin_hash=COALESCE(CAST(:pin AS VARCHAR),pin_hash),
                  updated_at=NOW() WHERE id=:id
                """).param("name", data.fullName().trim()).param("email", email).param("role", data.role())
                .param("active", data.active()).param("counter", access && data.canCounter())
                .param("reservations", access && data.canReservations())
                .param("document", document).param("phone", blankToNull(data.phone()))
                .param("job", blankToNull(data.jobTitle())).param("hired", data.hiredOn())
                .param("withEmail", withEmail).param("password", newPassword ? passwordEncoder.encode(data.password()) : null)
                .param("pin", pin == null ? null : pinHash(pin, scope.branchId(), id))
                .param("id", id).update();
        // Al quitar el acceso, el correo o desactivar, sus sesiones abiertas dejan de servir.
        if (!withEmail || !data.active()) {
            jdbc.sql("UPDATE refresh_tokens SET revoked=TRUE WHERE user_id=:id AND NOT revoked").param("id", id).update();
        }
        audit.record("USER_UPDATED", "Persona actualizada: " + data.fullName().trim() + " (" + roleName(data.role())
                + (data.active() ? "" : ", inactivo") + (newPassword ? ", contraseña cambiada" : "") + (pin == null ? "" : ", PIN cambiado")
                + (!access && !NO_ACCESS.equals(current[0]) ? ", acceso al sistema retirado" : "") + ")", null, id);
        return user(id, scope.branchId());
    }

    private UserAdminView user(UUID id, UUID branchId) {
        return jdbc.sql(USER_SELECT + " WHERE id=:id AND branch_id=:branch").param("id", id).param("branch", branchId)
                .query(this::mapUser).single();
    }

    private static final String NEEDS_ENTRY = "Para que pueda entrar al sistema, ponle un PIN o un correo con contraseña";

    private String requireEmail(String email, UUID excludedId) {
        if (email == null || !email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe un correo válido");
        }
        ensureEmailAvailable(email, excludedId);
        return email.trim().toLowerCase();
    }

    /** DNI (8 dígitos) o carné de extranjería / pasaporte (8 a 12 letras o números). Opcional. */
    private String cleanDocument(String value, UUID excludedId, UUID organizationId) {
        if (value == null || value.isBlank()) return null;
        var document = value.trim().toUpperCase();
        if (!document.matches("[A-Z0-9]{8,12}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El documento debe tener de 8 a 12 letras o números, sin espacios");
        }
        var taken = jdbc.sql("""
                SELECT count(*) FROM app_users WHERE organization_id=:org AND upper(document_number)=:document
                  AND (CAST(:excluded AS UUID) IS NULL OR id<>:excluded)
                """).param("org", organizationId).param("document", document).param("excluded", excludedId)
                .query(Integer.class).single();
        if (taken > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya hay una persona registrada con ese documento");
        return document;
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String roleName(String role) {
        return switch (role) {
            case "BRANCH_MANAGER" -> "encargado"; case "WAITER" -> "mesero"; case "KITCHEN" -> "cocina";
            default -> "sin acceso al sistema";
        };
    }

    private TableAdminView table(UUID id, UUID branchId) {
        return jdbc.sql("""
                        SELECT t.id,t.label,t.capacity,t.qr_public_id,t.active,t.zone_id,
                          EXISTS(SELECT 1 FROM table_sessions ts WHERE ts.table_id=t.id AND ts.status='OPEN') occupied
                        FROM dining_tables t WHERE t.id=:id AND t.branch_id=:branch
                        """).param("id", id).param("branch", branchId).query((rs, row) -> new TableAdminView(
                        rs.getObject("id", UUID.class), rs.getString("label"), rs.getInt("capacity"),
                        rs.getObject("qr_public_id", UUID.class), rs.getBoolean("active"), rs.getBoolean("occupied"),
                        rs.getObject("zone_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mesa no encontrada"));
    }

    private boolean isOccupied(UUID id, UUID branchId) {
        return jdbc.sql("""
                SELECT count(*) FROM table_sessions ts JOIN dining_tables t ON t.id=ts.table_id
                WHERE t.id=:id AND t.branch_id=:branch AND ts.status='OPEN'
                """).param("id", id).param("branch", branchId).query(Integer.class).single() > 0;
    }

    private void ensureTableLabelAvailable(String label, UUID excludedId, UUID branchId) {
        var count = excludedId == null
                ? jdbc.sql("SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND lower(label)=lower(:label)")
                    .param("branch", branchId).param("label", label.trim()).query(Integer.class).single()
                : jdbc.sql("SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND lower(label)=lower(:label) AND id<>:excluded")
                    .param("branch", branchId).param("label", label.trim()).param("excluded", excludedId)
                    .query(Integer.class).single();
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe una mesa con ese nombre");
    }

    private void ensureEmailAvailable(String email, UUID excludedId) {
        var count = excludedId == null
                ? jdbc.sql("SELECT count(*) FROM app_users WHERE lower(email)=lower(:email)")
                    .param("email", email.trim()).query(Integer.class).single()
                : jdbc.sql("SELECT count(*) FROM app_users WHERE lower(email)=lower(:email) AND id<>:excluded")
                    .param("email", email.trim()).param("excluded", excludedId).query(Integer.class).single();
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "El correo ya está registrado");
    }

    private void validateRole(String role) {
        if (!STAFF_ROLES.contains(role)) throw new ApiException(HttpStatus.BAD_REQUEST, "Rol inválido");
    }

    public record TableAdminView(UUID id, String label, int capacity, UUID qrPublicId,
                                 boolean active, boolean occupied, UUID zoneId) {}
    public record UserData(String fullName, String email, String password, String role, boolean active,
                           boolean canCounter, boolean canReservations, String documentNumber, String phone,
                           String jobTitle, java.time.LocalDate hiredOn, String pin) {}
    /** systemAccess: falso para el personal que solo figura en el registro (rol STAFF). */
    public record UserAdminView(UUID id, String fullName, String email, String role, boolean active,
                                boolean hasPin, boolean canCounter, boolean canReservations,
                                String documentNumber, String phone, String jobTitle, java.time.LocalDate hiredOn,
                                boolean systemAccess, boolean hasPassword) {}
}
