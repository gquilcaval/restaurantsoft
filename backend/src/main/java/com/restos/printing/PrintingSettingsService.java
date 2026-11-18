package com.restos.printing;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configuración de la sucursal: ticketeras, qué estación imprime en cuál y las PCs que hacen de puente. */
@Service
public class PrintingSettingsService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final PrintingService printing;

    public PrintingSettingsService(JdbcClient jdbc, CurrentUser currentUser, PrintingService printing) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.printing = printing;
    }

    public Overview overview() {
        var scope = currentUser.require();
        var printers = jdbc.sql("""
                        SELECT id,name,host,port,paper_width,active,last_ok_at,last_error,last_error_at FROM printers
                        WHERE branch_id=:branch ORDER BY name
                        """).param("branch", scope.branchId())
                .query((rs, row) -> new PrinterView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("host"),
                        rs.getInt("port"), rs.getInt("paper_width"), rs.getBoolean("active"), instant(rs.getTimestamp("last_ok_at")),
                        rs.getString("last_error"), instant(rs.getTimestamp("last_error_at")))).list();
        var routes = new ArrayList<RouteView>();
        jdbc.sql("""
                        SELECT ks.id,ks.name,pr.printer_id FROM kitchen_stations ks
                        LEFT JOIN print_routes pr ON pr.station_id=ks.id AND pr.branch_id=:branch
                        WHERE ks.organization_id=:org AND ks.active ORDER BY ks.display_order,ks.name
                        """).param("branch", scope.branchId()).param("org", scope.organizationId())
                .query((rs, row) -> routes.add(new RouteView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("printer_id", UUID.class)))).list();
        var unassigned = jdbc.sql("SELECT printer_id FROM print_routes WHERE branch_id=:branch AND station_id IS NULL")
                .param("branch", scope.branchId()).query(UUID.class).optional().orElse(null);
        routes.add(new RouteView(null, routes.isEmpty() ? "Cocina" : "Productos sin estación", unassigned));
        var agents = jdbc.sql("""
                        SELECT id,name,last_seen_at,version,pairing_code,pairing_expires_at,token_hash IS NOT NULL paired FROM print_agents
                        WHERE branch_id=:branch AND revoked_at IS NULL
                          AND (token_hash IS NOT NULL OR pairing_expires_at>NOW()) ORDER BY created_at
                        """).param("branch", scope.branchId())
                .query((rs, row) -> agentView(rs.getObject("id", UUID.class), rs.getString("name"), instant(rs.getTimestamp("last_seen_at")),
                        rs.getString("version"), rs.getBoolean("paired") ? null : rs.getString("pairing_code"),
                        instant(rs.getTimestamp("pairing_expires_at")))).list();
        return new Overview(printers, routes, agents, agents.stream().anyMatch(AgentView::online));
    }

    @Transactional
    public PrinterView savePrinter(UUID id, String name, String host, int port, int paperWidth, boolean active) {
        var scope = currentUser.require();
        var cleanName = name == null ? "" : name.trim();
        var cleanHost = host == null ? "" : host.trim();
        if (cleanName.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Ponle un nombre a la impresora");
        if (!cleanHost.matches("[A-Za-z0-9.-]{1,120}")) throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe la IP de la impresora, por ejemplo 192.168.1.100");
        if (paperWidth != 58 && paperWidth != 80) throw new ApiException(HttpStatus.BAD_REQUEST, "El papel es de 80 o 58 mm");
        var duplicate = jdbc.sql("SELECT count(*) FROM printers WHERE branch_id=:branch AND lower(name)=lower(:name) AND id<>COALESCE(:id,CAST(:none AS uuid))")
                .param("branch", scope.branchId()).param("name", cleanName).param("id", id).param("none", PrintingService.NO_STATION)
                .query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya hay una impresora con ese nombre");
        var printerId = id == null ? UUID.randomUUID() : id;
        if (id == null) {
            jdbc.sql("""
                    INSERT INTO printers(id,organization_id,branch_id,name,host,port,paper_width,active)
                    VALUES (:id,:org,:branch,:name,:host,:port,:width,TRUE)
                    """).param("id", printerId).param("org", scope.organizationId()).param("branch", scope.branchId())
                    .param("name", cleanName).param("host", cleanHost).param("port", port).param("width", paperWidth).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE printers SET name=:name,host=:host,port=:port,paper_width=:width,active=:active WHERE id=:id AND branch_id=:branch
                    """).param("name", cleanName).param("host", cleanHost).param("port", port).param("width", paperWidth)
                    .param("active", active).param("id", id).param("branch", scope.branchId()).update();
            if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Impresora no encontrada");
        }
        return overview().printers().stream().filter(printer -> printer.id().equals(printerId)).findFirst().orElseThrow();
    }

    /** Quitar una impresora devuelve sus estaciones a la pantalla de cocina. */
    @Transactional
    public void deletePrinter(UUID id) {
        var branch = currentUser.require().branchId();
        jdbc.sql("UPDATE print_jobs SET status='FAILED',error='Se quitó la impresora' WHERE printer_id=:id AND status IN ('PENDING','SENT')")
                .param("id", id).update();
        var deleted = jdbc.sql("DELETE FROM printers WHERE id=:id AND branch_id=:branch").param("id", id).param("branch", branch).update();
        if (deleted == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Impresora no encontrada");
    }

    /** Imprime un ticket de prueba para comprobar la conexión con la impresora. */
    @Transactional
    public void test(UUID printerId) {
        var scope = currentUser.require();
        var printer = jdbc.sql("SELECT name,host,port,paper_width FROM printers WHERE id=:id AND branch_id=:branch")
                .param("id", printerId).param("branch", scope.branchId())
                .query((rs, row) -> new Object[]{rs.getString("name"), rs.getString("host"), rs.getInt("port"), rs.getInt("paper_width")})
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Impresora no encontrada"));
        printing.insertJob(scope.organizationId(), scope.branchId(), printerId, null, null, null, "PRUEBA",
                "Prueba · " + printer[0], printing.testContent((String) printer[0], (String) printer[1], (Integer) printer[2], (Integer) printer[3]));
    }

    /** printerId null = esa estación vuelve a la pantalla de cocina. stationId null = productos sin estación. */
    @Transactional
    public void setRoute(UUID stationId, UUID printerId) {
        var scope = currentUser.require();
        if (stationId != null) {
            var valid = jdbc.sql("SELECT count(*) FROM kitchen_stations WHERE id=:id AND organization_id=:org")
                    .param("id", stationId).param("org", scope.organizationId()).query(Integer.class).single();
            if (valid == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Estación no encontrada");
        }
        if (printerId != null) {
            var valid = jdbc.sql("SELECT count(*) FROM printers WHERE id=:id AND branch_id=:branch")
                    .param("id", printerId).param("branch", scope.branchId()).query(Integer.class).single();
            if (valid == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Impresora no encontrada");
        }
        jdbc.sql("DELETE FROM print_routes WHERE branch_id=:branch AND COALESCE(station_id,CAST(:none AS uuid))=COALESCE(CAST(:station AS uuid),CAST(:none AS uuid))")
                .param("branch", scope.branchId()).param("station", stationId).param("none", PrintingService.NO_STATION).update();
        if (printerId != null) {
            jdbc.sql("INSERT INTO print_routes(id,branch_id,station_id,printer_id) VALUES (:id,:branch,:station,:printer)")
                    .param("id", UUID.randomUUID()).param("branch", scope.branchId()).param("station", stationId)
                    .param("printer", printerId).update();
        }
    }

    /** Prepara la vinculación de una PC: un código de 6 dígitos válido por 15 minutos. */
    @Transactional
    public AgentView createAgent(String name) {
        var scope = currentUser.require();
        var cleanName = name == null || name.isBlank() ? "PC del local" : name.trim();
        String code;
        do {
            code = PrintingService.pairingCode();
        } while (jdbc.sql("SELECT count(*) FROM print_agents WHERE pairing_code=:code AND pairing_expires_at>NOW()")
                .param("code", code).query(Integer.class).single() > 0);
        var id = UUID.randomUUID();
        var expires = Instant.now().plus(15, ChronoUnit.MINUTES);
        jdbc.sql("""
                INSERT INTO print_agents(id,organization_id,branch_id,name,pairing_code,pairing_expires_at,created_by)
                VALUES (:id,:org,:branch,:name,:code,:expires,:by)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("name", cleanName.length() > 60 ? cleanName.substring(0, 60) : cleanName).param("code", code)
                .param("expires", java.sql.Timestamp.from(expires)).param("by", scope.userId()).update();
        return agentView(id, cleanName, null, null, code, expires);
    }

    @Transactional
    public void revokeAgent(UUID id) {
        var updated = jdbc.sql("UPDATE print_agents SET revoked_at=NOW(),pairing_code=NULL WHERE id=:id AND branch_id=:branch AND revoked_at IS NULL")
                .param("id", id).param("branch", currentUser.require().branchId()).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "PC no encontrada");
    }

    private static AgentView agentView(UUID id, String name, Instant lastSeen, String version, String code, Instant expires) {
        var online = lastSeen != null && lastSeen.isAfter(Instant.now().minusSeconds(30));
        return new AgentView(id, name, lastSeen, version, code, code == null ? null : expires, online);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record PrinterView(UUID id, String name, String host, int port, int paperWidth, boolean active, Instant lastOkAt,
                              String lastError, Instant lastErrorAt) {}
    /** stationId null: productos sin estación. printerId null: la estación usa la pantalla de cocina. */
    public record RouteView(UUID stationId, String stationName, UUID printerId) {}
    /** pairingCode solo mientras la PC no se ha vinculado. online: se reportó en los últimos 30 s. */
    public record AgentView(UUID id, String name, Instant lastSeenAt, String version, String pairingCode, Instant pairingExpiresAt,
                            boolean online) {}
    public record Overview(List<PrinterView> printers, List<RouteView> stations, List<AgentView> agents, boolean agentOnline) {}
}
