package com.restos.zones;

import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Zonas de la sucursal, sus mesas y los meseros que las atienden. */
@Service
public class ZoneService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;

    public ZoneService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
    }

    public List<ZoneView> list() {
        var branch = currentUser.require().branchId();
        return jdbc.sql("""
                        SELECT z.id,z.name,z.color,z.display_order,z.open,z.active,
                          (SELECT count(*) FROM dining_tables t WHERE t.zone_id=z.id AND t.active) tables,
                          (SELECT count(*) FROM dining_tables t JOIN table_sessions ts ON ts.table_id=t.id AND ts.status='OPEN'
                             WHERE t.zone_id=z.id) occupied,
                          COALESCE((SELECT array_agg(uz.user_id::text) FROM user_zones uz JOIN app_users u ON u.id=uz.user_id
                             WHERE uz.zone_id=z.id AND u.active),'{}') waiters
                        FROM dining_zones z WHERE z.branch_id=:branch ORDER BY z.active DESC,z.display_order,z.name
                        """).param("branch", branch)
                .query((rs, row) -> new ZoneView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("color"),
                        rs.getInt("display_order"), rs.getBoolean("open"), rs.getBoolean("active"), rs.getInt("tables"),
                        rs.getInt("occupied"), List.of((String[]) rs.getArray("waiters").getArray()).stream().map(UUID::fromString).toList()))
                .list();
    }

    /** Zonas visibles para el salón y las asignadas al usuario conectado. */
    public StaffZones staff() {
        var scope = currentUser.require();
        var mine = jdbc.sql("""
                SELECT uz.zone_id FROM user_zones uz JOIN dining_zones z ON z.id=uz.zone_id
                WHERE uz.user_id=:user AND z.branch_id=:branch AND z.active
                """).param("user", scope.userId()).param("branch", scope.branchId()).query(UUID.class).list();
        // Mesas asignadas a este mesero desde el estudio de planos.
        var tables = jdbc.sql("SELECT id FROM dining_tables WHERE waiter_id=:user AND branch_id=:branch AND active")
                .param("user", scope.userId()).param("branch", scope.branchId()).query(UUID.class).list();
        return new StaffZones(list().stream().filter(ZoneView::active).toList(), mine, tables);
    }

    @Transactional
    public ZoneView save(UUID id, String name, String color, int displayOrder, boolean open, boolean active) {
        var scope = currentUser.require();
        var duplicate = jdbc.sql("SELECT count(*) FROM dining_zones WHERE branch_id=:branch AND lower(name)=lower(:name) AND id<>:id")
                .param("branch", scope.branchId()).param("name", name.trim()).param("id", id == null ? new UUID(0, 0) : id)
                .query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe una zona con ese nombre");
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO dining_zones(id,organization_id,branch_id,name,color,display_order,open,active)
                    VALUES (:id,:org,:branch,:name,:color,:order,:open,:active)
                    """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                    .param("name", name.trim()).param("color", color.toUpperCase()).param("order", displayOrder)
                    .param("open", open).param("active", active).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE dining_zones SET name=:name,color=:color,display_order=:order,open=:open,active=:active
                    WHERE id=:id AND branch_id=:branch
                    """).param("name", name.trim()).param("color", color.toUpperCase()).param("order", displayOrder)
                    .param("open", open).param("active", active).param("id", id).param("branch", scope.branchId()).update();
            if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Zona no encontrada");
            // Una zona desactivada deja sus mesas sin zona para no esconderlas del salón.
            if (!active) jdbc.sql("UPDATE dining_tables SET zone_id=NULL WHERE zone_id=:id").param("id", id).update();
        }
        realtime.waiters(scope.branchId(), "ZONES_UPDATED", new ZoneRef(id));
        var savedId = id;
        return list().stream().filter(zone -> zone.id().equals(savedId)).findFirst().orElseThrow();
    }

    /** Define exactamente qué mesas pertenecen a la zona. */
    @Transactional
    public ZoneView assignTables(UUID zoneId, List<UUID> tableIds) {
        var branch = requireZone(zoneId);
        jdbc.sql("UPDATE dining_tables SET zone_id=NULL WHERE zone_id=:zone").param("zone", zoneId).update();
        if (!tableIds.isEmpty()) {
            var updated = jdbc.sql("UPDATE dining_tables SET zone_id=:zone WHERE branch_id=:branch AND id IN (:ids)")
                    .param("zone", zoneId).param("branch", branch).param("ids", tableIds).update();
            if (updated != new HashSet<>(tableIds).size()) throw new ApiException(HttpStatus.BAD_REQUEST, "Alguna mesa no pertenece a la sucursal");
        }
        realtime.waiters(branch, "ZONES_UPDATED", new ZoneRef(zoneId));
        return list().stream().filter(zone -> zone.id().equals(zoneId)).findFirst().orElseThrow();
    }

    /** Define qué meseros atienden la zona. */
    @Transactional
    public ZoneView assignWaiters(UUID zoneId, List<UUID> userIds) {
        var branch = requireZone(zoneId);
        if (!userIds.isEmpty()) {
            var valid = jdbc.sql("""
                    SELECT count(*) FROM app_users WHERE branch_id=:branch AND id IN (:ids)
                      AND role IN ('WAITER','BRANCH_MANAGER')
                    """).param("branch", branch).param("ids", userIds).query(Integer.class).single();
            if (valid != new HashSet<>(userIds).size()) throw new ApiException(HttpStatus.BAD_REQUEST, "Solo se pueden asignar meseros o encargados de la sucursal");
        }
        jdbc.sql("DELETE FROM user_zones WHERE zone_id=:zone").param("zone", zoneId).update();
        for (var user : new HashSet<>(userIds)) {
            jdbc.sql("INSERT INTO user_zones(user_id,zone_id) VALUES (:user,:zone)").param("user", user).param("zone", zoneId).update();
        }
        realtime.waiters(branch, "ZONES_UPDATED", new ZoneRef(zoneId));
        return list().stream().filter(zone -> zone.id().equals(zoneId)).findFirst().orElseThrow();
    }

    /** Verifica que la zona sea de la sucursal actual y la devuelve. */
    public UUID requireZone(UUID zoneId) {
        var branch = currentUser.require().branchId();
        var count = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch")
                .param("id", zoneId).param("branch", branch).query(Integer.class).single();
        if (count == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Zona inválida");
        return branch;
    }

    public record ZoneRef(UUID zoneId) {}
    public record ZoneView(UUID id, String name, String color, int displayOrder, boolean open, boolean active, int tables,
                           int occupied, List<UUID> waiterIds) {}
    public record StaffZones(List<ZoneView> zones, List<UUID> myZoneIds, List<UUID> myTableIds) {}
}
