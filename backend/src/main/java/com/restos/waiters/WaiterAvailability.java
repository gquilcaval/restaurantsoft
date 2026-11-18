package com.restos.waiters;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quién está trabajando ahora según la asistencia. Solo se usa si el restaurante tiene el módulo activo
 * y alguien de la sucursal marcó entrada hoy; si no, se considera disponible a todo el equipo
 * (así un local que no marca asistencia no se queda sin avisos).
 */
@Component
public class WaiterAvailability {
    private final JdbcClient jdbc;

    public WaiterAvailability(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Meseros y encargados de la sucursal en turno (no en refrigerio); vacío = no se puede saber. */
    public Optional<Set<UUID>> onShift(UUID branchId) {
        var enabled = jdbc.sql("""
                        SELECT o.attendance_enabled FROM branches b JOIN organizations o ON o.id=b.organization_id WHERE b.id=:branch
                        """).param("branch", branchId).query(Boolean.class).optional().orElse(false);
        if (!enabled) return Optional.empty();
        var working = new HashSet<>(jdbc.sql("""
                        SELECT u.id FROM app_users u
                        JOIN LATERAL (SELECT kind,marked_at FROM attendance_marks m
                                      WHERE m.user_id=u.id AND m.voided_at IS NULL ORDER BY m.marked_at DESC LIMIT 1) last ON TRUE
                        WHERE u.branch_id=:branch AND u.active AND u.role IN ('WAITER','BRANCH_MANAGER')
                          AND last.kind IN ('IN','BREAK_END') AND last.marked_at > NOW() - INTERVAL '16 hours'
                        """).param("branch", branchId).query(UUID.class).list());
        // Nadie marcó: el local no está usando la asistencia hoy, no se filtra por turno.
        return working.isEmpty() ? Optional.empty() : Optional.of(working);
    }

    public boolean isAvailable(Optional<Set<UUID>> onShift, UUID userId) {
        return userId != null && onShift.map(set -> set.contains(userId)).orElse(true);
    }
}
