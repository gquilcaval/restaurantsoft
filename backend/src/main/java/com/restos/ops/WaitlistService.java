package com.restos.ops;

import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import com.restos.tables.TableService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lista de espera sin filas: el anfitrión registra al cliente, este sigue su turno desde el celular y
 * recibe el aviso cuando su mesa está lista.
 */
@Service
public class WaitlistService {
    private static final List<String> ACTIVE = List.of("WAITING", "NOTIFIED");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final TableService tables;

    public WaitlistService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime, TableService tables) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.tables = tables;
    }

    /** Clientes esperando (y los atendidos en las últimas horas, para el historial del turno). */
    public WaitlistBoard board() {
        var branch = currentUser.require().branchId();
        var entries = jdbc.sql("""
                        SELECT w.*,t.label table_label FROM waitlist_entries w LEFT JOIN dining_tables t ON t.id=w.table_id
                        WHERE w.branch_id=:branch AND (w.status IN ('WAITING','NOTIFIED') OR w.closed_at > NOW() - INTERVAL '4 hours')
                        ORDER BY CASE WHEN w.status IN ('WAITING','NOTIFIED') THEN 0 ELSE 1 END,w.created_at
                        """).param("branch", branch).query((rs, row) -> map(rs)).list();
        var waiting = entries.stream().filter(entry -> ACTIVE.contains(entry.status())).toList();
        var estimates = java.util.stream.IntStream.range(0, waiting.size())
                .mapToObj(index -> estimate(branch, waiting.get(index).withQueue(index + 1, null))).toList();
        var result = new java.util.ArrayList<WaitlistEntry>();
        for (var entry : entries) {
            var index = waiting.indexOf(entry);
            result.add(index >= 0 ? entry.withQueue(index + 1, estimates.get(index)) : entry);
        }
        var free = jdbc.sql("""
                        SELECT t.id,t.label,t.capacity FROM dining_tables t
                        LEFT JOIN dining_zones z ON z.id=t.zone_id
                        WHERE t.branch_id=:branch AND t.active AND COALESCE(z.open,TRUE)
                          AND NOT EXISTS (SELECT 1 FROM table_sessions ts WHERE ts.table_id=t.id AND ts.status='OPEN')
                        ORDER BY t.capacity,t.label
                        """).param("branch", branch)
                .query((rs, row) -> new FreeTable(rs.getObject("id", UUID.class), rs.getString("label"), rs.getInt("capacity")))
                .list();
        return new WaitlistBoard(result, free, averageVisit(branch));
    }

    @Transactional
    public WaitlistEntry create(String name, String phone, int partySize, String notes) {
        var scope = currentUser.require();
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO waitlist_entries(id,organization_id,branch_id,public_token,customer_name,phone,party_size,notes,created_by)
                VALUES (:id,:org,:branch,:token,:name,:phone,:party,:notes,:user)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("token", UUID.randomUUID()).param("name", name.trim())
                .param("phone", phone == null || phone.isBlank() ? null : phone.replaceAll("[^0-9+]", ""))
                .param("party", partySize).param("notes", notes == null || notes.isBlank() ? null : notes.trim())
                .param("user", scope.userId()).update();
        publish(scope.branchId());
        return find(id);
    }

    /** «Tu mesa está lista»: el celular del cliente cambia a la pantalla de aviso. */
    @Transactional
    public WaitlistEntry notifyReady(UUID id, UUID tableId) {
        var branch = currentUser.require().branchId();
        if (tableId != null) requireTable(branch, tableId);
        var updated = jdbc.sql("""
                UPDATE waitlist_entries SET status='NOTIFIED',notified_at=NOW(),table_id=COALESCE(:table,table_id)
                WHERE id=:id AND branch_id=:branch AND status IN ('WAITING','NOTIFIED')
                """).param("table", tableId).param("id", id).param("branch", branch).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "El cliente ya no está en la lista de espera");
        publish(branch);
        return find(id);
    }

    /** El cliente llegó: se abre la mesa y sale de la lista. */
    @Transactional
    public WaitlistEntry seat(UUID id, UUID tableId) {
        var branch = currentUser.require().branchId();
        var entry = find(id);
        if (!ACTIVE.contains(entry.status())) throw new ApiException(HttpStatus.CONFLICT, "El cliente ya no está en la lista de espera");
        var table = tableId != null ? tableId : entry.tableId();
        if (table == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige la mesa donde se sentará el cliente");
        requireTable(branch, table);
        var opened = tables.open(table);
        jdbc.sql("""
                UPDATE waitlist_entries SET status='SEATED',table_id=:table,session_id=:session,closed_at=NOW() WHERE id=:id
                """).param("table", table).param("session", opened.sessionId()).param("id", id).update();
        publish(branch);
        return find(id);
    }

    @Transactional
    public WaitlistEntry close(UUID id, boolean noShow) {
        var branch = currentUser.require().branchId();
        var updated = jdbc.sql("""
                UPDATE waitlist_entries SET status=:status,closed_at=NOW()
                WHERE id=:id AND branch_id=:branch AND status IN ('WAITING','NOTIFIED')
                """).param("status", noShow ? "NO_SHOW" : "CANCELLED").param("id", id).param("branch", branch).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT, "El cliente ya no está en la lista de espera");
        publish(branch);
        return find(id);
    }

    // ——— Vista pública del cliente ———
    public PublicWaitlist publicView(UUID token) {
        var entry = jdbc.sql("""
                        SELECT w.*,t.label table_label FROM waitlist_entries w LEFT JOIN dining_tables t ON t.id=w.table_id
                        WHERE w.public_token=:token AND w.created_at > NOW() - INTERVAL '1 day'
                        """).param("token", token).query((rs, row) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Este turno ya no está vigente"));
        var branch = branchOf(entry.id());
        var position = 0;
        Integer minutes = null;
        if (ACTIVE.contains(entry.status())) {
            position = jdbc.sql("""
                    SELECT count(*) FROM waitlist_entries WHERE branch_id=:branch AND status IN ('WAITING','NOTIFIED')
                      AND created_at <= :created
                    """).param("branch", branch).param("created", java.sql.Timestamp.from(entry.createdAt()))
                    .query(Integer.class).single();
            minutes = estimate(branch, entry.withQueue(position, null));
        }
        var brand = jdbc.sql("""
                        SELECT o.name,o.logo_url,o.primary_color,b.name branch_name FROM branches b
                        JOIN organizations o ON o.id=b.organization_id WHERE b.id=:branch
                        """).param("branch", branch)
                .query((rs, row) -> new String[]{rs.getString("name"), rs.getString("logo_url"), rs.getString("primary_color"),
                        rs.getString("branch_name")}).single();
        return new PublicWaitlist(entry.customerName(), entry.partySize(), entry.status(), position, minutes,
                entry.tableLabel(), entry.notifiedAt(), brand[0], brand[1], brand[2], brand[3]);
    }

    @Transactional
    public PublicWaitlist publicCancel(UUID token) {
        var branch = jdbc.sql("SELECT branch_id FROM waitlist_entries WHERE public_token=:token")
                .param("token", token).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Este turno ya no está vigente"));
        jdbc.sql("""
                UPDATE waitlist_entries SET status='CANCELLED',closed_at=NOW()
                WHERE public_token=:token AND status IN ('WAITING','NOTIFIED')
                """).param("token", token).update();
        publish(branch);
        return publicView(token);
    }

    /**
     * Espera estimada: mesas libres con capacidad suficiente atienden de inmediato; si no, cada grupo
     * delante consume una fracción de la duración promedio de una visita.
     */
    private Integer estimate(UUID branch, WaitlistEntry entry) {
        var fit = jdbc.sql("""
                        SELECT count(*) total,
                          count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM table_sessions ts WHERE ts.table_id=t.id AND ts.status='OPEN')) free
                        FROM dining_tables t WHERE t.branch_id=:branch AND t.active AND t.capacity >= :party
                        """).param("branch", branch).param("party", entry.partySize())
                .query((rs, row) -> new int[]{rs.getInt("total"), rs.getInt("free")}).single();
        var ahead = Math.max(0, (entry.position() == null ? 1 : entry.position()) - fit[1]);
        if (ahead == 0) return 0;
        var visit = averageVisit(branch);
        return Math.max(5, (int) Math.round(Math.ceil((double) ahead / Math.max(1, fit[0])) * visit * 0.5));
    }

    /** Duración promedio de una visita en la última semana (45 min si aún no hay historial). */
    private int averageVisit(UUID branch) {
        var minutes = jdbc.sql("""
                SELECT avg(EXTRACT(EPOCH FROM (closed_at-opened_at))/60) FROM table_sessions
                WHERE branch_id=:branch AND status='CLOSED' AND table_id IS NOT NULL AND closed_at > NOW() - INTERVAL '7 days'
                  AND closed_at-opened_at BETWEEN INTERVAL '10 minutes' AND INTERVAL '4 hours'
                """).param("branch", branch).query(Double.class).optional().orElse(null);
        return minutes == null ? 45 : (int) Math.round(minutes);
    }

    private void requireTable(UUID branch, UUID tableId) {
        var exists = jdbc.sql("SELECT count(*) FROM dining_tables WHERE id=:id AND branch_id=:branch AND active")
                .param("id", tableId).param("branch", branch).query(Integer.class).single();
        if (exists == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Mesa inválida");
    }

    private UUID branchOf(UUID id) {
        return jdbc.sql("SELECT branch_id FROM waitlist_entries WHERE id=:id").param("id", id).query(UUID.class).single();
    }

    private WaitlistEntry find(UUID id) {
        return jdbc.sql("""
                        SELECT w.*,t.label table_label FROM waitlist_entries w LEFT JOIN dining_tables t ON t.id=w.table_id
                        WHERE w.id=:id
                        """).param("id", id).query((rs, row) -> map(rs)).single();
    }

    private WaitlistEntry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WaitlistEntry(rs.getObject("id", UUID.class), rs.getObject("public_token", UUID.class),
                rs.getString("customer_name"), rs.getString("phone"), rs.getInt("party_size"), rs.getString("notes"),
                rs.getString("status"), rs.getObject("table_id", UUID.class), rs.getString("table_label"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), null, null);
    }

    private void publish(UUID branch) {
        realtime.waiters(branch, "WAITLIST_UPDATED", new Changed(Instant.now()));
    }

    private record Changed(Instant at) {}
    public record WaitlistEntry(UUID id, UUID publicToken, String customerName, String phone, int partySize, String notes,
                                String status, UUID tableId, String tableLabel, Instant createdAt, Instant notifiedAt,
                                Instant closedAt, Integer position, Integer estimatedMinutes) {
        WaitlistEntry withQueue(Integer position, Integer minutes) {
            return new WaitlistEntry(id, publicToken, customerName, phone, partySize, notes, status, tableId, tableLabel,
                    createdAt, notifiedAt, closedAt, position, minutes);
        }
    }
    public record FreeTable(UUID id, String label, int capacity) {}
    public record WaitlistBoard(List<WaitlistEntry> entries, List<FreeTable> freeTables, int averageVisitMinutes) {}
    public record PublicWaitlist(String customerName, int partySize, String status, int position, Integer estimatedMinutes,
                                 String tableLabel, Instant notifiedAt, String restaurant, String logoUrl, String color,
                                 String branchName) {}
}
