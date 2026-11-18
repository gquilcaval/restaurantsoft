package com.restos.waiters;

import com.restos.realtime.RealtimePublisher;
import com.restos.service.ServiceRequestService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Si el mesero a cargo no reacciona, el aviso se abre a todo el equipo (una sola vez):
 * llamado sin «Voy» en 1 minuto, cuenta sin atender en 90 segundos y plato listo sin recoger en 2 minutos.
 */
@Component
public class WaitingAlertsJob {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;

    public WaitingAlertsJob(JdbcClient jdbc, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 25000)
    public void escalate() {
        requests("WAITER_CALL", "1 minute", "WAITER_CALL_ESCALATED");
        requests("BILL", "90 seconds", "BILL_WAITING");
        dishes();
    }

    private void requests(String type, String after, String event) {
        jdbc.sql("""
                UPDATE service_requests sr SET escalation_level=2,escalated_at=NOW(),updated_at=NOW()
                FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=sr.session_id AND ts.status='OPEN' AND sr.type=:type AND sr.status='PENDING'
                  AND sr.escalation_level=0 AND sr.requested_at < NOW() - CAST(:after AS INTERVAL)
                RETURNING sr.id,sr.session_id,sr.branch_id,COALESCE(t.label,ts.label) label,sr.requested_at,sr.reason,ts.bill_note
                """).param("type", type).param("after", after).query((rs, row) -> {
            var view = new ServiceRequestService.RequestView(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                    rs.getString("label"), type, "PENDING", rs.getTimestamp("requested_at").toInstant(),
                    type.equals("BILL") ? rs.getString("bill_note") : null, null, rs.getString("reason"), 2, null);
            if (type.equals("WAITER_CALL")) realtime.table(view.sessionId(), "SERVICE_REQUEST_CREATED", view);
            realtime.waitersEveryone(rs.getObject("branch_id", UUID.class), event, view, view.sessionId());
            return view;
        }).list();
    }

    private void dishes() {
        var waiting = new LinkedHashMap<UUID, DishWaiting>();
        jdbc.sql("""
                UPDATE order_items oi SET ready_escalated_at=NOW()
                FROM orders o JOIN table_sessions ts ON ts.id=o.session_id LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE oi.order_id=o.id AND oi.status='READY' AND oi.ready_escalated_at IS NULL
                  AND oi.ready_at < NOW() - INTERVAL '2 minutes' AND ts.status='OPEN' AND ts.table_id IS NOT NULL
                RETURNING o.session_id,ts.branch_id,COALESCE(t.label,ts.label) label,oi.item_name
                """).query((rs, row) -> {
            var sessionId = rs.getObject("session_id", UUID.class);
            var current = waiting.get(sessionId);
            var items = current == null ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(current.items());
            items.add(rs.getString("item_name"));
            waiting.put(sessionId, new DishWaiting(sessionId, rs.getString("label"), items, rs.getObject("branch_id", UUID.class)));
            return sessionId;
        }).list();
        waiting.values().forEach(dish -> realtime.waitersEveryone(dish.branchId(), "DISH_WAITING", dish, dish.sessionId()));
    }

    /** Platos listos de una mesa que nadie recogió. */
    public record DishWaiting(UUID sessionId, String tableLabel, List<String> items, UUID branchId) {}
}
