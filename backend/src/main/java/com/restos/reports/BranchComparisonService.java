package com.restos.reports;

import com.restos.payments.PaymentService;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Comparativo de sucursales de la organización en un mismo período (solo para el dueño). */
@Service
public class BranchComparisonService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ReportService reports;

    public BranchComparisonService(JdbcClient jdbc, CurrentUser currentUser, ReportService reports) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.reports = reports;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Comparison compare(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Selecciona un período válido de hasta 366 días");
        }
        var org = currentUser.require().organizationId();
        var zone = reports.zone();
        var start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        var end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        // Descuentos por sucursal: se calculan igual que en la cuenta (porcentaje sobre el consumo vigente).
        var discounts = new HashMap<UUID, BigDecimal>();
        jdbc.sql("""
                        SELECT ts.branch_id,ts.discount_kind,ts.discount_value,
                          (SELECT COALESCE(sum(oi.unit_price*oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                           WHERE o.session_id=ts.id AND o.status<>'DRAFT' AND oi.status<>'CANCELLED') subtotal
                        FROM table_sessions ts WHERE ts.organization_id=:org AND ts.status='CLOSED'
                          AND ts.discount_kind IS NOT NULL AND ts.closed_at>=:start AND ts.closed_at<:end
                        """).param("org", org).param("start", start).param("end", end)
                .query((rs, row) -> discounts.merge(rs.getObject("branch_id", UUID.class),
                        PaymentService.discountAmount(rs.getString("discount_kind"), rs.getBigDecimal("discount_value"),
                                rs.getBigDecimal("subtotal")), BigDecimal::add)).list();
        var rows = jdbc.sql("""
                        SELECT b.id,b.name,
                          (SELECT COALESCE(sum(p.amount),0) FROM payments p WHERE p.branch_id=b.id AND p.paid_at>=:start AND p.paid_at<:end) collected,
                          (SELECT count(*) FROM payments p WHERE p.branch_id=b.id AND p.paid_at>=:start AND p.paid_at<:end) payments,
                          (SELECT count(DISTINCT p.session_id) FROM payments p WHERE p.branch_id=b.id AND p.paid_at>=:start AND p.paid_at<:end) sessions,
                          (SELECT COALESCE(sum(p.tip),0) FROM payments p WHERE p.branch_id=b.id AND p.paid_at>=:start AND p.paid_at<:end) tips,
                          (SELECT count(*) FROM table_sessions ts WHERE ts.branch_id=b.id AND ts.channel<>'DINE_IN'
                             AND ts.status='CLOSED' AND ts.closed_at>=:start AND ts.closed_at<:end) counter_orders,
                          (SELECT COALESCE(sum(oi.unit_price*oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                             WHERE o.branch_id=b.id AND oi.voided_at>=:start AND oi.voided_at<:end) voids,
                          (SELECT COALESCE(-sum(m.quantity*m.unit_cost),0) FROM stock_movements m WHERE m.branch_id=b.id
                             AND m.kind IN ('SALE','VOID_RETURN') AND m.created_at>=:start AND m.created_at<:end) inventory_cost,
                          (SELECT oi.item_name FROM order_items oi JOIN orders o ON o.id=oi.order_id
                             WHERE o.branch_id=b.id AND o.submitted_at>=:start AND o.submitted_at<:end
                               AND o.status NOT IN ('DRAFT','CANCELLED') AND oi.status<>'CANCELLED'
                             GROUP BY oi.item_name ORDER BY sum(oi.quantity) DESC LIMIT 1) top_product
                        FROM branches b WHERE b.organization_id=:org AND b.active ORDER BY collected DESC,b.name
                        """).param("org", org).param("start", start).param("end", end)
                .query((rs, row) -> {
                    var id = rs.getObject("id", UUID.class);
                    var collected = rs.getBigDecimal("collected");
                    var sessions = rs.getLong("sessions");
                    var cost = rs.getBigDecimal("inventory_cost").setScale(2, RoundingMode.HALF_UP);
                    return new BranchRow(id, rs.getString("name"), collected, rs.getLong("payments"), sessions,
                            sessions == 0 ? BigDecimal.ZERO : collected.divide(BigDecimal.valueOf(sessions), 2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("tips"), rs.getLong("counter_orders"), rs.getBigDecimal("voids"),
                            discounts.getOrDefault(id, BigDecimal.ZERO), cost, collected.subtract(cost), rs.getString("top_product"));
                }).list();
        var total = rows.stream().map(BranchRow::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Comparison(from, to, zone.getId(), rows, total);
    }

    public record BranchRow(UUID branchId, String name, BigDecimal collected, long payments, long sessions,
                            BigDecimal averageTicket, BigDecimal tips, long counterOrders, BigDecimal voids,
                            BigDecimal discounts, BigDecimal inventoryCost, BigDecimal grossMargin, String topProduct) {}
    public record Comparison(LocalDate from, LocalDate to, String timezone, List<BranchRow> branches, BigDecimal total) {}
}
