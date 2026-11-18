package com.restos.menu;

import com.restos.realtime.RealtimePublisher;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Disponibilidad de la carta por sucursal. Además del agotado manual, marca solo los platos cuya receta ya no
 * alcanza para una porción y los vuelve a habilitar cuando se repone el stock. El agotado manual nunca se
 * levanta automáticamente.
 */
@Service
public class AvailabilityService {
    private final JdbcClient jdbc;
    private final RealtimePublisher realtime;

    public AvailabilityService(JdbcClient jdbc, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    /** Recalcula los agotados automáticos de la sucursal y avisa a cocina, salón y comensales. */
    public void syncAutomatic(UUID branchId) {
        var blocked = jdbc.sql("""
                        INSERT INTO menu_item_unavailability(branch_id,menu_item_id,automatic)
                        SELECT DISTINCT :branch,r.menu_item_id,TRUE FROM recipe_lines r
                        JOIN menu_items mi ON mi.id=r.menu_item_id AND mi.active
                        JOIN branches b ON b.id=:branch AND b.organization_id=mi.organization_id
                        LEFT JOIN ingredient_stock s ON s.ingredient_id=r.ingredient_id AND s.branch_id=:branch
                        WHERE COALESCE(s.quantity,0) < r.quantity
                        ON CONFLICT DO NOTHING
                        RETURNING menu_item_id
                        """).param("branch", branchId).query(UUID.class).list();
        var restored = jdbc.sql("""
                        DELETE FROM menu_item_unavailability u
                        WHERE u.branch_id=:branch AND u.automatic AND NOT EXISTS (
                          SELECT 1 FROM recipe_lines r
                          LEFT JOIN ingredient_stock s ON s.ingredient_id=r.ingredient_id AND s.branch_id=:branch
                          WHERE r.menu_item_id=u.menu_item_id AND COALESCE(s.quantity,0) < r.quantity)
                        RETURNING menu_item_id
                        """).param("branch", branchId).query(UUID.class).list();
        publish(branchId, blocked, false);
        publish(branchId, restored, true);
    }

    /** Cambio manual desde cocina o administración. */
    public void setManual(UUID branchId, UUID menuItemId, boolean available, UUID userId) {
        if (available) {
            jdbc.sql("DELETE FROM menu_item_unavailability WHERE branch_id=:branch AND menu_item_id=:item")
                    .param("branch", branchId).param("item", menuItemId).update();
        } else {
            jdbc.sql("""
                    INSERT INTO menu_item_unavailability(branch_id,menu_item_id,marked_by,automatic)
                    VALUES (:branch,:item,:user,FALSE)
                    ON CONFLICT (branch_id,menu_item_id) DO UPDATE SET automatic=FALSE,marked_by=:user,marked_at=NOW()
                    """).param("branch", branchId).param("item", menuItemId).param("user", userId).update();
        }
        publish(branchId, List.of(menuItemId), available);
    }

    private void publish(UUID branchId, List<UUID> items, boolean available) {
        if (items.isEmpty()) return;
        var sessions = jdbc.sql("SELECT id FROM table_sessions WHERE branch_id=:branch AND status='OPEN'")
                .param("branch", branchId).query(UUID.class).list();
        for (var itemId : items) {
            var name = jdbc.sql("SELECT name FROM menu_items WHERE id=:id").param("id", itemId).query(String.class).single();
            var event = new AvailabilityChanged(itemId, name, available);
            realtime.kitchen(branchId, "MENU_AVAILABILITY_CHANGED", event);
            realtime.waiters(branchId, "MENU_AVAILABILITY_CHANGED", event);
            // Los comensales con la carta abierta deben ver el cambio sin recargar.
            sessions.forEach(session -> realtime.table(session, "MENU_AVAILABILITY_CHANGED", event));
        }
    }

    public record AvailabilityChanged(UUID menuItemId, String name, boolean available) {}
}
