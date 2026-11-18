package com.restos.tables;

import com.restos.realtime.RealtimePublisher;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Libera atenciones QR abandonadas que nunca enviaron productos a cocina. */
@Component
public class TableSessionCleanupJob {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;
    private final int inactivityMinutes;
    private final int lastGuestGraceMinutes;
    private final int staleReviewHours;

    public TableSessionCleanupJob(JdbcClient jdbc, RealtimePublisher realtime,
            @Value("${restos.sessions.inactivity-minutes:120}") int inactivityMinutes,
            @Value("${restos.sessions.last-guest-grace-minutes:5}") int lastGuestGraceMinutes,
            @Value("${restos.sessions.stale-review-hours:12}") int staleReviewHours) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.inactivityMinutes = inactivityMinutes;
        this.lastGuestGraceMinutes = lastGuestGraceMinutes;
        this.staleReviewHours = staleReviewHours;
    }

    @Scheduled(fixedDelayString = "${restos.sessions.cleanup-delay-ms:60000}")
    @Transactional
    public void closeAbandonedSessions() {
        markSubmittedSessionsForReview();
        List<ExpiredSession> expired = jdbc.sql("""
                        SELECT ts.id,ts.branch_id,
                          NOT EXISTS (SELECT 1 FROM guests g WHERE g.session_id=ts.id AND g.active) empty
                        FROM table_sessions ts
                        WHERE ts.status='OPEN' AND ts.opened_source='QR'
                          AND NOT EXISTS (
                            SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status NOT IN ('DRAFT','CANCELLED')
                          )
                          AND (
                            ts.updated_at < NOW() - (:minutes * INTERVAL '1 minute')
                            OR (
                              ts.updated_at < NOW() - (:grace * INTERVAL '1 minute')
                              AND NOT EXISTS (SELECT 1 FROM guests g WHERE g.session_id=ts.id AND g.active)
                            )
                          )
                FOR UPDATE OF ts SKIP LOCKED
                        """).param("minutes", inactivityMinutes).param("grace", lastGuestGraceMinutes)
                .query((rs, row) -> new ExpiredSession(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getBoolean("empty"))).list();
        for (var session : expired) {
            jdbc.sql("UPDATE orders SET status='CANCELLED',updated_at=NOW() WHERE session_id=:id AND status='DRAFT'")
                    .param("id", session.id()).update();
            jdbc.sql("""
                    UPDATE service_requests SET status='RESOLVED',handled_at=NOW(),updated_at=NOW()
                    WHERE session_id=:id AND status IN ('PENDING','IN_PROGRESS')
                    """).param("id", session.id()).update();
            jdbc.sql("""
                    UPDATE table_sessions SET status='CANCELLED',closed_at=NOW(),
                      close_reason=:reason,updated_at=NOW() WHERE id=:id
                    """).param("reason", session.empty() ? "Cierre automático sin comensales"
                            : "Cierre automático por inactividad").param("id", session.id()).update();
            var event = new TableService.SessionClosed(session.id(), "CANCELLED",
                    session.empty() ? "Cierre automático sin comensales" : "Cierre automático por inactividad");
            realtime.table(session.id(), "TABLE_SESSION_CANCELLED", event);
            realtime.waiters(session.branchId(), "TABLE_SESSION_CANCELLED", event);
        }
    }

    /** Bloquea atenciones antiguas con consumo para que nunca se mezclen con clientes nuevos. */
    private void markSubmittedSessionsForReview() {
        var stale = jdbc.sql("""
                SELECT ts.id,ts.branch_id FROM table_sessions ts
                WHERE ts.status='OPEN' AND NOT ts.requires_review AND ts.table_id IS NOT NULL
                  AND COALESCE(ts.reviewed_at,ts.opened_at) < NOW() - (:hours * INTERVAL '1 hour')
                  AND EXISTS (SELECT 1 FROM orders o WHERE o.session_id=ts.id AND o.status NOT IN ('DRAFT','CANCELLED'))
                FOR UPDATE OF ts SKIP LOCKED
                """).param("hours", staleReviewHours)
                .query((rs, row) -> new ExpiredSession(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), false)).list();
        for (var session : stale) {
            jdbc.sql("UPDATE table_sessions SET requires_review=TRUE WHERE id=:id")
                    .param("id", session.id()).update();
            var event = new ReviewRequired(session.id(), "Atención antigua con consumo pendiente");
            realtime.table(session.id(), "TABLE_SESSION_REVIEW_REQUIRED", event);
            realtime.waiters(session.branchId(), "TABLE_SESSION_REVIEW_REQUIRED", event);
        }
    }

    private record ExpiredSession(UUID id, UUID branchId, boolean empty) {}
    private record ReviewRequired(UUID sessionId, String reason) {}
}
