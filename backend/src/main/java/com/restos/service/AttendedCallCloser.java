package com.restos.service;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.restos.realtime.RealtimePublisher;

/**
 * Cierra solos los llamados al mesero ya atendidos («Voy»): cuando el mesero actúa en esa mesa
 * (pedido o cobro) o, si no, a los 5 minutos. Así nadie tiene que marcar «resuelto».
 * El pedido de cuenta no se toca: se cierra al cobrar.
 */
@Component
public class AttendedCallCloser {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;

    public AttendedCallCloser(JdbcClient jdbc, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    /** El mesero hizo algo en la mesa: el llamado que estaba atendiendo queda resuelto. */
    public void closeForSession(UUID sessionId) {
        close("sr.session_id=:session AND sr.status='IN_PROGRESS'", sessionId);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void closeExpired() {
        close("sr.status='IN_PROGRESS' AND sr.attended_at < NOW() - INTERVAL '5 minutes'", null);
    }

    /**
     * La mesa dijo «Aún no» y su mesero no respondió en 1 minuto: el llamado vuelve a quedar libre
     * y se avisa a todos los meseros para que lo tome quien esté más cerca.
     */
    @Scheduled(fixedDelay = 20000, initialDelay = 20000)
    public void escalateToEveryone() {
        jdbc.sql("""
                UPDATE service_requests sr SET status='PENDING',handled_by=NULL,escalation_level=2,escalated_at=NOW(),updated_at=NOW()
                FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=sr.session_id AND sr.type='WAITER_CALL' AND sr.status='IN_PROGRESS'
                  AND sr.escalation_level=1 AND sr.escalated_at < NOW() - INTERVAL '1 minute'
                RETURNING sr.id,sr.session_id,sr.branch_id,COALESCE(t.label,ts.label) label,sr.requested_at,sr.reason
                """).query((rs, row) -> {
            var view = new ServiceRequestService.RequestView(rs.getObject("id", UUID.class),
                    rs.getObject("session_id", UUID.class), rs.getString("label"), "WAITER_CALL", "PENDING",
                    rs.getTimestamp("requested_at").toInstant(), null, null, rs.getString("reason"), 2, null);
            realtime.table(view.sessionId(), "SERVICE_REQUEST_CREATED", view);
            realtime.waitersEveryone(rs.getObject("branch_id", UUID.class), "WAITER_CALL_ESCALATED", view, view.sessionId());
            return view;
        }).list();
    }

    private void close(String condition, UUID sessionId) {
        var query = jdbc.sql("""
                UPDATE service_requests sr SET status='RESOLVED',handled_at=NOW(),updated_at=NOW()
                FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=sr.session_id AND sr.type='WAITER_CALL' AND %s
                RETURNING sr.id,sr.session_id,sr.branch_id,COALESCE(t.label,ts.label) label,sr.requested_at
                """.formatted(condition));
        if (sessionId != null) query = query.param("session", sessionId);
        query.query((rs, row) -> {
            var view = new ServiceRequestService.RequestView(rs.getObject("id", UUID.class),
                    rs.getObject("session_id", UUID.class), rs.getString("label"), "WAITER_CALL", "RESOLVED",
                    rs.getTimestamp("requested_at").toInstant(), null, null);
            var branch = rs.getObject("branch_id", UUID.class);
            realtime.table(view.sessionId(), "SERVICE_REQUEST_RESOLVED", view);
            realtime.waiters(branch, "SERVICE_REQUEST_RESOLVED", view);
            return view;
        }).list();
    }
}
