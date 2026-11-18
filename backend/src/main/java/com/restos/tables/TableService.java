package com.restos.tables;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.restos.audit.AuditService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;

/** Gestiona el ciclo de vida de la mesa y la sesión temporal utilizada por los comensales. */
@Service
public class TableService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final int staleReviewHours;
    private final AuditService audit;
    private final com.restos.waiters.WaiterAssignmentService assignments;
    private final com.restos.waiters.WaiterAvailability availability;

    public TableService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime,
            @Value("${restos.sessions.stale-review-hours:12}") int staleReviewHours, AuditService audit,
            com.restos.waiters.WaiterAssignmentService assignments, com.restos.waiters.WaiterAvailability availability) {
        this.audit = audit;
        this.assignments = assignments;
        this.availability = availability;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.staleReviewHours = staleReviewHours;
    }

    public List<TableView> listBranchTables() {
        var scope = currentUser.require();
        // Quién atiende cada mesa y si está en turno (para mostrar «Lucía · en refrigerio»).
        var onShift = availability.onShift(scope.branchId());
        return jdbc.sql("""
                        SELECT t.id,t.label,t.capacity,t.qr_public_id,
                               COALESCE(ts.status,'FREE') status,ts.id session_id,
                               (COALESCE(ts.requires_review,FALSE) OR (
                                 ts.id IS NOT NULL
                                 AND COALESCE(ts.reviewed_at,ts.opened_at) < NOW() - (:hours * INTERVAL '1 hour')
                                 AND EXISTS (SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status NOT IN ('DRAFT','CANCELLED'))
                               )) requires_review,ts.updated_at,t.zone_id,ts.opened_at,t.waiter_id,
                               ts.waiter_id served_by_id,w.full_name served_by_name
                        FROM dining_tables t
                        LEFT JOIN table_sessions ts ON ts.table_id=t.id AND ts.status='OPEN'
                        LEFT JOIN app_users w ON w.id=ts.waiter_id
                        WHERE t.branch_id=:branch AND t.active ORDER BY t.label
                        """).param("branch", scope.branchId()).param("hours", staleReviewHours)
                .query((rs, row) -> new TableView(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getInt("capacity"), rs.getObject("qr_public_id", UUID.class),
                        rs.getString("status"), rs.getObject("session_id", UUID.class),
                        rs.getBoolean("requires_review"),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("zone_id", UUID.class),
                        rs.getTimestamp("opened_at") == null ? null : rs.getTimestamp("opened_at").toInstant(),
                        rs.getObject("waiter_id", UUID.class), rs.getObject("served_by_id", UUID.class),
                        rs.getString("served_by_name"),
                        rs.getObject("served_by_id", UUID.class) != null
                                && !availability.isAvailable(onShift, rs.getObject("served_by_id", UUID.class)))).list();
    }

    @Transactional
    public SessionOpened open(UUID tableId) {
        var scope = currentUser.require();
        var belongs = jdbc.sql("SELECT count(*) FROM dining_tables WHERE id=:id AND branch_id=:branch AND active")
                .param("id", tableId).param("branch", scope.branchId()).query(Integer.class).single();
        if (belongs == 0) throw new ApiException(HttpStatus.NOT_FOUND, "La mesa no existe en esta sucursal");
        var existing = jdbc.sql("SELECT id FROM table_sessions WHERE table_id=:table AND status='OPEN'")
                .param("table", tableId).query(UUID.class).optional();
        if (existing.isPresent()) throw new ApiException(HttpStatus.CONFLICT, "La mesa ya está abierta");

        var sessionId = UUID.randomUUID();
        var guestToken = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO table_sessions(id,organization_id,branch_id,table_id,status,guest_token,opened_by)
                VALUES (:id,:org,:branch,:table,'OPEN',:token,:user)
                """).param("id", sessionId).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("table", tableId).param("token", guestToken).param("user", scope.userId()).update();
        assignments.assignOnOpen(sessionId, scope.userId());
        realtime.waiters(scope.branchId(), "TABLE_SESSION_OPENED", new SessionOpened(sessionId, guestToken));
        return new SessionOpened(sessionId, guestToken);
    }

    @Transactional
    public GuestJoined join(UUID qrPublicId, String displayName, String mode) {
        // Se bloquea la mesa para impedir que dos escaneos simultáneos creen sesiones distintas.
        var table = jdbc.sql("""
                        SELECT t.id table_id,t.organization_id,t.branch_id,ts.id session_id,ts.guest_token,
                               (COALESCE(ts.requires_review,FALSE) OR (
                                 ts.id IS NOT NULL
                                 AND COALESCE(ts.reviewed_at,ts.opened_at) < NOW() - (:hours * INTERVAL '1 hour')
                                 AND EXISTS (SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status NOT IN ('DRAFT','CANCELLED'))
                               )) requires_review,COALESCE(z.open,TRUE) zone_open
                        FROM dining_tables t
                        LEFT JOIN table_sessions ts ON ts.table_id=t.id AND ts.status='OPEN'
                        LEFT JOIN dining_zones z ON z.id=t.zone_id AND z.active
                        WHERE t.qr_public_id=:qr AND t.active
                        FOR UPDATE OF t
                        """).param("qr", qrPublicId).param("hours", staleReviewHours)
                .query((rs, row) -> new QrTable(rs.getObject("table_id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getObject("guest_token", UUID.class),
                        rs.getBoolean("requires_review"), rs.getBoolean("zone_open")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El código QR no corresponde a una mesa activa"));
        // Una zona cerrada (terraza en día de lluvia, segundo piso sin atención) no abre mesas nuevas por QR.
        if (table.sessionId() == null && !table.zoneOpen()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Esta zona está cerrada en este momento. Pide al personal que te ubique en otra mesa");
        }

        if (table.sessionId() != null && table.requiresReview()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Esta mesa conserva una atención anterior. Solicita al personal que la revise antes de ingresar");
        }

        var session = table.sessionId() == null ? createAutomaticSession(table)
                : new OpenSession(table.sessionId(), table.guestToken(), table.branchId());
        // Mismo nombre que alguien de la mesa: suele ser la misma persona que perdió la página o abrió el QR
        // con otro navegador. Se pregunta «¿eres tú?» en vez de crear un comensal repetido.
        if (table.sessionId() != null && !"NEW".equals(mode)) {
            var sameName = jdbc.sql("""
                            SELECT id,is_host FROM guests WHERE session_id=:session AND active
                              AND lower(trim(display_name))=lower(trim(:name)) ORDER BY created_at LIMIT 1
                            """).param("session", session.id()).param("name", displayName)
                    .query((rs, row) -> new GuestIdentity(rs.getObject("id", UUID.class), rs.getBoolean("is_host"))).optional();
            if (sameName.isPresent() && "RECLAIM".equals(mode)) {
                // Nuevo token: si en otro teléfono seguía abierta, esa pantalla vuelve a pedir el nombre.
                var newToken = UUID.randomUUID();
                jdbc.sql("UPDATE guests SET guest_token=:token,identity_claimed=TRUE WHERE id=:id")
                        .param("token", newToken).param("id", sameName.get().id()).update();
                var name = jdbc.sql("SELECT display_name FROM guests WHERE id=:id").param("id", sameName.get().id())
                        .query(String.class).single();
                return new GuestJoined(session.id(), sameName.get().id(), session.token(), newToken, name, sameName.get().host());
            }
            if (sameName.isPresent()) throw new ApiException(HttpStatus.CONFLICT,
                    "Ya hay alguien llamado " + displayName.trim() + " en esta mesa", "SAME_NAME");
        }
        var guestId = UUID.randomUUID();
        var guestToken = UUID.randomUUID();
        var guestCount = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                .param("session", session.id()).query(Integer.class).single();
        var host = guestCount == 0;
        jdbc.sql("""
                INSERT INTO guests(id,session_id,display_name,is_host,guest_token,identity_claimed)
                VALUES (:id,:session,:name,:host,:guestToken,TRUE)
                """)
                .param("id", guestId).param("session", session.id()).param("name", displayName.trim())
                .param("host", host).param("guestToken", guestToken).update();
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id=:session")
                .param("session", session.id()).update();
        jdbc.sql("""
                UPDATE service_requests SET status='RESOLVED',handled_at=NOW(),updated_at=NOW()
                WHERE session_id=:session AND type='TABLE_EMPTY' AND status IN ('PENDING','IN_PROGRESS')
                """).param("session", session.id()).update();
        var response = new GuestJoined(session.id(), guestId, session.token(), guestToken,
                displayName.trim(), host);
        realtime.table(session.id(), "TABLE_GUEST_JOINED", response);
        // El salón debe enterarse sin recargar cuando una persona ocupa o se une a la mesa.
        realtime.waiters(session.branchId(), "TABLE_GUEST_JOINED", response);
        return response;
    }

    /** El primer escaneo del QR permanente ocupa la mesa sin intervención del personal. */
    private OpenSession createAutomaticSession(QrTable table) {
        var sessionId = UUID.randomUUID();
        var guestToken = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO table_sessions(id,organization_id,branch_id,table_id,status,guest_token,opened_source)
                VALUES (:id,:org,:branch,:table,'OPEN',:token,'QR')
                """).param("id", sessionId).param("org", table.organizationId())
                .param("branch", table.branchId()).param("table", table.tableId())
                .param("token", guestToken).update();
        assignments.assignOnOpen(sessionId, null);
        var opened = new SessionOpened(sessionId, guestToken);
        realtime.waiters(table.branchId(), "TABLE_SESSION_OPENED", opened);
        return new OpenSession(sessionId, guestToken, table.branchId());
    }

    public void verifyTableToken(UUID sessionId, UUID token) {
        var session = jdbc.sql("""
                SELECT requires_review FROM table_sessions
                WHERE id=:id AND guest_token=:token AND status='OPEN'
                """).param("id", sessionId).param("token", token).query(Boolean.class).optional();
        if (session.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesión de mesa no es válida");
        if (session.get()) throw new ApiException(HttpStatus.CONFLICT,
                "La atención está pendiente de revisión por el personal");
    }

    /** Resuelve la identidad del comensal; el cliente nunca decide sobre qué invitado actúa. */
    public GuestIdentity requireGuest(UUID sessionId, UUID tableToken, UUID guestToken) {
        verifyTableToken(sessionId, tableToken);
        return jdbc.sql("""
                        SELECT id,is_host FROM guests
                        WHERE session_id=:session AND guest_token=:guestToken AND active
                        """).param("session", sessionId).param("guestToken", guestToken)
                .query((rs, row) -> new GuestIdentity(rs.getObject("id", UUID.class), rs.getBoolean("is_host")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "La identidad del comensal no es válida"));
    }

    /** Compatibilidad de una sola vez para sesiones abiertas antes de introducir el token individual. */
    @Transactional
    public GuestIdentityClaim claimLegacyIdentity(UUID sessionId, UUID tableToken, UUID guestId) {
        lockOpenSession(sessionId, tableToken);
        var legacy = jdbc.sql("""
                        SELECT guest_token,identity_claimed FROM guests
                        WHERE id=:guest AND session_id=:session FOR UPDATE
                        """).param("guest", guestId).param("session", sessionId)
                .query((rs, row) -> new LegacyIdentity(rs.getObject("guest_token", UUID.class),
                        rs.getBoolean("identity_claimed")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Comensal no encontrado"));
        if (legacy.claimed()) throw new ApiException(HttpStatus.CONFLICT, "La identidad ya fue recuperada");
        jdbc.sql("UPDATE guests SET identity_claimed=TRUE,updated_at=NOW() WHERE id=:guest")
                .param("guest", guestId).update();
        return new GuestIdentityClaim(legacy.token());
    }

    /** Bloqueo común para serializar carrito y envío de rondas de una misma mesa. */
    public void lockOpenSession(UUID sessionId, UUID tableToken) {
        var valid = jdbc.sql("""
                        SELECT id FROM table_sessions
                        WHERE id=:session AND guest_token=:token AND status='OPEN' FOR UPDATE
                        """).param("session", sessionId).param("token", tableToken)
                .query(UUID.class).optional();
        if (valid.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesión de mesa no es válida");
    }

    @Transactional
    public SessionClosed cancel(UUID sessionId, String reason) {
        var scope = currentUser.require();
        var context = jdbc.sql("""
                        SELECT id,branch_id,requires_review FROM table_sessions
                        WHERE id=:session AND branch_id=:branch AND status='OPEN' FOR UPDATE
                        """).param("session", sessionId).param("branch", scope.branchId())
                .query((rs, row) -> new CancelContext(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getBoolean("requires_review")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Atención no encontrada"));
        var submittedOrders = jdbc.sql("""
                SELECT count(*) FROM orders WHERE session_id=:session AND status NOT IN ('DRAFT','CANCELLED')
                """).param("session", sessionId).query(Integer.class).single();
        if (submittedOrders > 0 && !context.requiresReview()) throw new ApiException(HttpStatus.CONFLICT,
                "La mesa ya tiene pedidos enviados; debe completar la entrega y registrar el pago");
        jdbc.sql("UPDATE orders SET status='CANCELLED',updated_at=NOW() WHERE session_id=:session AND status<>'CANCELLED'")
                .param("session", sessionId).update();
        jdbc.sql("""
                UPDATE service_requests SET status='RESOLVED',handled_by=:user,handled_at=NOW(),updated_at=NOW()
                WHERE session_id=:session AND status IN ('PENDING','IN_PROGRESS')
                """).param("user", scope.userId()).param("session", sessionId).update();
        jdbc.sql("""
                UPDATE table_sessions SET status='CANCELLED',closed_at=NOW(),closed_by=:user,
                  close_reason=:reason,updated_at=NOW() WHERE id=:session
                """).param("user", scope.userId()).param("reason", normalizeReason(reason))
                .param("session", sessionId).update();
        var response = new SessionClosed(sessionId, "CANCELLED", normalizeReason(reason));
        var label = jdbc.sql("""
                SELECT COALESCE(t.label,ts.label) FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=:session
                """).param("session", sessionId).query(String.class).single();
        audit.record("SESSION_CANCELLED", "Atención cancelada · " + label
                + (normalizeReason(reason) == null ? "" : " · " + normalizeReason(reason)), null, sessionId);
        realtime.table(sessionId, "TABLE_SESSION_CANCELLED", response);
        realtime.waiters(context.branchId(), "TABLE_SESSION_CANCELLED", response);
        return response;
    }

    /** Desbloquea una atención revisada conservando todos sus pedidos y participantes. */
    @Transactional
    public SessionReviewed reactivate(UUID sessionId) {
        var scope = currentUser.require();
        var context = jdbc.sql("""
                SELECT id,branch_id,requires_review FROM table_sessions
                WHERE id=:session AND branch_id=:branch AND status='OPEN' FOR UPDATE
                """).param("session", sessionId).param("branch", scope.branchId())
                .query((rs, row) -> new CancelContext(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getBoolean("requires_review")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Atención no encontrada"));
        if (!context.requiresReview()) throw new ApiException(HttpStatus.CONFLICT,
                "La atención no requiere revisión");
        jdbc.sql("""
                UPDATE table_sessions SET requires_review=FALSE,reviewed_at=NOW(),reviewed_by=:user,
                  updated_at=NOW() WHERE id=:session
                """).param("user", scope.userId()).param("session", sessionId).update();
        var response = new SessionReviewed(sessionId, scope.userId());
        realtime.table(sessionId, "TABLE_SESSION_REACTIVATED", response);
        realtime.waiters(context.branchId(), "TABLE_SESSION_REACTIVATED", response);
        return response;
    }

    @Transactional
    public HostTransferred transferHost(UUID sessionId, UUID guestId) {
        var scope = currentUser.require();
        var context = jdbc.sql("""
                        SELECT id,branch_id FROM table_sessions
                        WHERE id=:session AND branch_id=:branch AND status='OPEN' FOR UPDATE
                        """).param("session", sessionId).param("branch", scope.branchId())
                .query((rs, row) -> new CancelContext(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), false)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Atención no encontrada"));
        var guestName = jdbc.sql("SELECT display_name FROM guests WHERE id=:guest AND session_id=:session AND active")
                .param("guest", guestId).param("session", sessionId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "El comensal no pertenece a la mesa"));
        jdbc.sql("UPDATE guests SET is_host=FALSE,updated_at=NOW() WHERE session_id=:session")
                .param("session", sessionId).update();
        jdbc.sql("UPDATE guests SET is_host=TRUE,updated_at=NOW() WHERE id=:guest")
                .param("guest", guestId).update();
        var response = new HostTransferred(sessionId, guestId, guestName);
        realtime.table(sessionId, "HOST_TRANSFERRED", response);
        realtime.waiters(context.branchId(), "HOST_TRANSFERRED", response);
        return response;
    }

    /** Permite que un comensal abandone la experiencia sin borrar sus consumos ni el historial. */
    @Transactional
    public GuestLeft leave(UUID sessionId, UUID tableToken, UUID guestToken) {
        lockOpenSession(sessionId, tableToken);
        var guest = requireGuest(sessionId, tableToken, guestToken);
        var activeGuests = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                .param("session", sessionId).query(Integer.class).single();
        var submittedOrders = jdbc.sql("""
                SELECT count(*) FROM orders
                WHERE session_id=:session AND status NOT IN ('DRAFT','CANCELLED')
                """).param("session", sessionId).query(Integer.class).single();
        if (activeGuests == 1 && submittedOrders > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Eres el último comensal y existen pedidos enviados. Solicita la cuenta o llama al mesero antes de salir");
        }
        return retire(sessionId, guest.id(), null, "Salida solicitada por el comensal");
    }

    /** Retiro operativo usado para duplicados o personas que ya abandonaron físicamente la mesa. */
    @Transactional
    public GuestLeft retireByStaff(UUID sessionId, UUID guestId, String reason) {
        var scope = currentUser.require();
        var belongs = jdbc.sql("""
                SELECT count(*) FROM table_sessions
                WHERE id=:session AND branch_id=:branch AND status='OPEN'
                """).param("session", sessionId).param("branch", scope.branchId())
                .query(Integer.class).single();
        if (belongs == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Atención no encontrada");
        lockSessionForStaff(sessionId);
        return retire(sessionId, guestId, scope.userId(),
                reason == null || reason.isBlank() ? "Retirado por el personal" : reason.trim());
    }

    private GuestLeft retire(UUID sessionId, UUID guestId, UUID staffUserId, String reason) {
        var guest = jdbc.sql("""
                        SELECT display_name,is_host FROM guests
                        WHERE id=:guest AND session_id=:session AND active FOR UPDATE
                        """).param("guest", guestId).param("session", sessionId)
                .query((rs, row) -> new RetiringGuest(rs.getString("display_name"), rs.getBoolean("is_host")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "El comensal ya no está activo en esta mesa"));
        var draftItems = jdbc.sql("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.session_id=:session AND oi.guest_id=:guest AND o.status='DRAFT'
                """).param("session", sessionId).param("guest", guestId).query(Integer.class).single();
        if (draftItems > 0) throw new ApiException(HttpStatus.CONFLICT,
                "El comensal debe quitar o transferir sus productos en selección antes de salir");

        jdbc.sql("""
                UPDATE guests SET active=FALSE,is_host=FALSE,left_at=NOW(),left_reason=:reason,
                  left_by=:staff,updated_at=NOW() WHERE id=:guest
                """).param("reason", reason).param("staff", staffUserId).param("guest", guestId).update();

        UUID newHostId = null;
        if (guest.host() || !hasActiveHost(sessionId)) {
            newHostId = jdbc.sql("""
                    SELECT id FROM guests WHERE session_id=:session AND active
                    ORDER BY created_at LIMIT 1
                    """).param("session", sessionId).query(UUID.class).optional().orElse(null);
            if (newHostId != null) {
                jdbc.sql("UPDATE guests SET is_host=TRUE,updated_at=NOW() WHERE id=:guest")
                        .param("guest", newHostId).update();
            }
        }
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id=:session")
                .param("session", sessionId).update();
        var context = jdbc.sql("SELECT branch_id FROM table_sessions WHERE id=:session")
                .param("session", sessionId).query(UUID.class).single();
        var activeGuests = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                .param("session", sessionId).query(Integer.class).single();
        var submittedOrders = jdbc.sql("""
                SELECT count(*) FROM orders WHERE session_id=:session AND status NOT IN ('DRAFT','CANCELLED')
                """).param("session", sessionId).query(Integer.class).single();

        if (activeGuests == 0 && submittedOrders > 0) createEmptyTableAlert(sessionId, context);
        if (activeGuests == 0 && submittedOrders == 0) {
            // Una mesa vacía sin consumo debe liberarse inmediatamente para el siguiente QR.
            jdbc.sql("UPDATE orders SET status='CANCELLED',updated_at=NOW() WHERE session_id=:session AND status='DRAFT'")
                    .param("session", sessionId).update();
            jdbc.sql("""
                    UPDATE table_sessions SET status='CANCELLED',closed_at=NOW(),
                      close_reason='Último comensal salió sin consumo',updated_at=NOW()
                    WHERE id=:session
                    """).param("session", sessionId).update();
        }
        var response = new GuestLeft(sessionId, guestId, guest.name(), activeGuests, newHostId,
                activeGuests == 0 && submittedOrders == 0);
        realtime.table(sessionId, "TABLE_GUEST_LEFT", response);
        realtime.waiters(context, "TABLE_GUEST_LEFT", response);
        if (response.eligibleForAutomaticClosure()) {
            realtime.waiters(context, "TABLE_SESSION_CANCELLED", response);
        }
        return response;
    }

    private void lockSessionForStaff(UUID sessionId) {
        jdbc.sql("SELECT id FROM table_sessions WHERE id=:session AND status='OPEN' FOR UPDATE")
                .param("session", sessionId).query(UUID.class).single();
    }

    private boolean hasActiveHost(UUID sessionId) {
        return jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active AND is_host")
                .param("session", sessionId).query(Integer.class).single() > 0;
    }

    private void createEmptyTableAlert(UUID sessionId, UUID branchId) {
        var exists = jdbc.sql("""
                SELECT count(*) FROM service_requests
                WHERE session_id=:session AND type='TABLE_EMPTY' AND status IN ('PENDING','IN_PROGRESS')
                """).param("session", sessionId).query(Integer.class).single();
        if (exists > 0) return;
        var organizationId = jdbc.sql("SELECT organization_id FROM table_sessions WHERE id=:session")
                .param("session", sessionId).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO service_requests(organization_id,branch_id,session_id,type,status)
                VALUES (:organization,:branch,:session,'TABLE_EMPTY','PENDING')
                """).param("organization", organizationId).param("branch", branchId)
                .param("session", sessionId).update();
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "Cancelada por el personal" : reason.trim();
    }

    private record OpenSession(UUID id, UUID token, UUID branchId) {}
    private record CancelContext(UUID sessionId, UUID branchId, boolean requiresReview) {}
    private record LegacyIdentity(UUID token, boolean claimed) {}
    private record RetiringGuest(String name, boolean host) {}
    private record QrTable(UUID tableId, UUID organizationId, UUID branchId, UUID sessionId,
                           UUID guestToken, boolean requiresReview, boolean zoneOpen) {}
    public record TableView(UUID id, String label, int capacity, UUID qrPublicId, String status, UUID sessionId,
                            boolean requiresReview, java.time.Instant lastActivityAt, UUID zoneId,
                            java.time.Instant openedAt, UUID waiterId, UUID servedById, String servedByName,
                            boolean servedByAway) {}
    public record SessionOpened(UUID sessionId, UUID tableToken) {}
    public record GuestIdentity(UUID id, boolean host) {}
    public record GuestIdentityClaim(UUID guestToken) {}
    public record GuestJoined(UUID sessionId, UUID guestId, UUID tableToken, UUID guestToken,
                              String displayName, boolean host) {}
    public record SessionClosed(UUID sessionId, String status, String reason) {}
    public record SessionReviewed(UUID sessionId, UUID reviewedBy) {}
    public record HostTransferred(UUID sessionId, UUID guestId, String displayName) {}
    public record GuestLeft(UUID sessionId, UUID guestId, String displayName, int activeGuests,
                            UUID newHostId, boolean eligibleForAutomaticClosure) {}
}
