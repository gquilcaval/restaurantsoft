package com.restos.security;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.restos.shared.ApiException;

/**
 * Permisos opcionales por persona. Encargados y administradores siempre pueden; el mesero solo si se le
 * activó en Configuración → Equipo.
 */
@Component
public class StaffPermissions {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public StaffPermissions(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    public View mine() {
        var scope = currentUser.require();
        if (scope.role() != UserRole.WAITER) return new View(true, true);
        return jdbc.sql("SELECT can_counter, can_reservations FROM app_users WHERE id=:id")
                .param("id", scope.userId())
                .query((rs, row) -> new View(rs.getBoolean("can_counter"), rs.getBoolean("can_reservations")))
                .optional().orElse(new View(false, false));
    }

    public void requireCounter() {
        if (!mine().counter()) throw new ApiException(HttpStatus.FORBIDDEN, "No tienes permiso para pedidos de mostrador. Pídeselo al encargado.");
    }

    public void requireReservations() {
        if (!mine().reservations()) throw new ApiException(HttpStatus.FORBIDDEN, "No tienes permiso para gestionar reservas. Pídeselo al encargado.");
    }

    public record View(boolean counter, boolean reservations) {}
}
