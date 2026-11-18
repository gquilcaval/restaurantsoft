package com.restos.booking;

import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservas online: el cliente abre el enlace de la sucursal, elige fecha, hora y su mesa en el plano.
 * La reserva entra «por confirmar» y el local la confirma desde Recepción.
 */
@Service
public class BookingService {
    private static final int DURATION = 90;
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final ZoneId zone;
    private final ConcurrentHashMap<String, List<Instant>> attempts = new ConcurrentHashMap<>();

    public BookingService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime,
                          @Value("${restos.business-zone:America/Lima}") String timezone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.zone = ZoneId.of(timezone);
    }

    public BookingPage page(UUID branchId) {
        var branch = jdbc.sql("""
                        SELECT b.id,b.name branch_name,b.address,o.name org_name,o.logo_url,o.primary_color,b.online_booking
                        FROM branches b JOIN organizations o ON o.id=b.organization_id WHERE b.id=:id AND b.active AND o.active
                        """).param("id", branchId)
                .query((rs, row) -> new BranchInfo(rs.getString("branch_name"), rs.getString("address"), rs.getString("org_name"),
                        rs.getString("logo_url"), rs.getString("primary_color"), rs.getBoolean("online_booking"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Local no encontrado"));
        if (!branch.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "Este local no recibe reservas online por ahora");
        var zones = jdbc.sql("SELECT id,name,color,plan_width_meters FROM dining_zones WHERE branch_id=:id AND active AND open ORDER BY display_order")
                .param("id", branchId).query((rs, row) -> new ZoneInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("color"))).list();
        var tables = jdbc.sql("""
                        SELECT t.id,t.label,t.capacity,t.zone_id,t.plan_x,t.plan_y,t.plan_shape,t.plan_rotation FROM dining_tables t
                        LEFT JOIN dining_zones z ON z.id=t.zone_id
                        WHERE t.branch_id=:id AND t.active AND (t.zone_id IS NULL OR (z.active AND z.open)) ORDER BY t.label
                        """).param("id", branchId)
                .query((rs, row) -> new TableInfo(rs.getObject("id", UUID.class), rs.getString("label"), rs.getInt("capacity"),
                        rs.getObject("zone_id", UUID.class), (Integer) rs.getObject("plan_x"), (Integer) rs.getObject("plan_y"),
                        rs.getString("plan_shape"), rs.getInt("plan_rotation"))).list();
        var elements = jdbc.sql("SELECT id,zone_id,kind,label,x,y,width,height,rotation FROM floor_elements WHERE branch_id=:id")
                .param("id", branchId).query((rs, row) -> new ElementInfo(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("kind"), rs.getString("label"), rs.getInt("x"), rs.getInt("y"), rs.getInt("width"),
                        rs.getInt("height"), rs.getInt("rotation"))).list();
        return new BookingPage(branch.branchName(), branch.address(), branch.organizationName(), branch.logoUrl(),
                branch.primaryColor(), zones, tables, elements);
    }

    /** Mesas ocupadas por otra reserva activa en ese horario (90 minutos). */
    public List<UUID> busyTables(UUID branchId, LocalDate date, LocalTime time) {
        var start = date.atTime(time).atZone(zone).toInstant();
        return jdbc.sql("""
                        SELECT DISTINCT table_id FROM reservations WHERE branch_id=:branch AND table_id IS NOT NULL
                          AND status IN ('PENDING','CONFIRMED') AND reserved_at < :end
                          AND reserved_at + duration_minutes * INTERVAL '1 minute' > :start
                        """).param("branch", branchId).param("start", Timestamp.from(start))
                .param("end", Timestamp.from(start.plusSeconds(DURATION * 60L))).query(UUID.class).list();
    }

    @Transactional
    public Confirmation book(UUID branchId, BookingRequest request, String clientKey) {
        throttle(clientKey);
        page(branchId);
        var start = request.date().atTime(request.time()).atZone(zone).toInstant();
        if (start.isBefore(Instant.now().plusSeconds(30 * 60))) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige un horario con al menos 30 minutos de anticipación");
        if (start.isAfter(Instant.now().plus(Duration.ofDays(60)))) throw new ApiException(HttpStatus.BAD_REQUEST, "Solo se puede reservar hasta 60 días antes");
        var phone = request.phone().replaceAll("\\D", "");
        if (phone.length() < 7) throw new ApiException(HttpStatus.BAD_REQUEST, "Revisa tu número de celular");
        var org = jdbc.sql("SELECT organization_id FROM branches WHERE id=:id").param("id", branchId).query(UUID.class).single();
        UUID zoneId = request.zoneId();
        if (request.tableId() != null) {
            var table = jdbc.sql("SELECT zone_id,capacity FROM dining_tables WHERE id=:id AND branch_id=:branch AND active")
                    .param("id", request.tableId()).param("branch", branchId)
                    .query((rs, row) -> new Object[] {rs.getObject("zone_id", UUID.class), rs.getInt("capacity")}).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Mesa inválida"));
            if ((Integer) table[1] < request.partySize()) throw new ApiException(HttpStatus.BAD_REQUEST, "La mesa elegida es pequeña para tu grupo");
            if (busyTables(branchId, request.date(), request.time()).contains(request.tableId()))
                throw new ApiException(HttpStatus.CONFLICT, "Esa mesa acaba de ser reservada. Elige otra, por favor");
            zoneId = (UUID) table[0];
        }
        var id = UUID.randomUUID();
        var notes = request.notes() == null || request.notes().isBlank() ? "Reserva online" : "Reserva online · " + request.notes().trim();
        jdbc.sql("""
                INSERT INTO reservations(id,organization_id,branch_id,customer_id,customer_name,phone,party_size,reserved_at,
                  duration_minutes,table_id,status,notes,zone_id)
                VALUES (:id,:org,:branch,(SELECT id FROM customers WHERE organization_id=:org AND phone=:phone),:name,:phone,:party,:at,
                  :duration,:table,'PENDING',:notes,:zone)
                """).param("id", id).param("org", org).param("branch", branchId).param("phone", phone)
                .param("name", request.name().trim()).param("party", request.partySize()).param("at", Timestamp.from(start))
                .param("duration", DURATION).param("table", request.tableId()).param("notes", notes.length() > 300 ? notes.substring(0, 300) : notes)
                .param("zone", zoneId).update();
        realtime.waiters(branchId, "RESERVATION_UPDATED", new Created(id));
        return new Confirmation(id, request.date(), request.time(), request.partySize());
    }

    /** Activa o desactiva las reservas online de la sucursal del administrador. */
    @Transactional
    public BookingStatus setEnabled(boolean enabled) {
        var branch = currentUser.require().branchId();
        jdbc.sql("UPDATE branches SET online_booking=:enabled,updated_at=NOW() WHERE id=:id").param("enabled", enabled).param("id", branch).update();
        return status();
    }

    public BookingStatus status() {
        var branch = currentUser.require().branchId();
        var enabled = jdbc.sql("SELECT online_booking FROM branches WHERE id=:id").param("id", branch).query(Boolean.class).single();
        return new BookingStatus(branch, enabled);
    }

    /** Máximo 4 reservas por hora desde el mismo dispositivo para frenar abusos. */
    private void throttle(String key) {
        var now = Instant.now();
        var recent = attempts.compute(key, (ignored, list) -> {
            var kept = list == null ? new java.util.ArrayList<Instant>() : new java.util.ArrayList<>(list.stream().filter(at -> at.isAfter(now.minusSeconds(3600))).toList());
            kept.add(now);
            return kept;
        });
        if (recent.size() > 4) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Hiciste varias reservas seguidas. Inténtalo en un rato o llama al local");
    }

    public record BookingRequest(String name, String phone, int partySize, LocalDate date, LocalTime time, UUID tableId, UUID zoneId, String notes) {}
    public record BookingPage(String branchName, String address, String organizationName, String logoUrl, String primaryColor,
                              List<ZoneInfo> zones, List<TableInfo> tables, List<ElementInfo> elements) {}
    public record ZoneInfo(UUID id, String name, String color) {}
    public record TableInfo(UUID id, String label, int capacity, UUID zoneId, Integer x, Integer y, String shape, int rotation) {}
    public record ElementInfo(UUID id, UUID zoneId, String kind, String label, int x, int y, int width, int height, int rotation) {}
    public record Confirmation(UUID id, LocalDate date, LocalTime time, int partySize) {}
    public record BookingStatus(UUID branchId, boolean enabled) {}
    private record BranchInfo(String branchName, String address, String organizationName, String logoUrl, String primaryColor, boolean enabled) {}
    private record Created(UUID id) {}
}
