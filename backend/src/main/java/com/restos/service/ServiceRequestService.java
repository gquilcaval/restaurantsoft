package com.restos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import com.restos.tables.TableService;

/** Registra llamadas y solicitudes de cuenta evitando alertas duplicadas por mesa. */
@Service
public class ServiceRequestService {
    private final JdbcClient jdbc;
    private final TableService tables;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;

    private final com.restos.waiters.WaiterAssignmentService assignments;

    public ServiceRequestService(JdbcClient jdbc, TableService tables, CurrentUser currentUser,
                                 RealtimePublisher realtime, com.restos.waiters.WaiterAssignmentService assignments) {
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.tables = tables;
        this.currentUser = currentUser;
        this.realtime = realtime;
    }

    @Transactional
    public RequestView create(UUID sessionId, UUID tableToken, UUID guestToken, String type) {
        return create(sessionId, tableToken, guestToken, type, null, null, null, null);
    }

    /**
     * Crea una llamada o un pedido de cuenta. Al pedir la cuenta, la mesa puede indicar cómo quiere pagar
     * (una sola cuenta, cada uno lo suyo o partes iguales) y la propina sugerida; el mesero lo ve al cobrar.
     */
    @Transactional
    public RequestView create(UUID sessionId, UUID tableToken, UUID guestToken, String type,
                              String splitMode, Integer splitParts, Integer tipPercent, String reason) {
        var cleanReason = type.equals("WAITER_CALL") && reason != null
                && java.util.List.of("CUTLERY", "DRINK", "QUESTION", "PROBLEM").contains(reason) ? reason : null;
        tables.requireGuest(sessionId, tableToken, guestToken);
        if (!type.equals("WAITER_CALL") && !type.equals("BILL")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tipo de solicitud inválido");
        }
        // El bloqueo serializa pulsaciones simultáneas de varios comensales de la misma mesa.
        var context = jdbc.sql("SELECT organization_id,branch_id FROM table_sessions WHERE id=:session FOR UPDATE")
                .param("session", sessionId)
                .query((rs, row) -> new Context(rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class))).single();
        // Solicitar atención es idempotente: una segunda pulsación devuelve la solicitud activa.
        var active = findActive(sessionId, type);
        if (active.isPresent()) return active.get();
        String note = null;
        if (type.equals("BILL") && splitMode != null) {
            if (!java.util.List.of("SINGLE", "GUEST", "EQUAL").contains(splitMode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Forma de pago inválida");
            }
            var parts = splitMode.equals("EQUAL") ? Math.max(2, Math.min(30, splitParts == null ? 2 : splitParts)) : null;
            var tip = tipPercent == null ? 0 : Math.max(0, Math.min(50, tipPercent));
            if (splitMode.equals("GUEST")) {
                var guests = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                        .param("session", sessionId).query(Integer.class).single();
                note = guests > 1 ? guests + " pagos separados (cada uno lo suyo)" : "Cada uno paga lo suyo";
            } else if (splitMode.equals("EQUAL")) {
                note = parts + " pagos iguales";
            } else {
                note = "Una sola cuenta";
            }
            if (tip > 0) note += " · propina " + tip + " %";
            jdbc.sql("""
                    UPDATE table_sessions SET bill_split_mode=:mode,bill_split_parts=:parts,bill_tip_percent=:tip,
                      bill_note=:note WHERE id=:session
                    """).param("mode", splitMode).param("parts", parts).param("tip", tip).param("note", note)
                    .param("session", sessionId).update();
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO service_requests(id,organization_id,branch_id,session_id,type,status,reason)
                VALUES (:id,:org,:branch,:session,:type,'PENDING',:reason)
                """).param("id", id).param("org", context.organizationId()).param("branch", context.branchId())
                .param("session", sessionId).param("type", type).param("reason", cleanReason).update();
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id=:session")
                .param("session", sessionId).update();
        var label = jdbc.sql("SELECT COALESCE(t.label,ts.label) FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE ts.id=:session")
                .param("session", sessionId).query(String.class).single();
        var view = new RequestView(id, sessionId, label, type, "PENDING", Instant.now(), note, null, cleanReason, 0, null);
        realtime.waiters(context.branchId(), type.equals("BILL") ? "BILL_REQUESTED" : "WAITER_CALLED", view);
        realtime.table(sessionId, "SERVICE_REQUEST_CREATED", view);
        return view;
    }

    /** Permite que el comensal recupere el estado de los avisos después de recargar la página. */
    public List<RequestView> activeForSession(UUID sessionId, UUID tableToken, UUID guestToken) {
        tables.requireGuest(sessionId, tableToken, guestToken);
        return jdbc.sql("""
                        SELECT sr.id,sr.session_id,COALESCE(t.label,ts.label) table_label,sr.type,sr.status,sr.requested_at,ts.bill_note,sr.reason,sr.escalation_level,sr.attended_at,
                               (SELECT u.full_name FROM app_users u WHERE u.id=sr.handled_by) waiter_name
                        FROM service_requests sr
                        JOIN table_sessions ts ON ts.id=sr.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE sr.session_id=:session AND sr.status IN ('PENDING','IN_PROGRESS')
                        ORDER BY sr.requested_at
                        """).param("session", sessionId).query(this::mapRequest).list();
    }

    public List<RequestView> pending() {
        var branchId = currentUser.require().branchId();
        return jdbc.sql("""
                        SELECT sr.id,sr.session_id,COALESCE(t.label,ts.label) table_label,sr.type,sr.status,sr.requested_at,ts.bill_note,sr.reason,sr.escalation_level,sr.attended_at,
                               (SELECT u.full_name FROM app_users u WHERE u.id=sr.handled_by) waiter_name
                        FROM service_requests sr JOIN table_sessions ts ON ts.id=sr.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE sr.branch_id=:branch AND sr.status IN ('PENDING','IN_PROGRESS')
                          AND ts.table_id IS NOT NULL ORDER BY sr.requested_at
                        """).param("branch", branchId)
                .query(this::mapRequest).list();
    }

    @Transactional
    public RequestView handle(UUID requestId, String status) {
        var scope = currentUser.require();
        if (!status.equals("IN_PROGRESS") && !status.equals("RESOLVED")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Estado de solicitud inválido");
        }
        var sessionId = jdbc.sql("SELECT session_id FROM service_requests WHERE id=:id AND branch_id=:branch")
                .param("id", requestId).param("branch", scope.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
        var requestType = jdbc.sql("SELECT type FROM service_requests WHERE id=:id")
                .param("id", requestId).query(String.class).single();
        if (status.equals("RESOLVED") && requestType.equals("BILL")) {
            var payments = jdbc.sql("SELECT count(*) FROM payments WHERE session_id=:session")
                    .param("session", sessionId).query(Integer.class).single();
            if (payments > 0) throw new ApiException(HttpStatus.CONFLICT,
                    "La cuenta ya tiene pagos registrados y no puede reabrirse");
        }
        jdbc.sql("""
                UPDATE service_requests SET status=:status,handled_by=:user,
                  attended_at=CASE WHEN :status='IN_PROGRESS' THEN NOW() ELSE COALESCE(attended_at,NOW()) END,
                  escalation_level=CASE WHEN :status='IN_PROGRESS' THEN 0 ELSE escalation_level END,
                  handled_at=CASE WHEN :status='RESOLVED' THEN NOW() ELSE handled_at END,updated_at=NOW()
                WHERE id=:id
                """).param("status", status).param("user", scope.userId()).param("id", requestId).update();
        if (status.equals("IN_PROGRESS")) assignments.claimIfUnassigned(sessionId);
        var details = jdbc.sql("""
                SELECT COALESCE(t.label,ts.label),sr.type FROM service_requests sr JOIN table_sessions ts ON ts.id=sr.session_id
                LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE sr.id=:id
                """).param("id", requestId).query((rs, row) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        if (status.equals("RESOLVED") && requestType.equals("BILL")) {
            jdbc.sql("""
                    UPDATE table_sessions SET bill_split_mode=NULL,bill_split_parts=NULL,bill_tip_percent=NULL,bill_note=NULL
                    WHERE id=:session AND status='OPEN'
                    """).param("session", sessionId).update();
        }
        var waiterName = jdbc.sql("SELECT full_name FROM app_users WHERE id=:id").param("id", scope.userId())
                .query(String.class).optional().orElse(null);
        var view = viewOf(requestId, status.equals("IN_PROGRESS") ? waiterName : null);
        var event = status.equals("RESOLVED") && requestType.equals("BILL")
                ? "BILL_CANCELLED" : "SERVICE_REQUEST_" + status;
        realtime.table(sessionId, event, view);
        realtime.waiters(scope.branchId(), event, view);
        return view;
    }

    /**
     * El comensal confirma si ya lo atendieron: «Sí» cierra el llamado; «Aún no» vuelve a avisar al
     * mismo mesero con prioridad (y si sigue sin atenderse, el vigilante avisa a todos).
     */
    @Transactional
    public RequestView feedback(UUID sessionId, UUID tableToken, UUID guestToken, UUID requestId, boolean attended) {
        tables.requireGuest(sessionId, tableToken, guestToken);
        var branch = jdbc.sql("""
                        SELECT branch_id FROM service_requests
                        WHERE id=:id AND session_id=:session AND type='WAITER_CALL' AND status IN ('PENDING','IN_PROGRESS')
                        """).param("id", requestId).param("session", sessionId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "El llamado ya se cerró"));
        if (attended) {
            jdbc.sql("UPDATE service_requests SET status='RESOLVED',handled_at=NOW(),updated_at=NOW() WHERE id=:id")
                    .param("id", requestId).update();
            var view = viewOf(requestId, null);
            realtime.table(sessionId, "SERVICE_REQUEST_RESOLVED", view);
            realtime.waiters(branch, "SERVICE_REQUEST_RESOLVED", view);
            return view;
        }
        jdbc.sql("""
                UPDATE service_requests SET escalation_level=GREATEST(escalation_level,1),escalated_at=NOW(),
                  attended_at=CASE WHEN status='IN_PROGRESS' THEN NOW() ELSE attended_at END,updated_at=NOW()
                WHERE id=:id
                """).param("id", requestId).update();
        var view = viewOf(requestId, null);
        realtime.table(sessionId, "SERVICE_REQUEST_" + view.status(), view);
        realtime.waiters(branch, "WAITER_CALL_ESCALATED", view);
        return view;
    }

    /** Vista completa de un llamado (con el nombre del mesero si está en camino). */
    RequestView viewOf(UUID requestId, String waiterName) {
        return jdbc.sql("""
                        SELECT sr.id,sr.session_id,COALESCE(t.label,ts.label) table_label,sr.type,sr.status,sr.requested_at,ts.bill_note,
                               sr.reason,sr.escalation_level,sr.attended_at,
                               COALESCE(CAST(:waiter AS TEXT),(SELECT u.full_name FROM app_users u WHERE u.id=sr.handled_by)) waiter_name
                        FROM service_requests sr JOIN table_sessions ts ON ts.id=sr.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE sr.id=:id
                        """).param("id", requestId).param("waiter", waiterName).query(this::mapRequest).single();
    }


    private record Context(UUID organizationId, UUID branchId) {}

    private java.util.Optional<RequestView> findActive(UUID sessionId, String type) {
        return jdbc.sql("""
                        SELECT sr.id,sr.session_id,COALESCE(t.label,ts.label) table_label,sr.type,sr.status,sr.requested_at,ts.bill_note,sr.reason,sr.escalation_level,sr.attended_at,
                               (SELECT u.full_name FROM app_users u WHERE u.id=sr.handled_by) waiter_name
                        FROM service_requests sr
                        JOIN table_sessions ts ON ts.id=sr.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE sr.session_id=:session AND sr.type=:type
                          AND sr.status IN ('PENDING','IN_PROGRESS')
                        """).param("session", sessionId).param("type", type)
                .query(this::mapRequest).optional();
    }

    private RequestView mapRequest(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RequestView(rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class), rs.getString("table_label"),
                rs.getString("type"), rs.getString("status"),
                rs.getTimestamp("requested_at").toInstant(),
                "BILL".equals(rs.getString("type")) ? rs.getString("bill_note") : null,
                "IN_PROGRESS".equals(rs.getString("status")) ? rs.getString("waiter_name") : null,
                rs.getString("reason"), rs.getInt("escalation_level"),
                rs.getTimestamp("attended_at") == null ? null : rs.getTimestamp("attended_at").toInstant());
    }

    /** waiterName: quién va a atender (se muestra al comensal como «Carlos va en camino»). */
    public record RequestView(UUID id, UUID sessionId, String tableLabel, String type, String status,
                              Instant requestedAt, String note, String waiterName,
                              String reason, int escalation, Instant attendedAt) {
        RequestView(UUID id, UUID sessionId, String tableLabel, String type, String status, Instant requestedAt,
                    String note, String waiterName) {
            this(id, sessionId, tableLabel, type, status, requestedAt, note, waiterName, null, 0, null);
        }
    }
}
