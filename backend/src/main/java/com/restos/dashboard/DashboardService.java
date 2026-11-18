package com.restos.dashboard;

import com.restos.security.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Calcula el tablero desde ventas, pedidos y atenciones persistidas; no genera métricas simuladas. */
@Service
public class DashboardService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public DashboardService(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    public DashboardView summary() {
        var branchId = currentUser.require().branchId();
        var totals = jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND active) total_tables,
                  (SELECT count(*) FROM table_sessions WHERE branch_id=:branch AND status='OPEN' AND table_id IS NOT NULL) open_tables,
                  (SELECT count(*) FROM guests g JOIN table_sessions ts ON ts.id=g.session_id
                    WHERE ts.branch_id=:branch AND ts.status='OPEN' AND g.active) active_guests,
                  (SELECT count(*) FROM orders WHERE branch_id=:branch AND submitted_at::date=CURRENT_DATE) orders_today,
                  (SELECT COALESCE(sum(amount),0) FROM payments
                    WHERE branch_id=:branch AND paid_at::date=CURRENT_DATE) today_revenue,
                  (SELECT COALESCE(sum(amount)/NULLIF(count(DISTINCT session_id),0),0) FROM payments
                    WHERE branch_id=:branch AND paid_at::date=CURRENT_DATE) average_ticket
                """).param("branch", branchId).query((rs, row) -> new Totals(
                rs.getInt("total_tables"), rs.getInt("open_tables"), rs.getInt("active_guests"),
                rs.getInt("orders_today"), rs.getBigDecimal("today_revenue"),
                rs.getBigDecimal("average_ticket"))).single();

        var sales = jdbc.sql("""
                SELECT calendar.sales_day::date sales_day,COALESCE(sum(p.amount),0) total
                FROM generate_series(CURRENT_DATE-INTERVAL '6 days',CURRENT_DATE,INTERVAL '1 day') calendar(sales_day)
                LEFT JOIN payments p ON p.branch_id=:branch
                  AND p.paid_at>=calendar.sales_day AND p.paid_at<calendar.sales_day+INTERVAL '1 day'
                GROUP BY calendar.sales_day ORDER BY calendar.sales_day
                """).param("branch", branchId).query((rs, row) -> new DailySale(
                rs.getObject("sales_day", LocalDate.class), rs.getBigDecimal("total"))).list();

        var itemStates = jdbc.sql("""
                SELECT
                  count(*) FILTER (WHERE oi.status='SUBMITTED') queued,
                  count(*) FILTER (WHERE oi.status='PREPARING') preparing,
                  count(*) FILTER (WHERE oi.status='READY') ready,
                  count(*) FILTER (WHERE oi.status='DELIVERED') delivered
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                JOIN table_sessions ts ON ts.id=o.session_id
                WHERE o.branch_id=:branch AND ts.status='OPEN' AND o.status<>'DRAFT'
                """).param("branch", branchId).query((rs, row) -> new ItemStates(
                rs.getInt("queued"), rs.getInt("preparing"), rs.getInt("ready"),
                rs.getInt("delivered"))).single();

        var activity = jdbc.sql("""
                SELECT occurred_at,kind,table_label,description,amount FROM (
                  SELECT p.paid_at occurred_at,'PAYMENT' kind,COALESCE(t.label,ts.label) table_label,
                    CASE p.method WHEN 'CASH' THEN 'Pago en efectivo' WHEN 'CARD' THEN 'Pago con tarjeta'
                      ELSE 'Pago con '||p.method END description,p.amount amount
                  FROM payments p JOIN table_sessions ts ON ts.id=p.session_id
                  LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE p.branch_id=:branch
                  UNION ALL
                  SELECT o.submitted_at occurred_at,'ORDER' kind,COALESCE(t.label,ts.label) table_label,
                    'Ronda '||o.round_number||' enviada a cocina' description,
                    COALESCE(sum(oi.unit_price*oi.quantity),0) amount
                  FROM orders o JOIN table_sessions ts ON ts.id=o.session_id
                  LEFT JOIN dining_tables t ON t.id=ts.table_id
                  LEFT JOIN order_items oi ON oi.order_id=o.id AND oi.status<>'CANCELLED'
                  WHERE o.branch_id=:branch AND o.submitted_at IS NOT NULL
                  GROUP BY o.id,o.submitted_at,COALESCE(t.label,ts.label),o.round_number
                ) recent ORDER BY occurred_at DESC LIMIT 8
                """).param("branch", branchId).query((rs, row) -> new Activity(
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("kind"),
                rs.getString("table_label"), rs.getString("description"),
                rs.getBigDecimal("amount"))).list();

        return new DashboardView(totals.totalTables(), totals.openTables(), totals.activeGuests(),
                totals.ordersToday(), totals.todayRevenue(), totals.averageTicket(), sales, itemStates, activity);
    }

    private record Totals(int totalTables, int openTables, int activeGuests, int ordersToday,
                          BigDecimal todayRevenue, BigDecimal averageTicket) {}
    public record DailySale(LocalDate day, BigDecimal total) {}
    public record ItemStates(int queued, int preparing, int ready, int delivered) {}
    public record Activity(Instant occurredAt, String kind, String tableLabel, String description,
                           BigDecimal amount) {}
    public record DashboardView(int totalTables, int openTables, int activeGuests, int ordersToday,
                                BigDecimal todayRevenue, BigDecimal averageTicket,
                                List<DailySale> sales, ItemStates itemStates,
                                List<Activity> activity) {}
}
