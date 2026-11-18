package com.restos.waiters;

import com.restos.realtime.RealtimeEvent;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A quién debe sonarle un aviso de una mesa: a su mesero a cargo si está en turno; si no está
 * (o la mesa no tiene mesero), a los meseros en turno de su zona; y si tampoco hay, a todo el equipo.
 * Todos reciben el evento igual para tener la pantalla al día; esto solo decide quién recibe sonido y aviso.
 */
@Component
public class AlertAudience {
    private final JdbcClient jdbc;
    private final WaiterAvailability availability;

    public AlertAudience(JdbcClient jdbc, WaiterAvailability availability) {
        this.jdbc = jdbc;
        this.availability = availability;
    }

    /** Destinatarios del aviso según la mesa del evento; null si el evento no es de una mesa. */
    public RealtimeEvent.Audience forPayload(Object payload) {
        var sessionId = sessionIdOf(payload);
        return sessionId == null ? null : forSession(sessionId);
    }

    public RealtimeEvent.Audience forSession(UUID sessionId) {
        var session = jdbc.sql("""
                        SELECT ts.branch_id,ts.waiter_id,t.zone_id FROM table_sessions ts
                        LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE ts.id=:id
                        """).param("id", sessionId)
                .query((rs, row) -> new Object[]{rs.getObject("branch_id", UUID.class), rs.getObject("waiter_id", UUID.class),
                        rs.getObject("zone_id", UUID.class)}).optional().orElse(null);
        if (session == null) return null;
        var branchId = (UUID) session[0]; var waiterId = (UUID) session[1]; var zoneId = (UUID) session[2];
        var onShift = availability.onShift(branchId);
        if (waiterId != null && availability.isAvailable(onShift, waiterId)) return new RealtimeEvent.Audience(waiterId, List.of(waiterId));
        // Mesero ausente o mesa sin mesero: los meseros en turno de la zona.
        if (zoneId != null) {
            var zone = jdbc.sql("""
                            SELECT uz.user_id FROM user_zones uz JOIN app_users u ON u.id=uz.user_id
                            WHERE uz.zone_id=:zone AND u.active AND u.role IN ('WAITER','BRANCH_MANAGER')
                            """).param("zone", zoneId).query(UUID.class).list().stream()
                    .filter(user -> availability.isAvailable(onShift, user)).toList();
            if (!zone.isEmpty()) return new RealtimeEvent.Audience(waiterId, zone);
        }
        return RealtimeEvent.Audience.everyone(waiterId);
    }

    /** Lee el campo sessionId de un record o un mapa (todos los eventos de mesa lo traen). */
    static UUID sessionIdOf(Object payload) {
        if (payload instanceof Map<?, ?> map && map.get("sessionId") instanceof UUID id) return id;
        if (payload == null || !payload.getClass().isRecord()) return null;
        for (RecordComponent component : payload.getClass().getRecordComponents()) {
            if (!component.getName().equals("sessionId") || component.getType() != UUID.class) continue;
            try {
                return (UUID) component.getAccessor().invoke(payload);
            } catch (ReflectiveOperationException error) {
                return null;
            }
        }
        return null;
    }
}
