package com.restos.floor;

import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estudio de planos: ajustes por zona (foto de fondo, ancho real, aforo), configuraciones guardadas
 * del mismo salón («Día normal», «Evento») y asignación de mesas a cada mesero.
 */
@Service
public class FloorStudioService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;

    public FloorStudioService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
    }

    @Transactional
    public void settings(UUID zoneId, SettingsData data) {
        var scope = currentUser.require();
        var url = data.backgroundUrl() == null || data.backgroundUrl().isBlank() ? null : data.backgroundUrl().trim();
        if (url != null && !url.startsWith("/") && !url.startsWith("https://")) throw new ApiException(HttpStatus.BAD_REQUEST, "Foto inválida");
        var updated = jdbc.sql("""
                UPDATE dining_zones SET plan_background_url=:url,plan_background_opacity=:opacity,plan_width_meters=:width,
                  max_capacity=:capacity,updated_at=NOW() WHERE id=:id AND branch_id=:branch AND active
                """).param("url", url).param("opacity", Math.max(0, Math.min(100, data.backgroundOpacity())))
                .param("width", Math.max(3, Math.min(200, data.widthMeters())))
                .param("capacity", data.maxCapacity() == null || data.maxCapacity() <= 0 ? null : data.maxCapacity())
                .param("id", zoneId).param("branch", scope.branchId()).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Plano no encontrado");
        realtime.waiters(scope.branchId(), "FLOOR_UPDATED", new FloorService.FloorChanged(zoneId));
    }

    public List<Layout> layouts() {
        return jdbc.sql("""
                        SELECT l.id,l.zone_id,l.name,l.updated_at,jsonb_array_length(l.data->'tables') tables,
                          (SELECT COALESCE(sum(t.capacity),0) FROM dining_tables t
                           WHERE t.id IN (SELECT (j->>'id')::uuid FROM jsonb_array_elements(l.data->'tables') j WHERE j->>'x' IS NOT NULL)) seats
                        FROM floor_layouts l WHERE l.branch_id=:branch ORDER BY l.created_at
                        """).param("branch", currentUser.require().branchId())
                .query((rs, row) -> new Layout(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class),
                        rs.getString("name"), rs.getTimestamp("updated_at").toInstant(), rs.getInt("tables"), rs.getInt("seats"))).list();
    }

    /** Guarda el plano actual de la zona (ya guardado) como una configuración con nombre y la deja activa. */
    @Transactional
    public UUID createLayout(UUID zoneId, String name) {
        var scope = currentUser.require();
        requireZone(zoneId, scope.branchId());
        var count = jdbc.sql("SELECT count(*) FROM floor_layouts WHERE zone_id=:zone").param("zone", zoneId).query(Integer.class).single();
        if (count >= 12) throw new ApiException(HttpStatus.BAD_REQUEST, "Máximo 12 configuraciones por plano");
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO floor_layouts(id,organization_id,branch_id,zone_id,name,data) VALUES (:id,:org,:branch,:zone,:name," + SNAPSHOT + ")")
                .param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("zone", zoneId).param("name", name.trim()).update();
        jdbc.sql("UPDATE dining_zones SET active_layout_id=:id WHERE id=:zone").param("id", id).param("zone", zoneId).update();
        return id;
    }

    /** Reemplaza una configuración con el plano actual de su zona. */
    @Transactional
    public void updateLayout(UUID id) {
        var layout = find(id);
        jdbc.sql("UPDATE floor_layouts SET data=" + SNAPSHOT + ",updated_at=NOW() WHERE id=:id")
                .param("zone", layout.zoneId()).param("id", id).update();
        jdbc.sql("UPDATE dining_zones SET active_layout_id=:id WHERE id=:zone").param("id", id).param("zone", layout.zoneId()).update();
    }

    /** Aplica una configuración: reubica las mesas que siguen en la zona y reemplaza los elementos del plano. */
    @Transactional
    public void applyLayout(UUID id) {
        var scope = currentUser.require();
        var layout = find(id);
        jdbc.sql("""
                UPDATE dining_tables t SET plan_x=NULL,plan_y=NULL WHERE t.zone_id=:zone AND t.active
                  AND t.id NOT IN (SELECT (j->>'id')::uuid FROM floor_layouts l, jsonb_array_elements(l.data->'tables') j WHERE l.id=:id)
                """).param("zone", layout.zoneId()).param("id", id).update();
        jdbc.sql("""
                UPDATE dining_tables t SET plan_x=(j->>'x')::int,plan_y=(j->>'y')::int,plan_shape=COALESCE(j->>'shape','SQUARE'),
                  plan_rotation=COALESCE((j->>'rotation')::int,0)
                FROM floor_layouts l, jsonb_array_elements(l.data->'tables') j
                WHERE l.id=:id AND t.id=(j->>'id')::uuid AND t.zone_id=:zone AND t.active
                """).param("zone", layout.zoneId()).param("id", id).update();
        jdbc.sql("DELETE FROM floor_elements WHERE zone_id=:zone").param("zone", layout.zoneId()).update();
        jdbc.sql("""
                INSERT INTO floor_elements(id,organization_id,branch_id,zone_id,kind,label,x,y,width,height,rotation)
                SELECT gen_random_uuid(),l.organization_id,l.branch_id,l.zone_id,j->>'kind',j->>'label',(j->>'x')::int,(j->>'y')::int,
                  (j->>'width')::int,(j->>'height')::int,COALESCE((j->>'rotation')::int,0)
                FROM floor_layouts l, jsonb_array_elements(l.data->'elements') j WHERE l.id=:id
                """).param("id", id).update();
        jdbc.sql("UPDATE dining_zones SET active_layout_id=:id WHERE id=:zone").param("id", id).param("zone", layout.zoneId()).update();
        realtime.waiters(scope.branchId(), "FLOOR_UPDATED", new FloorService.FloorChanged(layout.zoneId()));
    }

    @Transactional
    public void deleteLayout(UUID id) {
        var layout = find(id);
        jdbc.sql("UPDATE dining_zones SET active_layout_id=NULL WHERE id=:zone AND active_layout_id=:id")
                .param("zone", layout.zoneId()).param("id", id).update();
        jdbc.sql("DELETE FROM floor_layouts WHERE id=:id").param("id", id).update();
    }

    /** Asigna (o quita) un mesero a varias mesas a la vez. */
    @Transactional
    public void assignWaiter(List<UUID> tableIds, UUID waiterId) {
        var scope = currentUser.require();
        if (waiterId != null) {
            var valid = jdbc.sql("SELECT count(*) FROM app_users WHERE id=:id AND branch_id=:branch AND active AND role IN ('WAITER','BRANCH_MANAGER')")
                    .param("id", waiterId).param("branch", scope.branchId()).query(Integer.class).single();
            if (valid == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Mesero inválido");
        }
        if (tableIds.isEmpty()) return;
        jdbc.sql("UPDATE dining_tables SET waiter_id=:waiter WHERE branch_id=:branch AND id IN (:ids)")
                .param("waiter", waiterId).param("branch", scope.branchId()).param("ids", tableIds).update();
        realtime.waiters(scope.branchId(), "FLOOR_UPDATED", new FloorService.FloorChanged(null));
    }

    public List<StaffMember> staff() {
        return jdbc.sql("""
                        SELECT id,full_name,role FROM app_users WHERE branch_id=:branch AND active AND role IN ('WAITER','BRANCH_MANAGER')
                        ORDER BY role DESC,full_name
                        """).param("branch", currentUser.require().branchId())
                .query((rs, row) -> new StaffMember(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("role"))).list();
    }

    private static final String SNAPSHOT = """
            jsonb_build_object(
              'tables',(SELECT COALESCE(jsonb_agg(jsonb_build_object('id',t.id,'x',t.plan_x,'y',t.plan_y,'shape',t.plan_shape,'rotation',t.plan_rotation)),'[]'::jsonb)
                        FROM dining_tables t WHERE t.zone_id=:zone AND t.active),
              'elements',(SELECT COALESCE(jsonb_agg(jsonb_build_object('kind',e.kind,'label',e.label,'x',e.x,'y',e.y,'width',e.width,'height',e.height,'rotation',e.rotation)),'[]'::jsonb)
                          FROM floor_elements e WHERE e.zone_id=:zone))""";

    private void requireZone(UUID zoneId, UUID branch) {
        var zone = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch AND active")
                .param("id", zoneId).param("branch", branch).query(Integer.class).single();
        if (zone == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Plano inválido");
    }

    private LayoutRef find(UUID id) {
        return jdbc.sql("SELECT id,zone_id FROM floor_layouts WHERE id=:id AND branch_id=:branch")
                .param("id", id).param("branch", currentUser.require().branchId())
                .query((rs, row) -> new LayoutRef(rs.getObject("id", UUID.class), rs.getObject("zone_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Configuración no encontrada"));
    }

    public record SettingsData(String backgroundUrl, int backgroundOpacity, double widthMeters, Integer maxCapacity) {}
    public record Layout(UUID id, UUID zoneId, String name, Instant updatedAt, int tables, int seats) {}
    public record StaffMember(UUID id, String name, String role) {}
    private record LayoutRef(UUID id, UUID zoneId) {}
}
