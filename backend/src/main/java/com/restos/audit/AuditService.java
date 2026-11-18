package com.restos.audit;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Bitácora de acciones sensibles. Se escribe dentro de la misma transacción que la acción, así nunca
 * queda registrada una acción que no ocurrió ni una acción sin su registro.
 */
@Service
public class AuditService {
    public static final Set<String> ACTIONS = Set.of(
            "ITEM_VOIDED", "DISCOUNT_APPLIED", "DISCOUNT_REMOVED", "PAYMENT_REGISTERED", "SESSION_CLOSED",
            "SESSION_CANCELLED", "CASH_OPENED", "CASH_MOVEMENT", "CASH_CLOSED", "AVAILABILITY_CHANGED",
            "PRICE_CHANGED", "USER_CREATED", "USER_UPDATED", "PIN_CHANGED", "DEVICE_REGISTERED",
            "DEVICE_REVOKED", "PIN_LOGIN", "STOCK_PURCHASE", "STOCK_WASTE", "STOCK_COUNT", "POINTS_REDEEMED",
            "POINTS_ADJUSTED", "RESERVATION_CANCELLED", "ATTENDANCE_ADDED", "ATTENDANCE_CHANGED", "ATTENDANCE_VOIDED",
            "SCHEDULE_CHANGED", "ATTENDANCE_SETTINGS", "MODULE_CHANGED",
            "TABLE_WAITER_CHANGED");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ZoneId zone;

    public AuditService(JdbcClient jdbc, CurrentUser currentUser,
                        @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.zone = ZoneId.of(zone);
    }

    /** Registra una acción del usuario autenticado en su sucursal actual. */
    public void record(String action, String summary, BigDecimal amount, UUID entityId) {
        var scope = currentUser.require();
        record(scope.organizationId(), scope.branchId(), scope.userId(), action, summary, amount, entityId);
    }

    /** Variante para flujos sin JWT todavía (por ejemplo, el ingreso con PIN). */
    public void record(UUID organizationId, UUID branchId, UUID userId, String action, String summary,
                       BigDecimal amount, UUID entityId) {
        if (organizationId == null) return;
        jdbc.sql("""
                INSERT INTO audit_events(organization_id,branch_id,user_id,action,summary,amount,entity_id)
                VALUES (:org,:branch,:user,:action,:summary,:amount,:entity)
                """).param("org", organizationId).param("branch", branchId).param("user", userId)
                .param("action", action).param("summary", truncate(summary)).param("amount", amount)
                .param("entity", entityId).update();
    }

    public Page list(LocalDate from, LocalDate to, String action, int page) { return list(from, to, action, null, "", page); }

    /** Bitácora filtrada: una acción o varias separadas por coma, una persona y un texto a buscar. */
    public Page list(LocalDate from, LocalDate to, String action, UUID userId, String query, int page) {
        validatePeriod(from, to);
        var actions = actions(action);
        var branch = currentUser.require().branchId();
        var start = from.atStartOfDay(zone).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var term = query == null ? "" : query.trim().toLowerCase();
        var filter = "a.branch_id=:branch AND a.created_at>=:start AND a.created_at<:end"
                + (actions.isEmpty() ? "" : " AND a.action IN (:actions)")
                + (userId == null ? "" : " AND a.user_id=:user")
                + (term.isEmpty() ? "" : " AND lower(a.summary) LIKE '%'||:term||'%'");
        var countSql = jdbc.sql("SELECT count(*) FROM audit_events a WHERE " + filter)
                .param("branch", branch).param("start", start).param("end", end);
        if (!actions.isEmpty()) countSql = countSql.param("actions", actions);
        if (userId != null) countSql = countSql.param("user", userId);
        if (!term.isEmpty()) countSql = countSql.param("term", term);
        var total = countSql.query(Long.class).single();
        var events = jdbc.sql("""
                        SELECT a.id,a.created_at,a.action,a.summary,a.amount,COALESCE(u.full_name,'Sistema') user_name
                        FROM audit_events a LEFT JOIN app_users u ON u.id=a.user_id WHERE
                        """ + filter + " ORDER BY a.created_at DESC,a.id LIMIT 50 OFFSET :offset")
                .param("branch", branch).param("start", start).param("end", end)
                .param("offset", Math.max(0, page) * 50);
        if (!actions.isEmpty()) events = events.param("actions", actions);
        if (userId != null) events = events.param("user", userId);
        if (!term.isEmpty()) events = events.param("term", term);
        var list = events.query((rs, row) -> new Event(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("action"), rs.getString("summary"), rs.getBigDecimal("amount"),
                        rs.getString("user_name"))).list();
        return new Page(list, total, page, 50, zone.getId());
    }

    /** Resumen del período: acciones por tipo y por persona, para detectar señales de alerta. */
    public Summary summary(LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        var branch = currentUser.require().branchId();
        var start = from.atStartOfDay(zone).toOffsetDateTime();
        var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var byAction = jdbc.sql("""
                        SELECT action,count(*) count,COALESCE(sum(amount),0) amount FROM audit_events
                        WHERE branch_id=:branch AND created_at>=:start AND created_at<:end GROUP BY action ORDER BY count DESC
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new ActionCount(rs.getString("action"), rs.getLong("count"), rs.getBigDecimal("amount"))).list();
        var byUser = jdbc.sql("""
                        SELECT a.user_id,COALESCE(u.full_name,'Sistema') name,count(*) count,
                          count(*) FILTER (WHERE a.action='ITEM_VOIDED') voids,COALESCE(sum(a.amount) FILTER (WHERE a.action='ITEM_VOIDED'),0) void_amount,
                          count(*) FILTER (WHERE a.action='DISCOUNT_APPLIED') discounts,COALESCE(sum(a.amount) FILTER (WHERE a.action='DISCOUNT_APPLIED'),0) discount_amount,
                          count(*) FILTER (WHERE a.action IN ('SESSION_CANCELLED','CASH_MOVEMENT','PRICE_CHANGED')) sensitive
                        FROM audit_events a LEFT JOIN app_users u ON u.id=a.user_id
                        WHERE a.branch_id=:branch AND a.created_at>=:start AND a.created_at<:end
                        GROUP BY a.user_id,u.full_name ORDER BY count DESC
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new UserCount(rs.getObject("user_id", UUID.class), rs.getString("name"), rs.getLong("count"),
                        rs.getLong("voids"), rs.getBigDecimal("void_amount"), rs.getLong("discounts"), rs.getBigDecimal("discount_amount"),
                        rs.getLong("sensitive"))).list();
        return new Summary(byAction, byUser, zone.getId());
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Selecciona un período válido de hasta 366 días");
        }
    }

    private List<String> actions(String action) {
        if (action == null || action.isBlank() || action.equals("ALL")) return List.of();
        var list = java.util.Arrays.stream(action.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (list.isEmpty() || list.size() > 30 || !ACTIONS.containsAll(list)) throw new ApiException(HttpStatus.BAD_REQUEST, "Tipo de acción inválido");
        return list;
    }

    private String truncate(String value) {
        return value.length() <= 400 ? value : value.substring(0, 397) + "…";
    }

    public record Event(UUID id, Instant createdAt, String action, String summary, BigDecimal amount, String userName) {}
    public record ActionCount(String action, long count, BigDecimal amount) {}
    public record UserCount(UUID userId, String name, long count, long voids, BigDecimal voidAmount, long discounts,
                            BigDecimal discountAmount, long sensitive) {}
    public record Summary(List<ActionCount> actions, List<UserCount> users, String timezone) {}
    public record Page(List<Event> events, long total, int page, int pageSize, String timezone) {}
}
