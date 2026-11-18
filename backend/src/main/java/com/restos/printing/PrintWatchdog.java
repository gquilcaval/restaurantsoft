package com.restos.printing;

import com.restos.realtime.RealtimePublisher;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Que ninguna comanda se pierda en silencio: reintenta lo que la PC no confirmó y avisa a todo el equipo
 * si una comanda no se imprimió (impresora apagada, sin papel) o si no hay ninguna PC de impresión conectada.
 */
@Component
public class PrintWatchdog {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;

    public PrintWatchdog(JdbcClient jdbc, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 20000)
    public void check() {
        // Enviado a la PC pero sin respuesta en 45 s: se reintenta (hasta 3 veces) o se da por fallido.
        jdbc.sql("""
                UPDATE print_jobs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END,
                  error=COALESCE(error,'La impresora no respondió')
                WHERE status='SENT' AND sent_at < NOW() - INTERVAL '45 seconds'
                """).update();
        jdbc.sql("""
                UPDATE print_jobs j SET alerted_at=NOW()
                FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=j.session_id AND j.kind<>'PRUEBA' AND j.alerted_at IS NULL AND (
                  j.status='FAILED'
                  OR (j.status='PENDING' AND j.created_at < NOW() - INTERVAL '25 seconds' AND NOT EXISTS (
                        SELECT 1 FROM print_agents a WHERE a.branch_id=j.branch_id AND a.revoked_at IS NULL
                          AND a.last_seen_at > NOW() - INTERVAL '40 seconds')))
                RETURNING j.id,j.branch_id,j.session_id,j.station_name,j.kind,j.status,j.error,COALESCE(t.label,ts.label) label
                """).query((rs, row) -> {
            var failed = rs.getString("status").equals("FAILED");
            var what = rs.getString("kind").equals("ANULACION") ? "La anulación" : "La comanda";
            var station = rs.getString("station_name") == null ? "" : " (" + rs.getString("station_name") + ")";
            var message = failed
                    ? what + " de " + rs.getString("label") + station + " no se imprimió: " + rs.getString("error")
                    : "No hay ninguna PC de impresión conectada: " + what.toLowerCase() + " de " + rs.getString("label") + station + " está en espera";
            var alert = new PrintAlert(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class), rs.getString("label"),
                    rs.getString("station_name"), message);
            realtime.waitersEveryone(rs.getObject("branch_id", UUID.class), "PRINT_FAILED", alert, alert.sessionId());
            return alert;
        }).list();
    }

    public record PrintAlert(UUID jobId, UUID sessionId, String tableLabel, String stationName, String message) {}
}
