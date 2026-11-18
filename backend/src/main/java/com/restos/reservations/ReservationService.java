package com.restos.reservations;

import com.restos.audit.AuditService;
import com.restos.customers.CustomerService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import com.restos.tables.TableService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agenda de reservas por sucursal. Una mesa no puede tener dos reservas activas que se crucen en el tiempo;
 * al llegar el cliente se abre la atención en su mesa y queda vinculada a su ficha.
 */
@Service
public class ReservationService {
    private static final List<String> ACTIVE = List.of("PENDING", "CONFIRMED");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final CustomerService customers;
    private final TableService tables;
    private final RealtimePublisher realtime;
    private final AuditService audit;
    private final ZoneId zone;

    public ReservationService(JdbcClient jdbc, CurrentUser currentUser, CustomerService customers, TableService tables,
                              RealtimePublisher realtime, AuditService audit,
                              @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.customers = customers;
        this.tables = tables;
        this.realtime = realtime;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    public List<ReservationView> day(LocalDate date) {
        var branch = currentUser.require().branchId();
        return list("r.branch_id=:branch AND r.reserved_at>=:start AND r.reserved_at<:end ORDER BY r.reserved_at", branch,
                date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Reservas activas de las próximas horas, para avisar en el plano del salón. */
    public List<ReservationView> upcoming(int hours, int pastMinutes) {
        var branch = currentUser.require().branchId();
        var now = Instant.now();
        return list("r.branch_id=:branch AND r.status IN ('PENDING','CONFIRMED') AND r.reserved_at>=:start AND r.reserved_at<:end"
                + " ORDER BY r.reserved_at", branch, now.minusSeconds(pastMinutes * 60L), now.plusSeconds(hours * 3600L));
    }

    @Transactional
    public ReservationView save(UUID id, ReservationData data) {
        var scope = currentUser.require();
        var start = data.reservedAt().atZone(zone).toInstant();
        if (id == null && start.isBefore(Instant.now().minusSeconds(15 * 60))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha de la reserva ya pasó");
        }
        if (data.tableId() != null) {
            var table = jdbc.sql("SELECT count(*) FROM dining_tables WHERE id=:id AND branch_id=:branch AND active")
                    .param("id", data.tableId()).param("branch", scope.branchId()).query(Integer.class).single();
            if (table == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Mesa inválida");
            ensureNoOverlap(id, data.tableId(), start, data.durationMinutes());
        }
        if (data.zoneId() != null) {
            var zone = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch AND active")
                    .param("id", data.zoneId()).param("branch", scope.branchId()).query(Integer.class).single();
            if (zone == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Zona inválida");
        }
        var customerId = customers.findOrCreate(data.customerName().trim(), data.phone());
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO reservations(id,organization_id,branch_id,customer_id,customer_name,phone,party_size,
                      reserved_at,duration_minutes,table_id,status,notes,created_by,zone_id)
                    VALUES (:id,:org,:branch,:customer,:name,:phone,:party,:at,:duration,:table,:status,:notes,:user,:zone)
                    """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                    .param("customer", customerId).param("name", data.customerName().trim()).param("phone", data.phone().trim())
                    .param("party", data.partySize()).param("at", Timestamp.from(start)).param("duration", data.durationMinutes())
                    .param("table", data.tableId()).param("status", data.confirmed() ? "CONFIRMED" : "PENDING")
                    .param("notes", normalize(data.notes())).param("user", scope.userId()).param("zone", data.zoneId()).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE reservations SET customer_id=:customer,customer_name=:name,phone=:phone,party_size=:party,
                      reserved_at=:at,duration_minutes=:duration,table_id=:table,notes=:notes,zone_id=:zone,
                      status=CASE WHEN status IN ('PENDING','CONFIRMED') THEN :status ELSE status END,updated_at=NOW()
                    WHERE id=:id AND branch_id=:branch AND status IN ('PENDING','CONFIRMED')
                    """).param("customer", customerId).param("name", data.customerName().trim()).param("phone", data.phone().trim())
                    .param("party", data.partySize()).param("at", Timestamp.from(start)).param("duration", data.durationMinutes())
                    .param("table", data.tableId()).param("notes", normalize(data.notes()))
                    .param("status", data.confirmed() ? "CONFIRMED" : "PENDING").param("zone", data.zoneId())
                    .param("id", id).param("branch", scope.branchId()).update();
            if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "La reserva ya no se puede modificar");
        }
        return publish(id);
    }

    /** Cambios de estado: confirmar, cancelar o marcar que el cliente no llegó. */
    @Transactional
    public ReservationView changeStatus(UUID id, String status) {
        if (!List.of("CONFIRMED", "CANCELLED", "NO_SHOW").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Estado inválido");
        }
        var current = lock(id);
        if (!ACTIVE.contains(current.status())) throw new ApiException(HttpStatus.CONFLICT, "La reserva ya fue atendida o cerrada");
        jdbc.sql("UPDATE reservations SET status=:status,updated_at=NOW() WHERE id=:id")
                .param("status", status).param("id", id).update();
        if (status.equals("CANCELLED")) {
            audit.record("RESERVATION_CANCELLED", "Reserva cancelada: " + current.customerName() + " (" + current.partySize() + ")",
                    null, id);
        }
        return publish(id);
    }

    /** El cliente llegó: se abre la atención en su mesa y se vincula a su ficha para sumar puntos. */
    @Transactional
    public ReservationView seat(UUID id, UUID tableId) {
        var current = lock(id);
        if (!ACTIVE.contains(current.status())) throw new ApiException(HttpStatus.CONFLICT, "La reserva ya fue atendida o cerrada");
        var table = tableId != null ? tableId : current.tableId();
        if (table == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige la mesa donde se sentará el cliente");
        var opened = tables.open(table);
        jdbc.sql("UPDATE table_sessions SET customer_id=:customer WHERE id=:session")
                .param("customer", current.customerId()).param("session", opened.sessionId()).update();
        jdbc.sql("""
                UPDATE reservations SET status='SEATED',table_id=:table,session_id=:session,updated_at=NOW() WHERE id=:id
                """).param("table", table).param("session", opened.sessionId()).param("id", id).update();
        return publish(id);
    }

    private void ensureNoOverlap(UUID id, UUID tableId, Instant start, int duration) {
        var conflict = jdbc.sql("""
                        SELECT customer_name FROM reservations
                        WHERE table_id=:table AND status IN ('PENDING','CONFIRMED') AND id<>:id
                          AND reserved_at < :end AND reserved_at + duration_minutes * INTERVAL '1 minute' > :start
                        LIMIT 1
                        """).param("table", tableId).param("id", id == null ? new UUID(0, 0) : id)
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(start.plusSeconds(duration * 60L)))
                .query(String.class).optional();
        if (conflict.isPresent()) throw new ApiException(HttpStatus.CONFLICT,
                "La mesa ya está reservada en ese horario para " + conflict.get());
    }

    private Locked lock(UUID id) {
        return jdbc.sql("""
                        SELECT status,customer_id,customer_name,party_size,table_id FROM reservations
                        WHERE id=:id AND branch_id=:branch FOR UPDATE
                        """).param("id", id).param("branch", currentUser.require().branchId())
                .query((rs, row) -> new Locked(rs.getString("status"), rs.getObject("customer_id", UUID.class),
                        rs.getString("customer_name"), rs.getInt("party_size"), rs.getObject("table_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Reserva no encontrada"));
    }

    private ReservationView publish(UUID id) {
        var branch = currentUser.require().branchId();
        var view = list("r.id=:id", branch, null, null, id).get(0);
        realtime.waiters(branch, "RESERVATION_UPDATED", view);
        return view;
    }

    private List<ReservationView> list(String where, UUID branch, Instant start, Instant end) {
        return list(where, branch, start, end, null);
    }

    private List<ReservationView> list(String where, UUID branch, Instant start, Instant end, UUID id) {
        var sql = jdbc.sql("""
                SELECT r.*,t.label table_label,t.capacity,c.visits,c.points,z.name zone_name FROM reservations r
                LEFT JOIN dining_tables t ON t.id=r.table_id LEFT JOIN customers c ON c.id=r.customer_id
                LEFT JOIN dining_zones z ON z.id=r.zone_id
                WHERE r.branch_id=:branch AND\s""" + where).param("branch", branch);
        if (start != null) sql = sql.param("start", Timestamp.from(start)).param("end", Timestamp.from(end));
        if (id != null) sql = sql.param("id", id);
        return sql.query((rs, row) -> new ReservationView(rs.getObject("id", UUID.class), rs.getString("customer_name"),
                rs.getString("phone"), rs.getInt("party_size"),
                LocalDateTime.ofInstant(rs.getTimestamp("reserved_at").toInstant(), zone), rs.getInt("duration_minutes"),
                rs.getObject("table_id", UUID.class), rs.getString("table_label"),
                rs.getObject("capacity") == null ? null : rs.getInt("capacity"), rs.getString("status"), rs.getString("notes"),
                rs.getObject("customer_id", UUID.class), rs.getObject("visits") == null ? 0 : rs.getInt("visits"),
                rs.getObject("session_id", UUID.class), rs.getObject("zone_id", UUID.class), rs.getString("zone_name"))).list();
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Locked(String status, UUID customerId, String customerName, int partySize, UUID tableId) {}
    public record ReservationData(String customerName, String phone, int partySize, LocalDateTime reservedAt,
                                  int durationMinutes, UUID tableId, String notes, boolean confirmed, UUID zoneId) {}
    public record ReservationView(UUID id, String customerName, String phone, int partySize, LocalDateTime reservedAt,
                                  int durationMinutes, UUID tableId, String tableLabel, Integer tableCapacity, String status,
                                  String notes, UUID customerId, int customerVisits, UUID sessionId, UUID zoneId,
                                  String zoneName) {}
}
