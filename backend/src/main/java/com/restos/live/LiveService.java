package com.restos.live;

import com.restos.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Datos del Modo TV: el pulso del local en este momento, el mapa de calor de las mesas y la
 * repetición del día (cuándo estuvo ocupada cada mesa).
 */
@Service
public class LiveService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ZoneId zone;

    public LiveService(JdbcClient jdbc, CurrentUser currentUser,
                       @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.zone = ZoneId.of(zone);
    }

    public LiveSummary summary() {
        var branch = currentUser.require().branchId();
        var start = Timestamp.from(LocalDate.now(zone).atStartOfDay(zone).toInstant());
        var money = jdbc.sql("""
                        SELECT COALESCE(sum(amount),0) revenue,count(*) payments,count(DISTINCT session_id) sessions
                        FROM payments WHERE branch_id=:branch AND paid_at>=:start
                        """).param("branch", branch).param("start", start)
                .query((rs, row) -> new Object[]{rs.getBigDecimal("revenue"), rs.getInt("payments"), rs.getInt("sessions")})
                .single();
        var revenue = (BigDecimal) money[0];
        var paidSessions = (int) money[2];
        var orders = jdbc.sql("""
                SELECT count(*) FROM orders WHERE branch_id=:branch AND submitted_at>=:start AND status<>'CANCELLED'
                """).param("branch", branch).param("start", start).query(Integer.class).single();
        var tables = jdbc.sql("""
                        SELECT (SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND active) total,
                               (SELECT count(*) FROM table_sessions WHERE branch_id=:branch AND status='OPEN' AND table_id IS NOT NULL) busy,
                               (SELECT count(*) FROM guests g JOIN table_sessions ts ON ts.id=g.session_id
                                 WHERE ts.branch_id=:branch AND ts.status='OPEN' AND g.active) guests,
                               (SELECT COALESCE(sum(oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                                 WHERE o.branch_id=:branch AND oi.status IN ('SUBMITTED','PREPARING')) in_kitchen
                        """).param("branch", branch)
                .query((rs, row) -> new int[]{rs.getInt("total"), rs.getInt("busy"), rs.getInt("guests"), rs.getInt("in_kitchen")})
                .single();
        var kitchenMinutes = jdbc.sql("""
                SELECT avg(EXTRACT(EPOCH FROM (oi.ready_at-o.submitted_at))/60) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.branch_id=:branch AND oi.ready_at>=:start AND o.submitted_at IS NOT NULL
                """).param("branch", branch).param("start", start).query(Double.class).optional().orElse(null);
        var topHour = top(branch, Timestamp.from(Instant.now().minusSeconds(3600)));
        var topToday = top(branch, start);
        var hourly = jdbc.sql("""
                        SELECT EXTRACT(HOUR FROM paid_at AT TIME ZONE :zone)::int AS hour,sum(amount) AS amount
                        FROM payments WHERE branch_id=:branch AND paid_at>=:start GROUP BY 1 ORDER BY 1
                        """).param("zone", zone.getId()).param("branch", branch).param("start", start)
                .query((rs, row) -> new HourAmount(rs.getInt("hour"), rs.getBigDecimal("amount"))).list();
        var recent = jdbc.sql("""
                        SELECT p.id,COALESCE(t.label,ts.label,'Mostrador') label,p.method,p.amount,p.paid_at
                        FROM payments p JOIN table_sessions ts ON ts.id=p.session_id LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE p.branch_id=:branch AND p.paid_at>=:start ORDER BY p.paid_at DESC LIMIT 6
                        """).param("branch", branch).param("start", start)
                .query((rs, row) -> new RecentPayment(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getString("method"), rs.getBigDecimal("amount"), rs.getTimestamp("paid_at").toInstant())).list();
        // Mismo día de la semana pasada, hasta esta misma hora: compara peras con peras.
        var weekAgo = Timestamp.from(start.toInstant().minusSeconds(7L * 24 * 3600));
        var weekAgoNow = Timestamp.from(Instant.now().minusSeconds(7L * 24 * 3600));
        var lastWeek = jdbc.sql("""
                        SELECT COALESCE(sum(amount),0) revenue,count(DISTINCT session_id) sessions
                        FROM payments WHERE branch_id=:branch AND paid_at>=:from AND paid_at<:to
                        """).param("branch", branch).param("from", weekAgo).param("to", weekAgoNow)
                .query((rs, row) -> new Object[]{rs.getBigDecimal("revenue"), rs.getInt("sessions")}).single();
        var lastWeekRevenue = (BigDecimal) lastWeek[0];
        var lastWeekTicket = (int) lastWeek[1] == 0 ? BigDecimal.ZERO
                : lastWeekRevenue.divide(BigDecimal.valueOf((int) lastWeek[1]), 2, java.math.RoundingMode.HALF_UP);
        var ratings = jdbc.sql("""
                        SELECT avg(stars) FILTER (WHERE created_at > NOW() - INTERVAL '7 days') week_avg,
                               count(*) FILTER (WHERE created_at >= :start) today,
                               (SELECT comment FROM service_ratings WHERE branch_id=:branch AND comment IS NOT NULL AND stars >= 4
                                  ORDER BY created_at DESC LIMIT 1) comment
                        FROM service_ratings WHERE branch_id=:branch
                        """).param("branch", branch).param("start", start)
                .query((rs, row) -> new Object[]{rs.getObject("week_avg") == null ? null : Math.round(rs.getDouble("week_avg") * 10) / 10.0,
                        rs.getInt("today"), rs.getString("comment")}).single();
        var averageTicket = paidSessions == 0 ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(paidSessions), 2, java.math.RoundingMode.HALF_UP);
        return new LiveSummary(revenue, (int) money[1], averageTicket, orders, tables[0], tables[1], tables[2], tables[3],
                kitchenMinutes == null ? null : Math.round(kitchenMinutes * 10) / 10.0, topHour, topToday, hourly, recent,
                liveTables(branch), Instant.now(), lastWeekRevenue, lastWeekTicket, (Double) ratings[0], (int) ratings[1],
                (String) ratings[2]);
    }

    /** Consumo, comensales y pendientes de cada mesa abierta, en una sola consulta. */
    private List<LiveTable> liveTables(UUID branch) {
        return jdbc.sql("""
                        SELECT ts.table_id,ts.id session_id,
                          (SELECT COALESCE(sum(oi.unit_price*oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                             WHERE o.session_id=ts.id AND o.status NOT IN ('DRAFT','CANCELLED') AND oi.status<>'CANCELLED') total,
                          (SELECT count(*) FROM guests g WHERE g.session_id=ts.id AND g.active) guests,
                          (SELECT COALESCE(sum(oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                             WHERE o.session_id=ts.id AND oi.status='READY') ready,
                          EXISTS (SELECT 1 FROM order_items oi JOIN orders o ON o.id=oi.order_id
                             WHERE o.session_id=ts.id AND oi.status IN ('SUBMITTED','PREPARING')) cooking,
                          EXISTS (SELECT 1 FROM service_requests s WHERE s.session_id=ts.id AND s.type='WAITER_CALL'
                             AND s.status IN ('PENDING','IN_PROGRESS')) calling,
                          EXISTS (SELECT 1 FROM service_requests s WHERE s.session_id=ts.id AND s.type='BILL'
                             AND s.status IN ('PENDING','IN_PROGRESS')) bill
                        FROM table_sessions ts WHERE ts.branch_id=:branch AND ts.status='OPEN' AND ts.table_id IS NOT NULL
                        """).param("branch", branch)
                .query((rs, row) -> new LiveTable(rs.getObject("table_id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getBigDecimal("total"), rs.getInt("guests"), rs.getInt("ready"), rs.getBoolean("cooking"),
                        rs.getBoolean("calling"), rs.getBoolean("bill"))).list();
    }

    private List<ProductCount> top(UUID branch, Timestamp since) {
        return jdbc.sql("""
                        SELECT oi.item_name,sum(oi.quantity) quantity FROM order_items oi JOIN orders o ON o.id=oi.order_id
                        WHERE o.branch_id=:branch AND o.submitted_at>=:since AND oi.status<>'CANCELLED'
                        GROUP BY oi.item_name ORDER BY 2 DESC,1 LIMIT 5
                        """).param("branch", branch).param("since", since)
                .query((rs, row) -> new ProductCount(rs.getString("item_name"), rs.getInt("quantity"))).list();
    }

    /** Cuánto vendió, cuántas veces giró y cuánto tiempo estuvo ocupada cada mesa en el período. */
    public List<TableHeat> heat(LocalDate from, LocalDate to) {
        var branch = currentUser.require().branchId();
        var start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        var end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        return jdbc.sql("""
                        SELECT t.id,
                          COALESCE((SELECT sum(p.amount) FROM payments p JOIN table_sessions ts ON ts.id=p.session_id
                             WHERE ts.table_id=t.id AND p.paid_at>=:start AND p.paid_at<:end),0) revenue,
                          (SELECT count(*) FROM table_sessions ts WHERE ts.table_id=t.id AND ts.opened_at>=:start
                             AND ts.opened_at<:end AND (ts.status<>'CANCELLED' OR EXISTS (
                               SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status<>'DRAFT'))) turns,
                          COALESCE((SELECT sum(EXTRACT(EPOCH FROM (LEAST(COALESCE(ts.closed_at,NOW()),:end)-GREATEST(ts.opened_at,:start)))/60)
                             FROM table_sessions ts WHERE ts.table_id=t.id AND ts.opened_at<:end
                             AND COALESCE(ts.closed_at,NOW())>:start),0) minutes
                        FROM dining_tables t WHERE t.branch_id=:branch AND t.active
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new TableHeat(rs.getObject("id", UUID.class), rs.getBigDecimal("revenue"),
                        rs.getInt("turns"), (int) Math.round(rs.getDouble("minutes")))).list();
    }

    /** Intervalos de ocupación de cada mesa en el día, para reproducir cómo se llenó el salón. */
    public DayReplay replay(LocalDate date) {
        var branch = currentUser.require().branchId();
        var start = Timestamp.from(date.atStartOfDay(zone).toInstant());
        var end = Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant());
        var sessions = jdbc.sql("""
                        SELECT ts.table_id,ts.opened_at,ts.closed_at FROM table_sessions ts
                        WHERE ts.branch_id=:branch AND ts.table_id IS NOT NULL AND ts.opened_at<:end
                          AND COALESCE(ts.closed_at,NOW())>:start
                          AND (ts.status<>'CANCELLED' OR EXISTS (SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status<>'DRAFT'))
                        ORDER BY ts.opened_at
                        """).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new Occupancy(rs.getObject("table_id", UUID.class), rs.getTimestamp("opened_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant())).list();
        var hourly = jdbc.sql("""
                        SELECT EXTRACT(HOUR FROM paid_at AT TIME ZONE :zone)::int AS hour,sum(amount) AS amount
                        FROM payments WHERE branch_id=:branch AND paid_at>=:start AND paid_at<:end GROUP BY 1 ORDER BY 1
                        """).param("zone", zone.getId()).param("branch", branch).param("start", start).param("end", end)
                .query((rs, row) -> new HourAmount(rs.getInt("hour"), rs.getBigDecimal("amount"))).list();
        return new DayReplay(date, start.toInstant(), sessions, hourly);
    }

    public record ProductCount(String name, int quantity) {}
    public record HourAmount(int hour, BigDecimal amount) {}
    public record RecentPayment(UUID id, String label, String method, BigDecimal amount, Instant paidAt) {}
    public record LiveTable(UUID tableId, UUID sessionId, BigDecimal total, int guests, int ready, boolean cooking,
                            boolean calling, boolean bill) {}
    public record LiveSummary(BigDecimal revenue, int payments, BigDecimal averageTicket, int orders, int tables,
                              int busyTables, int guests, int inKitchen, Double kitchenMinutes,
                              List<ProductCount> topHour, List<ProductCount> topToday, List<HourAmount> hourly,
                              List<RecentPayment> recent, List<LiveTable> live, Instant generatedAt,
                              BigDecimal lastWeekRevenue, BigDecimal lastWeekTicket, Double ratingAverage, int ratingsToday,
                              String lastComment) {}
    public record TableHeat(UUID tableId, BigDecimal revenue, int turns, int minutes) {}
    public record Occupancy(UUID tableId, Instant openedAt, Instant closedAt) {}
    public record DayReplay(LocalDate date, Instant dayStart, List<Occupancy> sessions, List<HourAmount> hourly) {}
}
