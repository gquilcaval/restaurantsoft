package com.restos.tables;

import com.restos.orders.OrderStatusRefresher;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Traslada un comensal con autorización del salón, preservando pedidos, estados y auditoría. */
@Service
public class TableTransferService {
    private final JdbcClient jdbc;
    private final TableService tables;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final OrderStatusRefresher orderStatus;

    private final com.restos.waiters.WaiterAssignmentService assignments;

    public TableTransferService(JdbcClient jdbc, TableService tables, CurrentUser currentUser,
                                RealtimePublisher realtime, OrderStatusRefresher orderStatus, com.restos.waiters.WaiterAssignmentService assignments) {
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.tables = tables;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.orderStatus = orderStatus;
    }

    /** Expone únicamente datos operativos de mesas libres de la misma sucursal. */
    public List<AvailableTable> availableTables(UUID sessionId, UUID tableToken, UUID guestToken) {
        tables.requireGuest(sessionId, tableToken, guestToken);
        return jdbc.sql("""
                SELECT t.qr_public_id,t.label,t.capacity
                FROM dining_tables t
                JOIN table_sessions source ON source.id=:session
                WHERE t.branch_id=source.branch_id AND t.active AND t.id<>source.table_id
                  AND NOT EXISTS (
                    SELECT 1 FROM table_sessions occupied
                    WHERE occupied.table_id=t.id AND occupied.status='OPEN'
                  )
                ORDER BY t.label
                """).param("session", sessionId)
                .query((rs, row) -> new AvailableTable(rs.getObject("qr_public_id", UUID.class),
                        rs.getString("label"), rs.getInt("capacity"))).list();
    }

    @Transactional
    public TransferView request(UUID sourceSessionId, UUID tableToken, UUID guestToken,
                                UUID destinationQrPublicId) {
        var guest = tables.requireGuest(sourceSessionId, tableToken, guestToken);
        var context = jdbc.sql("""
                SELECT ts.organization_id,ts.branch_id,ts.table_id,t.label,g.display_name
                FROM table_sessions ts JOIN dining_tables t ON t.id=ts.table_id
                JOIN guests g ON g.id=:guest
                WHERE ts.id=:session AND g.session_id=ts.id AND ts.status='OPEN'
                """).param("guest", guest.id()).param("session", sourceSessionId)
                .query((rs, row) -> new SourceContext(rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getString("label"), rs.getString("display_name"))).single();
        var destination = jdbc.sql("""
                SELECT t.id,t.label FROM dining_tables t
                WHERE t.qr_public_id=:qr AND t.branch_id=:branch AND t.active
                  AND NOT EXISTS (
                    SELECT 1 FROM table_sessions occupied
                    WHERE occupied.table_id=t.id AND occupied.status='OPEN'
                  )
                """).param("qr", destinationQrPublicId).param("branch", context.branchId())
                .query((rs, row) -> new Destination(rs.getObject("id", UUID.class), rs.getString("label")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "La mesa de destino ya no está libre"));
        if (destination.id().equals(context.tableId())) throw new ApiException(HttpStatus.CONFLICT,
                "Ya te encuentras en esta mesa");
        var pending = jdbc.sql("SELECT count(*) FROM table_transfer_requests WHERE guest_id=:guest AND status='PENDING'")
                .param("guest", guest.id()).query(Integer.class).single();
        if (pending > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya tienes un cambio de mesa pendiente");

        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO table_transfer_requests(id,organization_id,branch_id,guest_id,source_session_id,
                  destination_table_id,source_table_label,destination_table_label,status)
                VALUES (:id,:org,:branch,:guest,:source,:destination,:sourceLabel,:destinationLabel,'PENDING')
                """).param("id", id).param("org", context.organizationId()).param("branch", context.branchId())
                .param("guest", guest.id()).param("source", sourceSessionId)
                .param("destination", destination.id()).param("sourceLabel", context.tableLabel())
                .param("destinationLabel", destination.label()).update();
        var view = new TransferView(id, guest.id(), context.displayName(), sourceSessionId,
                context.tableLabel(), destination.label(), "PENDING", null, Instant.now());
        realtime.waiters(context.branchId(), "TABLE_TRANSFER_REQUESTED", view);
        realtime.table(sourceSessionId, "TABLE_TRANSFER_REQUESTED", view);
        return view;
    }

    public PublicTransferStatus publicStatus(UUID requestId, UUID guestToken) {
        return jdbc.sql("""
                SELECT tr.id,tr.status,tr.reason,tr.source_table_label,tr.destination_table_label,
                       tr.destination_session_id,dt.qr_public_id,ts.guest_token table_token,
                       g.id guest_id,g.guest_token,g.display_name,g.is_host
                FROM table_transfer_requests tr JOIN guests g ON g.id=tr.guest_id
                JOIN dining_tables dt ON dt.id=tr.destination_table_id
                LEFT JOIN table_sessions ts ON ts.id=tr.destination_session_id
                WHERE tr.id=:id AND g.guest_token=:guestToken
                """).param("id", requestId).param("guestToken", guestToken)
                .query((rs, row) -> new PublicTransferStatus(rs.getObject("id", UUID.class),
                        rs.getString("status"), rs.getString("source_table_label"),
                        rs.getString("destination_table_label"), rs.getString("reason"),
                        rs.getObject("destination_session_id", UUID.class), rs.getObject("guest_id", UUID.class),
                        rs.getObject("table_token", UUID.class), rs.getObject("guest_token", UUID.class),
                        rs.getString("display_name"), rs.getBoolean("is_host"),
                        rs.getObject("qr_public_id", UUID.class))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Solicitud no encontrada"));
    }

    /** Permite al comensal retirar su propia solicitud mientras el mesero no la haya atendido. */
    @Transactional
    public TransferView cancel(UUID requestId, UUID guestToken) {
        var request = jdbc.sql("""
                SELECT tr.id,tr.branch_id,tr.guest_id,tr.source_session_id,tr.source_table_label,
                       tr.destination_table_label,tr.requested_at,g.display_name
                FROM table_transfer_requests tr JOIN guests g ON g.id=tr.guest_id
                WHERE tr.id=:id AND g.guest_token=:guestToken AND tr.status='PENDING' FOR UPDATE OF tr
                """).param("id", requestId).param("guestToken", guestToken)
                .query((rs, row) -> new CancelContext(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getObject("guest_id", UUID.class),
                        rs.getObject("source_session_id", UUID.class), rs.getString("source_table_label"),
                        rs.getString("destination_table_label"), rs.getTimestamp("requested_at").toInstant(),
                        rs.getString("display_name"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "La solicitud ya fue atendida o no existe"));
        jdbc.sql("""
                UPDATE table_transfer_requests SET status='CANCELLED',reason='Cancelada por el comensal',
                  handled_at=NOW(),updated_at=NOW() WHERE id=:id
                """).param("id", request.id()).update();
        var view = new TransferView(request.id(), request.guestId(), request.displayName(),
                request.sourceSessionId(), request.sourceLabel(), request.destinationLabel(),
                "CANCELLED", "Cancelada por el comensal", request.requestedAt());
        realtime.table(request.sourceSessionId(), "TABLE_TRANSFER_CANCELLED", view);
        realtime.waiters(request.branchId(), "TABLE_TRANSFER_CANCELLED", view);
        return view;
    }

    public List<TransferView> pending() {
        var branchId = currentUser.require().branchId();
        return jdbc.sql("""
                SELECT tr.id,tr.guest_id,g.display_name,tr.source_session_id,tr.source_table_label,
                       tr.destination_table_label,tr.status,tr.reason,tr.requested_at
                FROM table_transfer_requests tr JOIN guests g ON g.id=tr.guest_id
                WHERE tr.branch_id=:branch AND tr.status='PENDING' ORDER BY tr.requested_at
                """).param("branch", branchId).query((rs, row) -> mapView(rs)).list();
    }

    @Transactional
    public TransferView approve(UUID requestId) {
        var scope = currentUser.require();
        var request = lockRequest(requestId, scope.branchId());
        jdbc.sql("SELECT id FROM table_sessions WHERE id=:id AND status='OPEN' FOR UPDATE")
                .param("id", request.sourceSessionId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "La mesa de origen ya fue cerrada"));
        var guest = jdbc.sql("""
                SELECT id,display_name,is_host FROM guests
                WHERE id=:guest AND session_id=:session AND active FOR UPDATE
                """).param("guest", request.guestId()).param("session", request.sourceSessionId())
                .query((rs, row) -> new MovingGuest(rs.getObject("id", UUID.class),
                        rs.getString("display_name"), rs.getBoolean("is_host"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "El comensal ya no está en la mesa de origen"));

        jdbc.sql("SELECT id FROM dining_tables WHERE id=:id AND branch_id=:branch AND active FOR UPDATE")
                .param("id", request.destinationTableId()).param("branch", scope.branchId())
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "La mesa de destino ya no está disponible"));
        var destinationSession = findOrCreateDestinationSession(request, scope.userId());
        moveOrders(request.sourceSessionId(), destinationSession.id(), guest.id(),
                request.organizationId(), request.branchId());
        moveGuest(request.sourceSessionId(), destinationSession.id(), guest);
        closeEmptySourceAfterTransfer(request.sourceSessionId(), scope.userId());

        jdbc.sql("""
                UPDATE table_transfer_requests SET status='APPROVED',destination_session_id=:destination,
                  handled_by=:user,handled_at=NOW(),updated_at=NOW() WHERE id=:id
                """).param("destination", destinationSession.id()).param("user", scope.userId())
                .param("id", request.id()).update();
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id IN (:source,:destination)")
                .param("source", request.sourceSessionId()).param("destination", destinationSession.id()).update();
        var view = new TransferView(request.id(), guest.id(), guest.name(), request.sourceSessionId(),
                request.sourceLabel(), request.destinationLabel(), "APPROVED", null, request.requestedAt());
        realtime.table(request.sourceSessionId(), "TABLE_TRANSFER_APPROVED", view);
        realtime.table(destinationSession.id(), "TABLE_GUEST_JOINED", view);
        realtime.waiters(request.branchId(), "TABLE_TRANSFER_APPROVED", view);
        realtime.kitchen(request.branchId(), "TABLE_TRANSFER_APPROVED", view);
        return view;
    }

    @Transactional
    public TransferView reject(UUID requestId, String reason) {
        var scope = currentUser.require();
        var request = lockRequest(requestId, scope.branchId());
        var normalized = reason == null || reason.isBlank() ? "Cambio no autorizado por el personal" : reason.trim();
        jdbc.sql("""
                UPDATE table_transfer_requests SET status='REJECTED',reason=:reason,handled_by=:user,
                  handled_at=NOW(),updated_at=NOW() WHERE id=:id
                """).param("reason", normalized).param("user", scope.userId()).param("id", request.id()).update();
        var name = jdbc.sql("SELECT display_name FROM guests WHERE id=:id")
                .param("id", request.guestId()).query(String.class).single();
        var view = new TransferView(request.id(), request.guestId(), name, request.sourceSessionId(),
                request.sourceLabel(), request.destinationLabel(), "REJECTED", normalized, request.requestedAt());
        realtime.table(request.sourceSessionId(), "TABLE_TRANSFER_REJECTED", view);
        realtime.waiters(request.branchId(), "TABLE_TRANSFER_REJECTED", view);
        return view;
    }

    private TransferContext lockRequest(UUID requestId, UUID branchId) {
        return jdbc.sql("""
                SELECT id,organization_id,branch_id,guest_id,source_session_id,destination_table_id,
                       source_table_label,destination_table_label,requested_at
                FROM table_transfer_requests WHERE id=:id AND branch_id=:branch AND status='PENDING' FOR UPDATE
                """).param("id", requestId).param("branch", branchId)
                .query((rs, row) -> new TransferContext(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("guest_id", UUID.class), rs.getObject("source_session_id", UUID.class),
                        rs.getObject("destination_table_id", UUID.class), rs.getString("source_table_label"),
                        rs.getString("destination_table_label"), rs.getTimestamp("requested_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "La solicitud ya fue atendida o no existe"));
    }

    private DestinationSession findOrCreateDestinationSession(TransferContext request, UUID staffId) {
        var existing = jdbc.sql("""
                SELECT id,guest_token,requires_review FROM table_sessions
                WHERE table_id=:table AND status='OPEN' FOR UPDATE
                """).param("table", request.destinationTableId())
                .query((rs, row) -> new DestinationSession(rs.getObject("id", UUID.class),
                        rs.getObject("guest_token", UUID.class), rs.getBoolean("requires_review"))).optional();
        if (existing.isPresent()) throw new ApiException(HttpStatus.CONFLICT,
                "La mesa de destino ya no está libre; solicita otra mesa");
        var id = UUID.randomUUID();
        var token = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO table_sessions(id,organization_id,branch_id,table_id,status,guest_token,opened_by,opened_source)
                VALUES (:id,:org,:branch,:table,'OPEN',:token,:user,'TRANSFER')
                """).param("id", id).param("org", request.organizationId()).param("branch", request.branchId())
                .param("table", request.destinationTableId()).param("token", token).param("user", staffId).update();
        assignments.assignOnOpen(id, staffId);
        return new DestinationSession(id, token, false);
    }

    private void moveOrders(UUID sourceSession, UUID destinationSession, UUID guestId,
                            UUID organizationId, UUID branchId) {
        var sourceOrders = jdbc.sql("""
                SELECT DISTINCT o.id,o.status,o.submitted_at FROM orders o
                JOIN order_items oi ON oi.order_id=o.id
                WHERE o.session_id=:session AND oi.guest_id=:guest ORDER BY o.submitted_at NULLS FIRST
                """).param("session", sourceSession).param("guest", guestId)
                .query((rs, row) -> new MovingOrder(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant())).list();
        for (var source : sourceOrders) {
            UUID destinationOrder;
            if (source.status().equals("DRAFT")) {
                destinationOrder = jdbc.sql("SELECT id FROM orders WHERE session_id=:session AND status='DRAFT'")
                        .param("session", destinationSession).query(UUID.class).optional()
                        .orElseGet(() -> createOrder(destinationSession, organizationId, branchId, "DRAFT", null));
            } else {
                destinationOrder = createOrder(destinationSession, organizationId, branchId,
                        source.status(), source.submittedAt());
            }
            jdbc.sql("UPDATE order_items SET order_id=:destination,updated_at=NOW() WHERE order_id=:source AND guest_id=:guest")
                    .param("destination", destinationOrder).param("source", source.id()).param("guest", guestId).update();
            orderStatus.refresh(source.id());
            if (!source.status().equals("DRAFT")) orderStatus.refresh(destinationOrder);
        }
    }

    private UUID createOrder(UUID sessionId, UUID organizationId, UUID branchId, String status, Instant submittedAt) {
        var round = jdbc.sql("SELECT COALESCE(max(round_number)+1,1) FROM orders WHERE session_id=:session")
                .param("session", sessionId).query(Integer.class).single();
        var id = UUID.randomUUID();
        // PostgreSQL no infiere java.time.Instant en parámetros genéricos; Timestamp preserva el instante.
        if (submittedAt == null) {
            jdbc.sql("""
                    INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status,submitted_at)
                    VALUES (:id,:org,:branch,:session,:round,:status,NULL)
                    """).param("id", id).param("org", organizationId).param("branch", branchId)
                    .param("session", sessionId).param("round", round).param("status", status).update();
        } else {
            jdbc.sql("""
                    INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status,submitted_at)
                    VALUES (:id,:org,:branch,:session,:round,:status,:submittedAt)
                    """).param("id", id).param("org", organizationId).param("branch", branchId)
                    .param("session", sessionId).param("round", round).param("status", status)
                    .param("submittedAt", Timestamp.from(submittedAt)).update();
        }
        return id;
    }

    private void moveGuest(UUID sourceSession, UUID destinationSession, MovingGuest guest) {
        var destinationGuests = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                .param("session", destinationSession).query(Integer.class).single();
        jdbc.sql("UPDATE guests SET session_id=:destination,is_host=:host,updated_at=NOW() WHERE id=:guest")
                .param("destination", destinationSession).param("host", destinationGuests == 0)
                .param("guest", guest.id()).update();
        if (guest.host()) {
            var nextHost = jdbc.sql("SELECT id FROM guests WHERE session_id=:session AND active ORDER BY created_at LIMIT 1")
                    .param("session", sourceSession).query(UUID.class).optional();
            nextHost.ifPresent(id -> jdbc.sql("UPDATE guests SET is_host=TRUE,updated_at=NOW() WHERE id=:id")
                    .param("id", id).update());
        }
    }

    /** Libera inmediatamente la mesa anterior cuando ya no quedan personas ni consumos en ella. */
    private void closeEmptySourceAfterTransfer(UUID sourceSession, UUID staffId) {
        var activeGuests = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active")
                .param("session", sourceSession).query(Integer.class).single();
        var remainingItems = jdbc.sql("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.session_id=:session AND o.status<>'CANCELLED'
                """).param("session", sourceSession).query(Integer.class).single();
        if (activeGuests == 0 && remainingItems == 0) {
            jdbc.sql("""
                    UPDATE table_sessions SET status='CANCELLED',closed_at=NOW(),closed_by=:user,
                      close_reason='Mesa liberada por cambio de mesa',updated_at=NOW() WHERE id=:session
                    """).param("user", staffId).param("session", sourceSession).update();
            jdbc.sql("""
                    UPDATE service_requests SET status='RESOLVED',handled_by=:user,handled_at=NOW(),updated_at=NOW()
                    WHERE session_id=:session AND status IN ('PENDING','IN_PROGRESS')
                    """).param("user", staffId).param("session", sourceSession).update();
        }
    }

    private TransferView mapView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TransferView(rs.getObject("id", UUID.class), rs.getObject("guest_id", UUID.class),
                rs.getString("display_name"), rs.getObject("source_session_id", UUID.class),
                rs.getString("source_table_label"), rs.getString("destination_table_label"),
                rs.getString("status"), rs.getString("reason"), rs.getTimestamp("requested_at").toInstant());
    }

    private record SourceContext(UUID organizationId, UUID branchId, UUID tableId, String tableLabel,
                                 String displayName) {}
    public record AvailableTable(UUID qrPublicId, String label, int capacity) {}
    private record Destination(UUID id, String label) {}
    private record CancelContext(UUID id, UUID branchId, UUID guestId, UUID sourceSessionId,
                                 String sourceLabel, String destinationLabel, Instant requestedAt,
                                 String displayName) {}
    private record MovingGuest(UUID id, String name, boolean host) {}
    private record MovingOrder(UUID id, String status, Instant submittedAt) {}
    private record DestinationSession(UUID id, UUID tableToken, boolean requiresReview) {}
    private record TransferContext(UUID id, UUID organizationId, UUID branchId, UUID guestId,
                                   UUID sourceSessionId, UUID destinationTableId, String sourceLabel,
                                   String destinationLabel, Instant requestedAt) {}
    public record TransferView(UUID id, UUID guestId, String displayName, UUID sourceSessionId,
                               String sourceTableLabel, String destinationTableLabel, String status,
                               String reason, Instant requestedAt) {}
    public record PublicTransferStatus(UUID id, String status, String sourceTableLabel,
                                       String destinationTableLabel, String reason, UUID sessionId,
                                       UUID guestId, UUID tableToken, UUID guestToken, String displayName,
                                       boolean host, UUID qrPublicId) {}
}
