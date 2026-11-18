package com.restos.printing;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comandas impresas. Una estación (Cocina, Barra…) puede trabajar con la pantalla de cocina o con una
 * ticketera: si tiene una ruta de impresión en la sucursal, al enviarse un pedido se imprime su comanda,
 * al anularse un plato se imprime la anulación, y el mesero puede marcar «servido» sin esperar «listo».
 * Las ticketeras están en la red del local; un programa puente en una PC del local recoge los trabajos.
 */
@Service
public class PrintingService {
    static final String NO_STATION = "00000000-0000-0000-0000-000000000000";
    /** Clave de «productos sin estación» en {@link #printingStations}. */
    public static final String NO_STATION_KEY = NO_STATION;
    private static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", ES);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a", ES);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final com.restos.realtime.RealtimePublisher realtime;
    private final ZoneId zone;

    public PrintingService(JdbcClient jdbc, CurrentUser currentUser, com.restos.realtime.RealtimePublisher realtime,
                           @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.zone = ZoneId.of(zone);
    }

    // ——— Comandas ———

    /** Imprime la comanda de un pedido recién enviado: una por cada estación que imprime. */
    public void comandas(UUID orderId, boolean reprint) {
        var order = orderContext(orderId);
        if (order == null) return;
        var lines = jdbc.sql("""
                        SELECT oi.quantity,oi.item_name,oi.notes,g.display_name guest,ks.name station_name,ks.display_order,
                               pr.printer_id,p.paper_width,
                               (SELECT string_agg(m.option_name,' · ' ORDER BY m.display_order)
                                  FROM order_item_modifiers m WHERE m.order_item_id=oi.id) modifiers
                        FROM order_items oi
                        LEFT JOIN guests g ON g.id=oi.guest_id
                        LEFT JOIN menu_items mi ON mi.id=oi.menu_item_id
                        LEFT JOIN menu_categories mc ON mc.id=mi.category_id
                        LEFT JOIN kitchen_stations ks ON ks.id=mc.station_id
                        JOIN print_routes pr ON pr.branch_id=:branch
                          AND COALESCE(pr.station_id,CAST(:none AS uuid))=COALESCE(mc.station_id,CAST(:none AS uuid))
                        JOIN printers p ON p.id=pr.printer_id AND p.active
                        WHERE oi.order_id=:order AND oi.status NOT IN ('CANCELLED','DELIVERED')
                        ORDER BY ks.display_order NULLS LAST,oi.created_at
                        """).param("branch", order.branchId()).param("none", NO_STATION).param("order", orderId)
                .query((rs, row) -> new Line(rs.getInt("quantity"), rs.getString("item_name"), rs.getString("modifiers"),
                        rs.getString("notes"), rs.getString("guest"), station(rs.getString("station_name")),
                        rs.getObject("printer_id", UUID.class), rs.getInt("paper_width"))).list();
        // Una comanda por estación (cada estación puede tener su propia impresora).
        var byStation = new LinkedHashMap<String, List<Line>>();
        lines.forEach(line -> byStation.computeIfAbsent(line.station() + "|" + line.printerId(), key -> new ArrayList<>()).add(line));
        for (var group : byStation.values()) {
            var first = group.get(0);
            var ticket = new Ticket(first.paperWidth()).big(order.label().toUpperCase(ES));
            if (reprint) ticket.center("** REIMPRESIÓN **");
            ticket.center(first.station().toUpperCase(ES) + " · Ronda " + order.round());
            ticket.center(order.submittedAt().atZone(zone).format(TIME) + (order.waiter() == null ? "" : " · Mesero: " + order.waiter()));
            if (order.customer() != null) ticket.center("Cliente: " + order.customer());
            ticket.rule();
            var count = 0;
            for (var line : group) {
                ticket.boldHanging(line.quantity() + " x " + line.name(), "    ");
                if (line.modifiers() != null) ticket.text(line.modifiers(), "    ");
                if (line.notes() != null && !line.notes().isBlank()) ticket.bold(">> " + line.notes().trim(), "    ");
                if (line.guest() != null) ticket.text("(" + line.guest() + ")", "    ");
                count += line.quantity();
            }
            ticket.rule().center(count + (count == 1 ? " producto" : " productos"));
            insertJob(order.organizationId(), order.branchId(), first.printerId(), order.sessionId(), orderId, first.station(),
                    "COMANDA", order.label() + " · " + first.station() + " · Ronda " + order.round() + (reprint ? " (reimpresión)" : ""),
                    ticket.content());
        }
    }

    /** Un plato anulado en una estación que imprime: sale un ticket de ANULACIÓN para que no lo preparen. */
    public void voided(UUID itemId, int quantity, String reason, String byName) {
        var item = jdbc.sql("""
                        SELECT o.id order_id,oi.item_name,ks.name station_name,pr.printer_id,p.paper_width,
                               (SELECT string_agg(m.option_name,' · ' ORDER BY m.display_order)
                                  FROM order_item_modifiers m WHERE m.order_item_id=oi.id) modifiers
                        FROM order_items oi JOIN orders o ON o.id=oi.order_id
                        LEFT JOIN menu_items mi ON mi.id=oi.menu_item_id
                        LEFT JOIN menu_categories mc ON mc.id=mi.category_id
                        LEFT JOIN kitchen_stations ks ON ks.id=mc.station_id
                        JOIN print_routes pr ON pr.branch_id=o.branch_id
                          AND COALESCE(pr.station_id,CAST(:none AS uuid))=COALESCE(mc.station_id,CAST(:none AS uuid))
                        JOIN printers p ON p.id=pr.printer_id AND p.active
                        WHERE oi.id=:item
                        """).param("item", itemId).param("none", NO_STATION)
                .query((rs, row) -> new Object[]{rs.getObject("order_id", UUID.class), rs.getString("item_name"),
                        station(rs.getString("station_name")), rs.getObject("printer_id", UUID.class), rs.getInt("paper_width"),
                        rs.getString("modifiers")}).optional().orElse(null);
        if (item == null) return;
        var order = orderContext((UUID) item[0]);
        var ticket = new Ticket((Integer) item[4]).big("ANULADO")
                .center(order.label() + " · Ronda " + order.round())
                .center(((String) item[2]).toUpperCase(ES) + " · " + Instant.now().atZone(zone).format(TIME))
                .rule()
                .boldHanging(quantity + " x " + item[1], "    ");
        if (item[5] != null) ticket.text((String) item[5], "    ");
        ticket.rule().center("Motivo: " + reason);
        if (byName != null) ticket.center("Anuló: " + byName);
        insertJob(order.organizationId(), order.branchId(), (UUID) item[3], order.sessionId(), order.id(), (String) item[2],
                "ANULACION", "Anulación · " + order.label() + " · " + item[1], ticket.content());
    }

    /** ¿Este plato se prepara en una estación que imprime? Entonces el mesero puede servirlo directo. */
    public boolean itemPrints(UUID itemId) {
        return jdbc.sql("""
                        SELECT count(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                        LEFT JOIN menu_items mi ON mi.id=oi.menu_item_id LEFT JOIN menu_categories mc ON mc.id=mi.category_id
                        JOIN print_routes pr ON pr.branch_id=o.branch_id
                          AND COALESCE(pr.station_id,CAST(:none AS uuid))=COALESCE(mc.station_id,CAST(:none AS uuid))
                        WHERE oi.id=:item
                        """).param("item", itemId).param("none", NO_STATION).query(Integer.class).single() > 0;
    }

    /** Estaciones de la sucursal que imprimen (id de estación, o NO_STATION para productos sin estación). */
    public java.util.Set<String> printingStations(UUID branchId) {
        return new java.util.HashSet<>(jdbc.sql("SELECT COALESCE(station_id,CAST(:none AS uuid))::text FROM print_routes WHERE branch_id=:branch")
                .param("branch", branchId).param("none", NO_STATION).query(String.class).list());
    }

    /** Comandas de una mesa, para mostrarle al mesero si se imprimieron. */
    public List<JobView> jobsOfSession(UUID sessionId) {
        return jdbc.sql("""
                        SELECT id,order_id,station_name,kind,status,error,created_at FROM print_jobs
                        WHERE session_id=:session AND kind='COMANDA' ORDER BY created_at
                        """).param("session", sessionId).query((rs, row) -> new JobView(rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getString("station_name"), rs.getString("kind"),
                        rs.getString("status"), rs.getString("error"), rs.getTimestamp("created_at").toInstant())).list();
    }

    /** Vuelve a intentar un trabajo que falló. */
    @Transactional
    public void retry(UUID jobId) {
        var branch = currentUser.require().branchId();
        var updated = jdbc.sql("""
                UPDATE print_jobs SET status='PENDING',attempts=0,error=NULL,alerted_at=NULL,sent_at=NULL
                WHERE id=:id AND branch_id=:branch AND status IN ('FAILED','PENDING','SENT')
                """).param("id", jobId).param("branch", branch).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Ese ticket ya se imprimió o no existe");
    }

    /** Imprime de nuevo la comanda de una ronda (por ejemplo, si se perdió el papel). */
    @Transactional
    public void reprint(UUID orderId) {
        var branch = currentUser.require().branchId();
        var belongs = jdbc.sql("SELECT count(*) FROM orders WHERE id=:id AND branch_id=:branch AND status<>'DRAFT'")
                .param("id", orderId).param("branch", branch).query(Integer.class).single();
        if (belongs == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Ronda no encontrada");
        comandas(orderId, true);
    }

    // ——— Programa puente (sin sesión: se identifica con su token) ———

    /** Vincula una PC con el código de 6 dígitos que muestra la configuración; devuelve su token. */
    @Transactional
    public Paired pair(String code, String version) {
        var agent = jdbc.sql("""
                        SELECT a.id,b.name FROM print_agents a JOIN branches b ON b.id=a.branch_id
                        WHERE a.pairing_code=:code AND a.pairing_expires_at>NOW() AND a.revoked_at IS NULL FOR UPDATE OF a
                        """).param("code", code == null ? "" : code.trim())
                .query((rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name")}).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "El código no es válido o ya venció. Genera uno nuevo en RestOS"));
        var token = randomToken();
        jdbc.sql("""
                UPDATE print_agents SET token_hash=:hash,pairing_code=NULL,pairing_expires_at=NULL,last_seen_at=NOW(),version=:version
                WHERE id=:id
                """).param("hash", sha256(token)).param("version", version).param("id", agent[0]).update();
        return new Paired(token, (String) agent[1]);
    }

    /** El programa puente pide trabajos: se le entregan los pendientes con sus bytes listos para la impresora. */
    @Transactional
    public List<AgentJob> poll(String token, String version) {
        var agent = agentOf(token);
        jdbc.sql("UPDATE print_agents SET last_seen_at=NOW(),version=COALESCE(:version,version) WHERE id=:id")
                .param("version", version).param("id", agent.id()).update();
        var jobs = jdbc.sql("""
                        UPDATE print_jobs j SET status='SENT',sent_at=NOW(),attempts=attempts+1
                        FROM printers p WHERE p.id=j.printer_id AND j.id IN (
                          SELECT id FROM print_jobs WHERE branch_id=:branch AND status='PENDING'
                          ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED)
                        RETURNING j.id,j.title,j.content,p.host,p.port,p.paper_width
                        """).param("branch", agent.branchId())
                .query((rs, row) -> new AgentJob(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("host"),
                        rs.getInt("port"), Base64.getEncoder().encodeToString(EscPos.render(rs.getString("content"), rs.getInt("paper_width")))))
                .list();
        return jobs;
    }

    /** Resultado de un trabajo: impreso, o el error que dio la impresora (se reintenta hasta 3 veces). */
    @Transactional
    public void result(String token, UUID jobId, boolean ok, String error) {
        var agent = agentOf(token);
        var cleanError = error == null ? "La impresora no respondió" : error.length() > 240 ? error.substring(0, 240) : error;
        if (ok) {
            jdbc.sql("UPDATE print_jobs SET status='PRINTED',printed_at=NOW(),error=NULL WHERE id=:id AND branch_id=:branch")
                    .param("id", jobId).param("branch", agent.branchId()).update();
            jdbc.sql("UPDATE printers SET last_ok_at=NOW() WHERE id=(SELECT printer_id FROM print_jobs WHERE id=:id)")
                    .param("id", jobId).update();
            // La mesa se actualiza en la app del mesero («Comanda impresa»), sin sonido.
            jdbc.sql("SELECT session_id FROM print_jobs WHERE id=:id AND session_id IS NOT NULL").param("id", jobId)
                    .query(UUID.class).optional()
                    .ifPresent(session -> realtime.waiters(agent.branchId(), "PRINT_UPDATED", Map.of("sessionId", session)));
            return;
        }
        jdbc.sql("""
                UPDATE print_jobs SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END,error=:error
                WHERE id=:id AND branch_id=:branch
                """).param("error", cleanError).param("id", jobId).param("branch", agent.branchId()).update();
        jdbc.sql("UPDATE printers SET last_error=:error,last_error_at=NOW() WHERE id=(SELECT printer_id FROM print_jobs WHERE id=:id)")
                .param("error", cleanError).param("id", jobId).update();
    }

    // ——— Uso interno ———

    void insertJob(UUID organizationId, UUID branchId, UUID printerId, UUID sessionId, UUID orderId, String station,
                   String kind, String title, String content) {
        UUID createdBy = null;
        try { createdBy = currentUser.require().userId(); } catch (RuntimeException guest) { /* pedido del comensal */ }
        jdbc.sql("""
                INSERT INTO print_jobs(id,organization_id,branch_id,printer_id,session_id,order_id,station_name,kind,title,content,created_by)
                VALUES (:id,:org,:branch,:printer,:session,:order,:station,:kind,:title,:content,:by)
                """).param("id", UUID.randomUUID()).param("org", organizationId).param("branch", branchId)
                .param("printer", printerId).param("session", sessionId).param("order", orderId).param("station", station)
                .param("kind", kind).param("title", title.length() > 120 ? title.substring(0, 120) : title)
                .param("content", content).param("by", createdBy).update();
    }

    String testContent(String printerName, String host, int port, int paperWidth) {
        return new Ticket(paperWidth).big("RestOS").center("Prueba de impresión").center(printerName + " · " + host + ":" + port)
                .center(Instant.now().atZone(zone).format(DATE_TIME)).rule()
                .center("Si lees esto, la impresora está lista para las comandas.").center("Tildes: áéíóú ñ ¿¡").content();
    }

    private OrderContext orderContext(UUID orderId) {
        return jdbc.sql("""
                        SELECT o.id,o.organization_id,o.branch_id,o.session_id,o.round_number,COALESCE(o.submitted_at,NOW()) submitted_at,
                               COALESCE(t.label,ts.label) label,ts.channel,ts.customer_name,w.full_name waiter
                        FROM orders o JOIN table_sessions ts ON ts.id=o.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id LEFT JOIN app_users w ON w.id=ts.waiter_id
                        WHERE o.id=:id
                        """).param("id", orderId)
                .query((rs, row) -> new OrderContext(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getObject("session_id", UUID.class), rs.getInt("round_number"),
                        rs.getTimestamp("submitted_at").toInstant(), rs.getString("label"),
                        "DINE_IN".equals(rs.getString("channel")) ? null : rs.getString("customer_name"),
                        firstName(rs.getString("waiter")))).optional().orElse(null);
    }

    private Agent agentOf(String token) {
        if (token == null || token.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Falta el token del programa de impresión");
        return jdbc.sql("SELECT id,branch_id FROM print_agents WHERE token_hash=:hash AND revoked_at IS NULL")
                .param("hash", sha256(token.trim()))
                .query((rs, row) -> new Agent(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Esta PC ya no está vinculada a RestOS. Vuelve a vincularla"));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String pairingCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String station(String name) {
        return name == null ? "Cocina" : name;
    }

    private static String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? null : fullName.trim().split("\\s+")[0];
    }

    private record Line(int quantity, String name, String modifiers, String notes, String guest, String station,
                        UUID printerId, int paperWidth) {}
    private record OrderContext(UUID id, UUID organizationId, UUID branchId, UUID sessionId, int round, Instant submittedAt,
                                String label, String customer, String waiter) {}
    private record Agent(UUID id, UUID branchId) {}
    public record Paired(String token, String branchName) {}
    public record AgentJob(UUID id, String title, String host, int port, String data) {}
    public record JobView(UUID id, UUID orderId, String stationName, String kind, String status, String error, Instant createdAt) {}
}
