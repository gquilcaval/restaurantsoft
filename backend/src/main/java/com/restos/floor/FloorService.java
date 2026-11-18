package com.restos.floor;

import com.restos.audit.AuditService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import com.restos.zones.ZoneService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plano visual del salón: ubicación de mesas y elementos decorativos por zona, y los movimientos
 * que el personal hace arrastrando una mesa ocupada sobre otra (cambiar de mesa o unir cuentas).
 */
@Service
public class FloorService {
    private static final Set<String> SHAPES = Set.of("SQUARE", "ROUND", "RECT");
    private static final Set<String> KINDS = Set.of("BAR", "WALL", "DOOR", "KITCHEN", "WC", "CASHIER", "PLANT", "WINDOW", "STAIRS");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final AuditService audit;
    private final ZoneService zones;

    public FloorService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime, AuditService audit,
                        ZoneService zones) {
        this.zones = zones;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.audit = audit;
    }

    public FloorPlan plan() {
        var branch = currentUser.require().branchId();
        var tables = jdbc.sql("""
                        SELECT id,plan_x,plan_y,plan_shape,plan_rotation,waiter_id FROM dining_tables WHERE branch_id=:branch AND active
                        """).param("branch", branch)
                .query((rs, row) -> new TablePlacement(rs.getObject("id", UUID.class),
                        (Integer) rs.getObject("plan_x"), (Integer) rs.getObject("plan_y"),
                        rs.getString("plan_shape"), rs.getInt("plan_rotation"), rs.getObject("waiter_id", UUID.class))).list();
        var elements = jdbc.sql("""
                        SELECT id,zone_id,kind,label,x,y,width,height,rotation FROM floor_elements
                        WHERE branch_id=:branch ORDER BY created_at
                        """).param("branch", branch)
                .query((rs, row) -> new FloorElement(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("kind"), rs.getString("label"), rs.getInt("x"), rs.getInt("y"),
                        rs.getInt("width"), rs.getInt("height"), rs.getInt("rotation"))).list();
        var settings = jdbc.sql("""
                        SELECT id,plan_background_url,plan_background_opacity,plan_width_meters,max_capacity,active_layout_id
                        FROM dining_zones WHERE branch_id=:branch AND active
                        """).param("branch", branch)
                .query((rs, row) -> new ZoneSettings(rs.getObject("id", UUID.class), rs.getString("plan_background_url"),
                        rs.getInt("plan_background_opacity"), rs.getBigDecimal("plan_width_meters").doubleValue(),
                        (Integer) rs.getObject("max_capacity"), rs.getObject("active_layout_id", UUID.class))).list();
        return new FloorPlan(tables, elements, settings);
    }

    /** Guarda el plano de una zona (o de las mesas sin zona): reemplaza sus elementos y reubica sus mesas. */
    @Transactional
    public FloorPlan save(UUID zoneId, List<TablePlacement> tables, List<FloorElement> elements) {
        var scope = currentUser.require();
        if (zoneId != null) {
            var zone = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch AND active")
                    .param("id", zoneId).param("branch", scope.branchId()).query(Integer.class).single();
            if (zone == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Zona inválida");
        }
        for (var table : tables) {
            if (!SHAPES.contains(table.shape())) throw new ApiException(HttpStatus.BAD_REQUEST, "Forma de mesa inválida");
            var placed = table.x() != null && table.y() != null;
            var updated = jdbc.sql("""
                    UPDATE dining_tables SET plan_x=:x,plan_y=:y,plan_shape=:shape,plan_rotation=:rotation
                    WHERE id=:id AND branch_id=:branch AND active
                    """).param("x", placed ? clamp(table.x()) : null).param("y", placed ? clamp(table.y()) : null)
                    .param("shape", table.shape()).param("rotation", Math.floorMod(table.rotation(), 360))
                    .param("id", table.id()).param("branch", scope.branchId()).update();
            if (updated == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Una de las mesas ya no existe");
        }
        var delete = jdbc.sql("DELETE FROM floor_elements WHERE branch_id=:branch AND zone_id "
                + (zoneId == null ? "IS NULL" : "=:zone")).param("branch", scope.branchId());
        if (zoneId != null) delete = delete.param("zone", zoneId);
        delete.update();
        if (elements.size() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "Demasiados elementos en el plano");
        for (var element : elements) {
            if (!KINDS.contains(element.kind())) throw new ApiException(HttpStatus.BAD_REQUEST, "Elemento inválido");
            jdbc.sql("""
                    INSERT INTO floor_elements(id,organization_id,branch_id,zone_id,kind,label,x,y,width,height,rotation)
                    VALUES (:id,:org,:branch,:zone,:kind,:label,:x,:y,:w,:h,:rotation)
                    """).param("id", UUID.randomUUID()).param("org", scope.organizationId()).param("branch", scope.branchId())
                    .param("zone", zoneId).param("kind", element.kind())
                    .param("label", element.label() == null || element.label().isBlank() ? null : element.label().trim())
                    .param("x", clamp(element.x())).param("y", clamp(element.y()))
                    .param("w", Math.max(10, Math.min(2000, element.width())))
                    .param("h", Math.max(10, Math.min(2000, element.height())))
                    .param("rotation", Math.floorMod(element.rotation(), 360)).update();
        }
        realtime.waiters(scope.branchId(), "FLOOR_UPDATED", new FloorChanged(zoneId));
        return plan();
    }

    /**
     * Crea un plano nuevo (cada plano es una zona del local). Si es el primero, el plano que existía sin
     * zona se convierte en un plano con nombre propio para conservar sus mesas y su diseño.
     */
    @Transactional
    public UUID createPlan(String name, String color, String baseName) {
        var scope = currentUser.require();
        var existing = jdbc.sql("SELECT count(*) FROM dining_zones WHERE branch_id=:branch AND active")
                .param("branch", scope.branchId()).query(Integer.class).single();
        if (existing == 0) {
            var unzoned = jdbc.sql("""
                    SELECT (SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND active AND zone_id IS NULL)
                         + (SELECT count(*) FROM floor_elements WHERE branch_id=:branch AND zone_id IS NULL)
                    """).param("branch", scope.branchId()).query(Integer.class).single();
            if (unzoned > 0) {
                var base = zones.save(null, baseName == null || baseName.isBlank() ? "Salón principal" : baseName.trim(),
                        "#55758A", 0, true, true).id();
                jdbc.sql("UPDATE dining_tables SET zone_id=:zone WHERE branch_id=:branch AND active AND zone_id IS NULL")
                        .param("zone", base).param("branch", scope.branchId()).update();
                jdbc.sql("UPDATE floor_elements SET zone_id=:zone WHERE branch_id=:branch AND zone_id IS NULL")
                        .param("zone", base).param("branch", scope.branchId()).update();
            }
        }
        var order = jdbc.sql("SELECT COALESCE(max(display_order)+1,0) FROM dining_zones WHERE branch_id=:branch")
                .param("branch", scope.branchId()).query(Integer.class).single();
        var created = zones.save(null, name.trim(), color == null ? "#55758A" : color, order, true, true).id();
        realtime.waiters(scope.branchId(), "FLOOR_UPDATED", new FloorChanged(created));
        return created;
    }

    /**
     * Mueve la atención de una mesa a otra. Si la mesa de destino está libre, los comensales simplemente
     * cambian de mesa; si está ocupada, ambas cuentas se unen en la de destino.
     */
    @Transactional
    public MoveResult move(UUID sessionId, UUID destinationTableId) {
        var scope = currentUser.require();
        var source = jdbc.sql("""
                        SELECT ts.id,ts.table_id,t.label,ts.requires_review,ts.discount_kind IS NOT NULL discounted,
                               ts.redeemed_points,ts.customer_id
                        FROM table_sessions ts JOIN dining_tables t ON t.id=ts.table_id
                        WHERE ts.id=:id AND ts.branch_id=:branch AND ts.status='OPEN' FOR UPDATE OF ts
                        """).param("id", sessionId).param("branch", scope.branchId())
                .query((rs, row) -> new LockedSession(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getString("label"), rs.getBoolean("requires_review"), rs.getBoolean("discounted"),
                        rs.getInt("redeemed_points"), rs.getObject("customer_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "La atención ya no está abierta"));
        if (source.tableId().equals(destinationTableId)) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige otra mesa");
        var destinationTable = jdbc.sql("SELECT label,qr_public_id FROM dining_tables WHERE id=:id AND branch_id=:branch AND active FOR UPDATE")
                .param("id", destinationTableId).param("branch", scope.branchId())
                .query((rs, row) -> new DestinationTable(rs.getString("label"), rs.getObject("qr_public_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "La mesa de destino no existe"));
        var destinationLabel = destinationTable.label();
        if (source.requiresReview()) throw new ApiException(HttpStatus.CONFLICT, source.label() + " requiere revisión antes de moverse");
        var destination = jdbc.sql("""
                        SELECT ts.id,ts.table_id,t.label,ts.requires_review,ts.discount_kind IS NOT NULL discounted,
                               ts.redeemed_points,ts.customer_id
                        FROM table_sessions ts JOIN dining_tables t ON t.id=ts.table_id
                        WHERE ts.table_id=:table AND ts.status='OPEN' FOR UPDATE OF ts
                        """).param("table", destinationTableId)
                .query((rs, row) -> new LockedSession(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getString("label"), rs.getBoolean("requires_review"), rs.getBoolean("discounted"),
                        rs.getInt("redeemed_points"), rs.getObject("customer_id", UUID.class))).optional();
        cancelPendingTransfers(source.id(), scope.userId());

        if (destination.isEmpty()) {
            jdbc.sql("UPDATE table_sessions SET table_id=:table,updated_at=NOW() WHERE id=:id")
                    .param("table", destinationTableId).param("id", source.id()).update();
            var result = new MoveResult("MOVED", source.id(), source.label(), destinationLabel);
            audit.record("TABLE_MOVED", "Atención movida de " + source.label() + " a " + destinationLabel, null, source.id());
            relocateGuests(source.id(), source.id(), destinationTable);
            publish(scope.branchId(), result);
            return result;
        }

        var target = destination.get();
        if (target.requiresReview()) throw new ApiException(HttpStatus.CONFLICT, destinationLabel + " requiere revisión antes de unirse");
        var payments = jdbc.sql("SELECT count(*) FROM payments WHERE session_id=:session")
                .param("session", source.id()).query(Integer.class).single();
        if (payments > 0 || source.discounted() || source.redeemedPoints() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, source.label()
                    + " ya tiene pagos, descuentos o puntos aplicados. Une las mesas en sentido contrario o cobra por separado");
        }
        mergeInto(source, target, scope.userId());
        var result = new MoveResult("MERGED", target.id(), source.label(), destinationLabel);
        audit.record("TABLES_MERGED", "Cuenta de " + source.label() + " unida a " + destinationLabel, null, target.id());
        relocateGuests(source.id(), target.id(), destinationTable);
        publish(scope.branchId(), result);
        return result;
    }

    private void mergeInto(LockedSession source, LockedSession target, UUID staffId) {
        // Los productos que aún se están eligiendo se juntan en el borrador de la mesa de destino.
        var targetDraft = jdbc.sql("SELECT id FROM orders WHERE session_id=:session AND status='DRAFT' LIMIT 1")
                .param("session", target.id()).query(UUID.class).optional();
        if (targetDraft.isPresent()) {
            jdbc.sql("""
                    UPDATE order_items SET order_id=:draft,updated_at=NOW()
                    WHERE order_id IN (SELECT id FROM orders WHERE session_id=:source AND status='DRAFT')
                    """).param("draft", targetDraft.get()).param("source", source.id()).update();
            jdbc.sql("DELETE FROM orders WHERE session_id=:source AND status='DRAFT'").param("source", source.id()).update();
        }
        var offset = jdbc.sql("SELECT COALESCE(max(round_number),0) FROM orders WHERE session_id=:session")
                .param("session", target.id()).query(Integer.class).single();
        jdbc.sql("UPDATE orders SET session_id=:target,round_number=round_number+:offset,updated_at=NOW() WHERE session_id=:source")
                .param("target", target.id()).param("offset", offset).param("source", source.id()).update();
        jdbc.sql("UPDATE guests SET session_id=:target,is_host=FALSE,updated_at=NOW() WHERE session_id=:source")
                .param("target", target.id()).param("source", source.id()).update();
        var hasHost = jdbc.sql("SELECT count(*) FROM guests WHERE session_id=:session AND active AND is_host")
                .param("session", target.id()).query(Integer.class).single();
        if (hasHost == 0) {
            jdbc.sql("""
                    UPDATE guests SET is_host=TRUE,updated_at=NOW() WHERE id=(
                      SELECT id FROM guests WHERE session_id=:session AND active ORDER BY created_at LIMIT 1)
                    """).param("session", target.id()).update();
        }
        // Las llamadas pendientes pasan a la mesa unida, salvo que ya tenga una igual activa.
        jdbc.sql("""
                UPDATE service_requests s SET session_id=:target,updated_at=NOW()
                WHERE s.session_id=:source AND s.status IN ('PENDING','IN_PROGRESS')
                  AND NOT EXISTS (SELECT 1 FROM service_requests d WHERE d.session_id=:target AND d.type=s.type
                                  AND d.status IN ('PENDING','IN_PROGRESS'))
                """).param("target", target.id()).param("source", source.id()).update();
        jdbc.sql("""
                UPDATE service_requests SET status='RESOLVED',handled_by=:user,handled_at=NOW(),updated_at=NOW()
                WHERE session_id=:source AND status IN ('PENDING','IN_PROGRESS')
                """).param("user", staffId).param("source", source.id()).update();
        jdbc.sql("UPDATE reservations SET session_id=:target WHERE session_id=:source")
                .param("target", target.id()).param("source", source.id()).update();
        if (target.customerId() == null && source.customerId() != null) {
            jdbc.sql("UPDATE table_sessions SET customer_id=:customer WHERE id=:id")
                    .param("customer", source.customerId()).param("id", target.id()).update();
        }
        jdbc.sql("""
                UPDATE table_sessions SET status='CANCELLED',closed_at=NOW(),closed_by=:user,
                  close_reason=:reason,updated_at=NOW() WHERE id=:id
                """).param("user", staffId).param("reason", "Cuenta unida a " + target.label())
                .param("id", source.id()).update();
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id=:id").param("id", target.id()).update();
    }

    /** Los comensales de la mesa original siguen conectados: su teléfono pasa solo a la nueva mesa. */
    private void relocateGuests(UUID sourceSession, UUID destinationSession, DestinationTable table) {
        var token = jdbc.sql("SELECT guest_token FROM table_sessions WHERE id=:id")
                .param("id", destinationSession).query(UUID.class).single();
        realtime.table(sourceSession, "TABLE_SESSION_RELOCATED",
                new SessionRelocated(destinationSession, token, table.qrPublicId(), table.label()));
    }

    private void cancelPendingTransfers(UUID sessionId, UUID staffId) {
        jdbc.sql("""
                UPDATE table_transfer_requests SET status='CANCELLED',reason='La mesa fue movida por el personal',
                  handled_by=:user,handled_at=NOW()
                WHERE source_session_id=:session AND status='PENDING'
                """).param("user", staffId).param("session", sessionId).update();
    }

    private void publish(UUID branchId, MoveResult result) {
        realtime.waiters(branchId, "TABLE_MOVED", result);
        realtime.kitchen(branchId, "TABLE_MOVED", result);
    }

    private int clamp(Integer value) { return Math.max(0, Math.min(2000, Objects.requireNonNullElse(value, 0))); }

    private record LockedSession(UUID id, UUID tableId, String label, boolean requiresReview, boolean discounted,
                                 int redeemedPoints, UUID customerId) {}
    public record TablePlacement(UUID id, Integer x, Integer y, String shape, int rotation, UUID waiterId) {}
    public record FloorElement(UUID id, UUID zoneId, String kind, String label, int x, int y, int width, int height,
                               int rotation) {}
    public record FloorPlan(List<TablePlacement> tables, List<FloorElement> elements, List<ZoneSettings> settings) {}
    /** Ajustes del plano de una zona: foto de fondo, ancho real del salón, aforo y configuración activa. */
    public record ZoneSettings(UUID zoneId, String backgroundUrl, int backgroundOpacity, double widthMeters,
                               Integer maxCapacity, UUID activeLayoutId) {}
    public record FloorChanged(UUID zoneId) {}
    public record MoveResult(String kind, UUID sessionId, String sourceLabel, String destinationLabel) {}
    public record SessionRelocated(UUID sessionId, UUID tableToken, UUID qrPublicId, String tableLabel) {}
    private record DestinationTable(String label, UUID qrPublicId) {}
}
