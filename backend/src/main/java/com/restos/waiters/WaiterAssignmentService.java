package com.restos.waiters;

import com.restos.audit.AuditService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.security.UserRole;
import com.restos.shared.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mesero a cargo de cada visita. Al abrirse una mesa queda a cargo el mesero de esa mesa en el plano o,
 * si no hay, el de su zona con menos mesas (entre los que están en turno); si nadie tiene esa mesa o zona,
 * el mesero que la abrió. Una mesa sin mesero la toma el primero que la atiende (pedido, «Voy», cobro).
 */
@Service
public class WaiterAssignmentService {
    private static final String WAITER_ROLES = "('WAITER','BRANCH_MANAGER')";
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final WaiterAvailability availability;
    private final AuditService audit;

    public WaiterAssignmentService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime,
                                   WaiterAvailability availability, AuditService audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.availability = availability;
        this.audit = audit;
    }

    /** Elige y guarda el mesero de una visita recién abierta. openedBy: personal que la abrió (null si fue por QR). */
    public UUID assignOnOpen(UUID sessionId, UUID openedBy) {
        var table = jdbc.sql("""
                        SELECT ts.branch_id,t.waiter_id,t.zone_id FROM table_sessions ts
                        JOIN dining_tables t ON t.id=ts.table_id WHERE ts.id=:id
                        """).param("id", sessionId)
                .query((rs, row) -> new Object[]{rs.getObject("branch_id", UUID.class), rs.getObject("waiter_id", UUID.class),
                        rs.getObject("zone_id", UUID.class)}).optional().orElse(null);
        if (table == null) return null;
        var branchId = (UUID) table[0]; var planWaiter = (UUID) table[1]; var zoneId = (UUID) table[2];
        var onShift = availability.onShift(branchId);
        UUID chosen = null;
        if (planWaiter != null && isActiveWaiter(planWaiter, branchId) && availability.isAvailable(onShift, planWaiter)) chosen = planWaiter;
        if (chosen == null && zoneId != null) {
            chosen = jdbc.sql("""
                            SELECT u.id,(SELECT count(*) FROM table_sessions s WHERE s.waiter_id=u.id AND s.status='OPEN') open_tables
                            FROM user_zones uz JOIN app_users u ON u.id=uz.user_id
                            WHERE uz.zone_id=:zone AND u.active AND u.role IN %s
                            """.formatted(WAITER_ROLES)).param("zone", zoneId)
                    .query((rs, row) -> new Load(rs.getObject("id", UUID.class), rs.getInt("open_tables"))).list().stream()
                    .filter(load -> availability.isAvailable(onShift, load.userId()))
                    .min(Comparator.comparingInt(Load::openTables)).map(Load::userId).orElse(null);
        }
        // Nadie tiene asignada esa mesa ni esa zona: queda a cargo quien la abrió, si es mesero o encargado.
        var assignedSomewhere = planWaiter != null || (zoneId != null && jdbc.sql("SELECT count(*) FROM user_zones WHERE zone_id=:zone")
                .param("zone", zoneId).query(Integer.class).single() > 0);
        if (chosen == null && !assignedSomewhere && openedBy != null && isActiveWaiter(openedBy, branchId)) chosen = openedBy;
        if (chosen != null) {
            jdbc.sql("UPDATE table_sessions SET waiter_id=:waiter,waiter_assigned_at=NOW() WHERE id=:id")
                    .param("waiter", chosen).param("id", sessionId).update();
        }
        return chosen;
    }

    /** El primer mesero que atiende una mesa sin mesero queda a cargo. */
    public void claimIfUnassigned(UUID sessionId) {
        var scope = currentUser.require();
        if (scope.role() != UserRole.WAITER && scope.role() != UserRole.BRANCH_MANAGER) return;
        var updated = jdbc.sql("""
                UPDATE table_sessions SET waiter_id=:waiter,waiter_assigned_at=NOW()
                WHERE id=:id AND waiter_id IS NULL AND status='OPEN' AND table_id IS NOT NULL
                """).param("waiter", scope.userId()).param("id", sessionId).update();
        if (updated > 0) publish(sessionId, scope.branchId(), null);
    }

    /**
     * Cambia el mesero a cargo. Un mesero puede tomar cualquier mesa o pasar una suya (o sin mesero) a otro;
     * pasar la mesa de otro mesero a un tercero lo hace el encargado o el dueño.
     */
    @Transactional
    public Assignment reassign(UUID sessionId, UUID waiterId) {
        var scope = currentUser.require();
        var session = jdbc.sql("""
                        SELECT ts.waiter_id,COALESCE(t.label,ts.label) label FROM table_sessions ts
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE ts.id=:id AND ts.branch_id=:branch AND ts.status='OPEN' FOR UPDATE OF ts
                        """).param("id", sessionId).param("branch", scope.branchId())
                .query((rs, row) -> new Object[]{rs.getObject("waiter_id", UUID.class), rs.getString("label")}).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "La mesa ya no está abierta"));
        var current = (UUID) session[0];
        if (waiterId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige un mesero");
        if (!isActiveWaiter(waiterId, scope.branchId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Elige un mesero activo de la sucursal");
        if (scope.role() == UserRole.WAITER && !waiterId.equals(scope.userId()) && current != null && !current.equals(scope.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo el encargado puede pasar la mesa de otro mesero");
        }
        if (waiterId.equals(current)) return assignmentOf(sessionId);
        jdbc.sql("UPDATE table_sessions SET waiter_id=:waiter,waiter_assigned_at=NOW() WHERE id=:id")
                .param("waiter", waiterId).param("id", sessionId).update();
        var names = jdbc.sql("SELECT id,full_name FROM app_users WHERE id IN (:ids)")
                .param("ids", current == null ? List.of(waiterId) : List.of(waiterId, current))
                .query((rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("full_name")}).list();
        var to = names.stream().filter(row -> row[0].equals(waiterId)).map(row -> (String) row[1]).findFirst().orElse("");
        var from = names.stream().filter(row -> row[0].equals(current)).map(row -> (String) row[1]).findFirst().orElse(null);
        audit.record("TABLE_WAITER_CHANGED", session[1] + ": " + (from == null ? "tomada por " + to : from + " → " + to), null, sessionId);
        publish(sessionId, scope.branchId(), current);
        return assignmentOf(sessionId);
    }

    /** Meseros y encargados de la sucursal, con su turno y cuántas mesas tienen ahora (para elegir a quién pasar). */
    public List<WaiterOption> waiters() {
        var branchId = currentUser.require().branchId();
        var onShift = availability.onShift(branchId);
        return jdbc.sql("""
                        SELECT u.id,u.full_name,u.role,(SELECT count(*) FROM table_sessions s WHERE s.waiter_id=u.id AND s.status='OPEN') open_tables
                        FROM app_users u WHERE u.branch_id=:branch AND u.active AND u.role IN %s ORDER BY u.full_name
                        """.formatted(WAITER_ROLES)).param("branch", branchId)
                .query((rs, row) -> new WaiterOption(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("role"),
                        availability.isAvailable(onShift, rs.getObject("id", UUID.class)), rs.getInt("open_tables"))).list();
    }

    public Assignment assignmentOf(UUID sessionId) {
        return jdbc.sql("""
                        SELECT ts.id,ts.waiter_id,u.full_name,COALESCE(t.label,ts.label) label FROM table_sessions ts
                        LEFT JOIN app_users u ON u.id=ts.waiter_id LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE ts.id=:id
                        """).param("id", sessionId)
                .query((rs, row) -> new Assignment(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getObject("waiter_id", UUID.class), rs.getString("full_name"), null)).single();
    }

    /** Avisa al salón (para actualizar la mesa y avisarle al nuevo mesero) y a la mesa (el comensal ve quién lo atiende). */
    private void publish(UUID sessionId, UUID branchId, UUID previous) {
        var assignment = assignmentOf(sessionId);
        var event = new Assignment(assignment.sessionId(), assignment.tableLabel(), assignment.waiterId(), assignment.waiterName(), previous);
        realtime.waiters(branchId, "TABLE_WAITER_CHANGED", event);
        realtime.table(sessionId, "TABLE_WAITER_CHANGED", new DinerWaiter(sessionId, firstName(assignment.waiterName())));
    }

    private boolean isActiveWaiter(UUID userId, UUID branchId) {
        return jdbc.sql("SELECT count(*) FROM app_users WHERE id=:id AND branch_id=:branch AND active AND role IN " + WAITER_ROLES)
                .param("id", userId).param("branch", branchId).query(Integer.class).single() > 0;
    }

    static String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? null : fullName.trim().split("\\s+")[0];
    }

    private record Load(UUID userId, int openTables) {}
    /** previousWaiterId solo viaja en el aviso de cambio, para que el mesero anterior sepa que ya no es suya. */
    public record Assignment(UUID sessionId, String tableLabel, UUID waiterId, String waiterName, UUID previousWaiterId) {}
    public record DinerWaiter(UUID sessionId, String waiterName) {}
    public record WaiterOption(UUID id, String fullName, String role, boolean onShift, int openTables) {}
}
