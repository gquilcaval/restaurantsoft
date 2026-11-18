package com.restos.orders;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Deriva el estado de una ronda desde sus platos; los anulados no cuentan para el avance. */
@Component
public class OrderStatusRefresher {
    private final JdbcClient jdbc;

    public OrderStatusRefresher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void refresh(UUID orderId) {
        var counts = jdbc.sql("""
                SELECT count(*) total,
                       count(*) FILTER (WHERE status <> 'CANCELLED') active,
                       count(*) FILTER (WHERE status NOT IN ('DELIVERED','CANCELLED')) pending,
                       count(*) FILTER (WHERE status NOT IN ('READY','DELIVERED','CANCELLED')) not_ready,
                       count(*) FILTER (WHERE status = 'PREPARING') preparing
                FROM order_items WHERE order_id=:order
                """).param("order", orderId)
                .query((rs, row) -> new Counts(rs.getInt("total"), rs.getInt("active"), rs.getInt("pending"),
                        rs.getInt("not_ready"), rs.getInt("preparing"))).single();
        var current = jdbc.sql("SELECT status FROM orders WHERE id=:id").param("id", orderId)
                .query(String.class).single();
        var status = counts.total() == 0 ? "CANCELLED"
                : current.equals("DRAFT") ? "DRAFT"
                : counts.active() == 0 ? "CANCELLED"
                : counts.pending() == 0 ? "DELIVERED"
                : counts.notReady() == 0 ? "READY"
                : counts.preparing() > 0 ? "PREPARING" : "SUBMITTED";
        jdbc.sql("UPDATE orders SET status=:status,updated_at=NOW() WHERE id=:id")
                .param("status", status).param("id", orderId).update();
    }

    private record Counts(int total, int active, int pending, int notReady, int preparing) {}
}
