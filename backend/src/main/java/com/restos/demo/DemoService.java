package com.restos.demo;

import com.restos.security.JwtService;
import com.restos.security.RefreshTokenService;
import com.restos.security.RestosUserDetailsService;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restaurante de demostración para mostrar RestOS en minutos: cinco semanas de historia (ventas,
 * calificaciones, inventario) y un salón en vivo con pedidos en cocina, llamadas y lista de espera.
 * El estado en vivo se regenera cada vez que alguien entra a la demo (como máximo cada 10 minutos).
 */
@Service
public class DemoService {
    private static final String SLUG = "restos-showcase";
    private static final int HISTORY_DAYS = 35;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final PasswordEncoder encoder;
    private final RestosUserDetailsService users;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final boolean enabled;
    private final ZoneId zone;
    private Instant lastRefresh = Instant.EPOCH;

    public DemoService(JdbcClient jdbc, JdbcTemplate template, PasswordEncoder encoder, RestosUserDetailsService users,
                       JwtService jwt, RefreshTokenService refreshTokens,
                       @Value("${restos.demo.public-enabled:${restos.demo-data-enabled:true}}") boolean enabled,
                       @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.template = template;
        this.encoder = encoder;
        this.users = users;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.enabled = enabled;
        this.zone = ZoneId.of(zone);
    }

    public boolean enabled() { return enabled; }

    /** Entra a la demo con el rol pedido; prepara el restaurante si hace falta. */
    public synchronized DemoAccess start(String role) {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "La demo no está disponible");
        var demo = ensureDemo();
        if (Instant.now().isAfter(lastRefresh.plusSeconds(600))) {
            refreshLive(demo);
            lastRefresh = Instant.now();
        }
        var email = switch (role == null ? "" : role) {
            case "WAITER" -> "carlos@demo.restos.local";
            case "KITCHEN" -> "cocina@demo.restos.local";
            default -> "duena@demo.restos.local";
        };
        var account = users.findAccount(email);
        var tokens = jwt.create(account, account.branchId(), refreshTokens.issue(account.id(), account.branchId()));
        var qr = jdbc.sql("""
                        SELECT t.qr_public_id FROM dining_tables t WHERE t.branch_id=:branch AND t.active
                          AND NOT EXISTS (SELECT 1 FROM table_sessions ts WHERE ts.table_id=t.id AND ts.status='OPEN')
                        ORDER BY t.label LIMIT 1
                        """).param("branch", demo.branch()).query(UUID.class).optional().orElse(null);
        return new DemoAccess(tokens, qr);
    }

    // ——— Creación inicial ———

    private Demo ensureDemo() {
        var existing = jdbc.sql("""
                        SELECT o.id,b.id branch FROM organizations o JOIN branches b ON b.organization_id=o.id
                        WHERE o.slug=:slug ORDER BY b.created_at LIMIT 1
                        """).param("slug", SLUG)
                .query((rs, row) -> new Demo(rs.getObject("id", UUID.class), rs.getObject("branch", UUID.class))).optional();
        if (existing.isPresent()) {
            topUpHistory(existing.get());
            return existing.get();
        }
        return create();
    }

    @Transactional
    protected Demo create() {
        var org = UUID.randomUUID();
        var branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO organizations(id,name,slug,primary_color,paid_until,max_branches,plan,menu_theme)
                VALUES (:id,'Sabores de Lima',:slug,'#E4572E',CURRENT_DATE + 3650,3,'DEMO','FRESH')
                """).param("id", org).param("slug", SLUG).update();
        jdbc.sql("INSERT INTO branches(id,organization_id,name,address) VALUES (:id,:org,'Miraflores','Av. Larco 123, Miraflores')")
                .param("id", branch).param("org", org).update();
        var demo = new Demo(org, branch);
        var password = encoder.encode(UUID.randomUUID().toString());
        user(demo, "Valeria Ríos", "duena@demo.restos.local", "ORGANIZATION_ADMIN", password);
        user(demo, "Carlos Medina", "carlos@demo.restos.local", "WAITER", password);
        user(demo, "Lucía Paredes", "lucia@demo.restos.local", "WAITER", password);
        user(demo, "Diego Salas", "diego@demo.restos.local", "WAITER", password);
        user(demo, "Cocina", "cocina@demo.restos.local", "KITCHEN", password);

        // Zonas, mesas y plano.
        var salon = zone(demo, "Salón", "#2F8F9D", 0);
        var terraza = zone(demo, "Terraza", "#E4572E", 1);
        int[][] salonSpots = {{200, 230}, {380, 230}, {560, 230}, {740, 230}, {200, 430}, {380, 430}, {560, 430}, {740, 430}};
        for (var index = 0; index < salonSpots.length; index++) {
            table(demo, salon, String.format("Mesa %02d", index + 1), index % 4 == 3 ? 6 : 4,
                    salonSpots[index][0], salonSpots[index][1], index % 4 == 3 ? "RECT" : "SQUARE");
        }
        int[][] terraceSpots = {{220, 200}, {440, 200}, {660, 200}, {330, 420}, {550, 420}};
        for (var index = 0; index < terraceSpots.length; index++) {
            table(demo, terraza, "Terraza " + (index + 1), index == 4 ? 6 : 2, terraceSpots[index][0], terraceSpots[index][1], "ROUND");
        }
        element(demo, salon, "BAR", "Barra", 900, 120, 240, 70);
        element(demo, salon, "KITCHEN", "Cocina", 900, 300, 260, 160);
        element(demo, salon, "DOOR", "Entrada", 480, 776, 160, 20);
        element(demo, salon, "CASHIER", "Caja", 960, 640, 160, 60);
        element(demo, salon, "PLANT", null, 40, 40, 44, 44);
        element(demo, salon, "WC", "Baños", 40, 620, 130, 120);
        element(demo, terraza, "WALL", "Fachada", 0, 0, 1200, 14);
        element(demo, terraza, "DOOR", "Acceso al salón", 520, 4, 160, 20);
        for (var x = 60; x <= 1100; x += 160) element(demo, terraza, "PLANT", null, x, 740, 44, 44);

        // Estaciones, carta, insumos y recetas.
        var hot = station(demo, "Cocina caliente", 0);
        var cold = station(demo, "Fríos y ceviches", 1);
        var bar = station(demo, "Barra", 2);
        var starters = category(demo, "Entradas", 1, cold);
        var mains = category(demo, "Fondos", 2, hot);
        var seafood = category(demo, "Del mar", 3, cold);
        var drinks = category(demo, "Bebidas", 4, bar);
        var desserts = category(demo, "Postres", 5, cold);
        var fish = ingredient(demo, "Pescado fresco", "KG", "28.00", "3.000", "2.4");
        var lemon = ingredient(demo, "Limón", "KG", "6.50", "2.000", "1.2");
        var beef = ingredient(demo, "Lomo de res", "KG", "42.00", "4.000", "9.0");
        var chicken = ingredient(demo, "Pollo", "KG", "14.00", "3.000", "7.5");
        var potato = ingredient(demo, "Papa amarilla", "KG", "4.00", "5.000", "18.0");
        var corn = ingredient(demo, "Maíz morado", "KG", "7.00", "2.000", "1.4");
        var pisco = ingredient(demo, "Pisco quebranta", "L", "45.00", "1.000", "3.2");
        item(demo, starters, "Causa limeña", "Papa amarilla, pollo y palta", "22.00", "", Map.of(potato, "0.200", chicken, "0.080"));
        item(demo, starters, "Papa a la huancaína", "Crema de ají amarillo y queso", "18.00", "VEGETARIAN", Map.of(potato, "0.250"));
        item(demo, starters, "Anticuchos", "Corazón a la parrilla con papa y choclo", "26.00", "SPICY", Map.of(potato, "0.120"));
        item(demo, mains, "Lomo saltado", "Lomo al wok, papas fritas y arroz", "42.00", "CHEF", Map.of(beef, "0.220", potato, "0.200"));
        item(demo, mains, "Ají de gallina", "Pollo en crema de ají amarillo", "34.00", "SPICY", Map.of(chicken, "0.200"));
        item(demo, mains, "Arroz con pollo", "Arroz verde al culantro con pollo", "32.00", "", Map.of(chicken, "0.250"));
        item(demo, mains, "Tallarín saltado vegetariano", "Verduras al wok con sillao", "29.00", "VEGAN", Map.of());
        item(demo, seafood, "Ceviche clásico", "Pescado del día, limón, ají limo y camote", "39.00", "SPICY", Map.of(fish, "0.200", lemon, "0.120"));
        item(demo, seafood, "Tiradito al ají amarillo", "Láminas de pescado con crema de ají", "37.00", "NEW", Map.of(fish, "0.180", lemon, "0.080"));
        item(demo, seafood, "Jalea mixta", "Mariscos y pescado frito con sarsa criolla", "45.00", "", Map.of(fish, "0.250", lemon, "0.040"));
        item(demo, drinks, "Chicha morada", "Jarra de 1 litro", "16.00", "VEGAN", Map.of(corn, "0.150"));
        item(demo, drinks, "Pisco sour", "Pisco quebranta, limón y amargo", "24.00", "", Map.of(pisco, "0.090", lemon, "0.050"));
        item(demo, drinks, "Limonada frozen", "Con hierbabuena", "14.00", "VEGAN", Map.of(lemon, "0.100"));
        item(demo, drinks, "Inca Kola", "Botella 500 ml", "7.00", "", Map.of());
        item(demo, desserts, "Suspiro limeño", "Manjar blanco y merengue al oporto", "18.00", "", Map.of());
        item(demo, desserts, "Picarones", "Con miel de chancaca", "16.00", "NEW", Map.of());
        item(demo, starters, "Tequeños con guacamole", "Masa crocante rellena de queso", "24.00", "VEGETARIAN", Map.of());
        item(demo, starters, "Papa rellena", "Rellena de carne, con sarsa criolla", "20.00", "", Map.of());
        item(demo, starters, "Ocopa arequipeña", "Papas en crema de huacatay y maní", "19.00", "VEGETARIAN", Map.of());
        item(demo, starters, "Choclo con queso", "Choclo serrano con queso fresco", "15.00", "VEGETARIAN", Map.of());
        item(demo, mains, "Seco de res con frejoles", "Res al culantro, frejoles y arroz", "38.00", "", Map.of());
        item(demo, mains, "Pollo a la brasa", "1/4 de pollo con papas y ensalada", "29.00", "", Map.of());
        item(demo, mains, "Tacu tacu con lomo", "Frejol y arroz dorados con lomo saltado", "44.00", "CHEF", Map.of());
        item(demo, mains, "Carapulcra con sopa seca", "Papa seca con cerdo y tallarín", "33.00", "SPICY", Map.of());
        item(demo, mains, "Quinotto de champiñones", "Quinua cremosa con parmesano", "31.00", "VEGETARIAN", Map.of());
        item(demo, seafood, "Arroz con mariscos", "Arroz meloso al ají panca", "42.00", "", Map.of());
        item(demo, seafood, "Chicharrón de pescado", "Con yuca frita y salsa tártara", "36.00", "", Map.of());
        item(demo, seafood, "Choritos a la chalaca", "Mejillones con sarsa de rocoto", "28.00", "SPICY", Map.of());
        item(demo, seafood, "Parihuela", "Sopa marina bien concentrada", "46.00", "NEW", Map.of());
        item(demo, drinks, "Maracuyá sour", "Pisco, maracuyá y limón", "24.00", "", Map.of());
        item(demo, drinks, "Jugo de fresa", "Fresa natural, vaso grande", "12.00", "VEGAN", Map.of());
        item(demo, drinks, "Café pasado", "Café de Chanchamayo", "8.00", "VEGAN", Map.of());
        item(demo, drinks, "Cerveza Cusqueña", "Botella 620 ml", "12.00", "", Map.of());
        item(demo, drinks, "Agua sin gas", "Botella 625 ml", "5.00", "VEGAN", Map.of());
        item(demo, desserts, "Arroz con leche", "Con canela y clavo", "12.00", "", Map.of());
        item(demo, desserts, "Mazamorra morada", "Maíz morado con frutas", "12.00", "VEGAN", Map.of());
        item(demo, desserts, "Tres leches", "Bizcocho húmedo con manjar", "17.00", "", Map.of());
        item(demo, desserts, "Helado de lúcuma", "Dos bolas, artesanal", "14.00", "NEW", Map.of());

        // Metas de venta por día de la semana (más altas el fin de semana).
        String[] goals = {"1800", "1800", "2000", "2200", "3200", "3800", "3000"};
        for (var day = 1; day <= 7; day++) {
            jdbc.sql("INSERT INTO sales_goals(branch_id,weekday,amount) VALUES (:branch,:day,:amount)")
                    .param("branch", branch).param("day", day).param("amount", new BigDecimal(goals[day - 1])).update();
        }
        jdbc.sql("UPDATE organizations SET billing_note=:day WHERE id=:id")
                .param("day", LocalDate.now(zone).minusDays(HISTORY_DAYS + 1).toString()).param("id", org).update();
        topUpHistory(demo);
        return demo;
    }

    // ——— Historia: días cerrados con ventas, calificaciones y consumo de insumos ———

    /** Genera los días que falten entre la última historia y ayer (la demo «envejece» con el tiempo). */
    private void topUpHistory(Demo demo) {
        var last = jdbc.sql("SELECT billing_note FROM organizations WHERE id=:id").param("id", demo.org())
                .query(String.class).optional().map(LocalDate::parse).orElse(LocalDate.now(zone).minusDays(HISTORY_DAYS + 1));
        var yesterday = LocalDate.now(zone).minusDays(1);
        var from = last.plusDays(1).isBefore(yesterday.minusDays(HISTORY_DAYS)) ? yesterday.minusDays(HISTORY_DAYS) : last.plusDays(1);
        if (from.isAfter(yesterday)) return;
        var context = context(demo);
        var random = new Random(from.toEpochDay());
        for (var day = from; !day.isAfter(yesterday); day = day.plusDays(1)) {
            var weekday = day.getDayOfWeek().getValue();
            var visits = (weekday >= 5 ? 26 : 16) + random.nextInt(8);
            for (var visit = 0; visit < visits; visit++) {
                var lunch = random.nextDouble() < 0.55;
                var hour = lunch ? 12 + random.nextInt(3) : 19 + random.nextInt(3);
                var opened = day.atTime(hour, random.nextInt(60)).atZone(zone).toInstant();
                closedVisit(context, random, opened, 45 + random.nextInt(50), true);
            }
        }
        flush(context);
        jdbc.sql("UPDATE organizations SET billing_note=:day WHERE id=:id")
                .param("day", yesterday.toString()).param("id", demo.org()).update();
    }

    // ——— Estado en vivo ———

    @Transactional
    protected void refreshLive(Demo demo) {
        modifiers(demo);
        var context = context(demo);
        var now = ZonedDateTime.now(zone);
        var todayStart = now.toLocalDate().atStartOfDay(zone).toInstant();
        // Limpia lo que dejaron visitas anteriores de la demo.
        jdbc.sql("""
                UPDATE service_requests SET status='RESOLVED',handled_at=NOW(),updated_at=NOW()
                WHERE branch_id=:branch AND status IN ('PENDING','IN_PROGRESS')
                """).param("branch", demo.branch()).update();
        jdbc.sql("UPDATE table_sessions SET status='CLOSED',closed_at=NOW(),close_reason='Demo reiniciada' WHERE branch_id=:branch AND status='OPEN'")
                .param("branch", demo.branch()).update();
        // Los platos de visitas anteriores ya no deben seguir «en cocina».
        jdbc.sql("""
                UPDATE order_items SET status='DELIVERED',ready_at=COALESCE(ready_at,NOW()),updated_at=NOW()
                WHERE status IN ('SUBMITTED','PREPARING','READY') AND order_id IN (
                  SELECT o.id FROM orders o JOIN table_sessions ts ON ts.id=o.session_id
                  WHERE ts.branch_id=:branch AND ts.status<>'OPEN')
                """).param("branch", demo.branch()).update();
        jdbc.sql("""
                UPDATE orders SET status='DELIVERED',updated_at=NOW() WHERE status IN ('SUBMITTED','PREPARING','READY')
                  AND session_id IN (SELECT id FROM table_sessions WHERE branch_id=:branch AND status<>'OPEN')
                """).param("branch", demo.branch()).update();
        jdbc.sql("DELETE FROM waitlist_entries WHERE branch_id=:branch AND status IN ('WAITING','NOTIFIED','CANCELLED')")
                .param("branch", demo.branch()).update();
        jdbc.sql("UPDATE reservations SET status='CANCELLED' WHERE branch_id=:branch AND status IN ('PENDING','CONFIRMED')")
                .param("branch", demo.branch()).update();
        // Ventas de hoy hasta ahora: si es muy temprano, el «día» de la demo empieza seis horas atrás.
        var todayVisits = jdbc.sql("SELECT count(*) FROM table_sessions WHERE branch_id=:branch AND opened_at>=:start")
                .param("branch", demo.branch()).param("start", Timestamp.from(todayStart)).query(Integer.class).single();
        var random = new Random();
        if (todayVisits < 8) {
            var windowStart = now.minusHours(6).toInstant();
            if (windowStart.isBefore(todayStart)) windowStart = todayStart;
            var minutes = Math.max(60, (int) ((now.toInstant().getEpochSecond() - windowStart.getEpochSecond()) / 60) - 50);
            for (var visit = 0; visit < 12; visit++) {
                closedVisit(context, random, windowStart.plusSeconds(random.nextInt(minutes) * 60L), 40 + random.nextInt(40), true);
            }
        }
        // Mesas ocupadas ahora mismo, cada una en un momento distinto del servicio.
        var tables = context.tables();
        openVisit(context, tables.get(0), 6, List.of(new Line("Ceviche clásico", 2, "READY", 18), new Line("Pisco sour", 2, "DELIVERED", 25)), null);
        openVisit(context, tables.get(1), 22, List.of(new Line("Lomo saltado", 2, "PREPARING", 22), new Line("Anticuchos", 1, "PREPARING", 22)), null);
        openVisit(context, tables.get(2), 3, List.of(new Line("Ají de gallina", 1, "SUBMITTED", 3), new Line("Chicha morada", 1, "SUBMITTED", 3)), "WAITER_CALL");
        openVisit(context, tables.get(4), 55, List.of(new Line("Jalea mixta", 1, "DELIVERED", 50), new Line("Arroz con pollo", 2, "DELIVERED", 48),
                new Line("Suspiro limeño", 2, "DELIVERED", 10)), "BILL");
        openVisit(context, tables.get(8), 12, List.of(new Line("Tiradito al ají amarillo", 1, "PREPARING", 9), new Line("Limonada frozen", 2, "READY", 6)), null);
        openVisit(context, tables.get(10), 1, List.of(new Line("Causa limeña", 2, "SUBMITTED", 1)), null);
        flush(context);
        // Lista de espera y una reserva para esta noche.
        waitlist(demo, "Familia Torres", 4, 18);
        waitlist(demo, "Andrea y Luis", 2, 6);
        reservations(demo, now);
        // Inventario: el pescado se acaba hoy y el pisco está bajo el mínimo (lista de compras).
        stock(demo, "Pescado fresco", "1.100");
        stock(demo, "Pisco quebranta", "0.700");
        stock(demo, "Limón", "3.500");
        counterOrders(demo);
        cashShifts(demo);
        customers(demo);
        floorStudio(demo);
        auditTrail(demo);
    }

    /** Estudio de planos de la demo: dos configuraciones del salón, mesas por mesero y reservas online activas. */
    private void floorStudio(Demo demo) {
        jdbc.sql("UPDATE branches SET online_booking=TRUE WHERE id=:branch").param("branch", demo.branch()).update();
        var salon = jdbc.sql("SELECT id FROM dining_zones WHERE branch_id=:branch AND name='Salón'").param("branch", demo.branch()).query(UUID.class).optional();
        if (salon.isEmpty()) return;
        var existing = jdbc.sql("SELECT count(*) FROM floor_layouts WHERE zone_id=:zone").param("zone", salon.get()).query(Integer.class).single();
        if (existing > 0) return;
        jdbc.sql("UPDATE dining_zones SET plan_width_meters=12 WHERE branch_id=:branch").param("branch", demo.branch()).update();
        // Meseros: Carlos el salón de adelante, Lucía el de atrás y Diego la terraza.
        String[][] owners = {{"Carlos Medina", "Mesa 01", "Mesa 02", "Mesa 03", "Mesa 04"}, {"Lucía Paredes", "Mesa 05", "Mesa 06", "Mesa 07", "Mesa 08"},
            {"Diego Salas", "Terraza 1", "Terraza 2", "Terraza 3", "Terraza 4", "Terraza 5"}};
        for (var owner : owners) {
            jdbc.sql("""
                    UPDATE dining_tables SET waiter_id=(SELECT id FROM app_users WHERE branch_id=:branch AND full_name=:name LIMIT 1)
                    WHERE branch_id=:branch AND label IN (:labels)
                    """).param("branch", demo.branch()).param("name", owner[0])
                    .param("labels", java.util.Arrays.asList(owner).subList(1, owner.length)).update();
        }
        var snapshot = """
                jsonb_build_object(
                  'tables',(SELECT COALESCE(jsonb_agg(jsonb_build_object('id',t.id,'x',t.plan_x,'y',t.plan_y,'shape',t.plan_shape,'rotation',t.plan_rotation)),'[]'::jsonb)
                            FROM dining_tables t WHERE t.zone_id=:zone AND t.active),
                  'elements',(SELECT COALESCE(jsonb_agg(jsonb_build_object('kind',e.kind,'label',e.label,'x',e.x,'y',e.y,'width',e.width,'height',e.height,'rotation',e.rotation)),'[]'::jsonb)
                              FROM floor_elements e WHERE e.zone_id=:zone))""";
        var normal = UUID.randomUUID();
        jdbc.sql("INSERT INTO floor_layouts(id,organization_id,branch_id,zone_id,name,data) VALUES (:id,:org,:branch,:zone,'Día normal'," + snapshot + ")")
                .param("id", normal).param("org", demo.org()).param("branch", demo.branch()).param("zone", salon.get()).update();
        // Evento: las mesas juntas en dos filas largas para un grupo grande.
        jdbc.sql("""
                INSERT INTO floor_layouts(id,organization_id,branch_id,zone_id,name,data)
                SELECT :id,:org,:branch,:zone,'Evento 30 personas',jsonb_build_object(
                  'tables',(SELECT jsonb_agg(jsonb_build_object('id',t.id,'x',260 + ((row_number - 1) % 4) * 120,'y',CASE WHEN row_number <= 4 THEN 300 ELSE 420 END,
                            'shape','RECT','rotation',0)) FROM (SELECT t.*,row_number() OVER (ORDER BY t.label) row_number FROM dining_tables t WHERE t.zone_id=:zone AND t.active) t),
                  'elements',(SELECT COALESCE(jsonb_agg(jsonb_build_object('kind',e.kind,'label',e.label,'x',e.x,'y',e.y,'width',e.width,'height',e.height,'rotation',e.rotation)),'[]'::jsonb)
                              FROM floor_elements e WHERE e.zone_id=:zone))
                """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch()).param("zone", salon.get()).update();
        jdbc.sql("UPDATE dining_zones SET active_layout_id=:id WHERE id=:zone").param("id", normal).param("zone", salon.get()).update();
    }

    /** Bitácora de la demo: anulaciones, descuentos, caja, precios, PIN y mermas de los últimos días. */
    private void auditTrail(Demo demo) {
        var recent = jdbc.sql("SELECT count(*) FROM audit_events WHERE branch_id=:branch AND created_at > now() - interval '2 days'")
                .param("branch", demo.branch()).query(Integer.class).single();
        if (recent >= 12) return;
        // [días atrás, hora, minuto, usuario, acción, detalle, monto]
        Object[][] events = {
            {0, 11, 32, "Carlos Medina", "PIN_LOGIN", "Ingreso con PIN en Tablet salón", null},
            {0, 11, 35, "Lucía Paredes", "PIN_LOGIN", "Ingreso con PIN en Tablet salón", null},
            {0, 11, 40, "Valeria Ríos", "CASH_OPENED", "Apertura de caja con fondo de S/ 200.00", "200"},
            {0, 13, 5, "Carlos Medina", "ITEM_VOIDED", "Mesa 03 · 1× Lomo saltado · Motivo: el cliente cambió de plato", "42"},
            {0, 13, 48, "Valeria Ríos", "CASH_MOVEMENT", "Salida de S/ 45.00 · Compra de limones en el mercado", "45"},
            {0, 14, 20, "Carlos Medina", "DISCOUNT_APPLIED", "Mesa 02 · S/ 20.00 · Demora en cocina", "20"},
            {0, 15, 2, "Cocina", "AVAILABILITY_CHANGED", "Ceviche clásico marcado como agotado (se acabó el pescado)", null},
            {0, 16, 11, "Carlos Medina", "ITEM_VOIDED", "Mesa 05 · 2× Pisco sour · Motivo: error al tomar el pedido", "48"},
            {0, 17, 30, "Diego Salas", "SESSION_CANCELLED", "Terraza 4 · Atención cancelada sin consumo", null},
            {1, 12, 15, "Valeria Ríos", "PRICE_CHANGED", "Ceviche clásico: S/ 36.00 → S/ 38.00", "38"},
            {1, 13, 40, "Lucía Paredes", "ITEM_VOIDED", "Mesa 06 · 1× Chicha morada · Motivo: se derramó", "8"},
            {1, 20, 10, "Valeria Ríos", "DISCOUNT_APPLIED", "Mesa 01 · 10 % · Cliente frecuente (cumpleaños)", "18.5"},
            {1, 21, 5, "Lucía Paredes", "POINTS_REDEEMED", "Mariana Torres canjeó 100 puntos", "10"},
            {1, 23, 12, "Valeria Ríos", "CASH_CLOSED", "Cierre de caja · contado S/ 1,038.00 · cuadró", "1038"},
            {2, 10, 50, "Valeria Ríos", "STOCK_WASTE", "Limón · 0.5 kg · Motivo: vencido", "3"},
            {2, 19, 45, "Carlos Medina", "DISCOUNT_APPLIED", "Mesa 08 · S/ 15.00 · Plato llegó frío", "15"},
            {2, 23, 30, "Valeria Ríos", "CASH_CLOSED", "Cierre de caja · contado S/ 892.00 · faltaron S/ 12.50", "892"},
            {3, 18, 20, "Valeria Ríos", "USER_UPDATED", "Diego Salas: cambió a zona Terraza", null},
            {4, 12, 0, "Valeria Ríos", "STOCK_PURCHASE", "Pescado fresco · 6 kg a S/ 28.00", "168"},
        };
        var today = LocalDate.now(zone);
        for (var event : events) {
            var at = today.minusDays((Integer) event[0]).atTime((Integer) event[1], (Integer) event[2]).atZone(zone).toInstant();
            if (at.isAfter(Instant.now())) at = Instant.now().minusSeconds(60L * (events.length - java.util.Arrays.asList(events).indexOf(event)));
            jdbc.sql("""
                    INSERT INTO audit_events(organization_id,branch_id,user_id,action,summary,amount,created_at)
                    VALUES (:org,:branch,(SELECT id FROM app_users WHERE branch_id=:branch AND full_name=:user LIMIT 1),:action,:summary,:amount,:at)
                    """).param("org", demo.org()).param("branch", demo.branch()).param("user", event[3]).param("action", event[4])
                    .param("summary", event[5]).param("amount", event[6] == null ? null : new BigDecimal((String) event[6]))
                    .param("at", Timestamp.from(at)).update();
        }
    }

    /** Agenda de la demo: reservas de hoy y mañana en distintos estados, algunas de clientes frecuentes. */
    private void reservations(Demo demo, ZonedDateTime now) {
        var todayStart = Timestamp.from(now.toLocalDate().atStartOfDay(zone).toInstant());
        jdbc.sql("DELETE FROM reservations WHERE branch_id=:branch AND reserved_at>=:start AND session_id IS NULL")
                .param("branch", demo.branch()).param("start", todayStart).update();
        var base = now.withSecond(0).withNano(0).withMinute((now.getMinute() / 15) * 15);
        var tomorrow = now.toLocalDate().plusDays(1);
        // cliente, celular, personas, hora, duración, estado, nota, zona, mesa
        Object[][] rows = {
            {"Pedro Salas", "981234567", 2, base.minusMinutes(90), 90, "NO_SHOW", null, null, null},
            {"Mariana Torres", "987654321", 2, base.minusMinutes(30), 90, "CONFIRMED", "Alérgica a los mariscos con concha", "Salón", "Mesa 06"},
            {"Jorge Castillo", "986512347", 4, base.plusMinutes(30), 90, "CONFIRMED", "Prefiere mesa junto a la ventana", "Terraza", null},
            {"Luis Paredes", "953214786", 5, base.plusMinutes(60), 90, "CANCELLED", "Cambió de planes", null, null},
            {"Ana Lucía Ramos", "962819305", 3, base.plusMinutes(75), 90, "PENDING", null, null, null},
            {"Cumpleaños de Sofía", "987001122", 8, base.plusMinutes(120), 120, "CONFIRMED", "Torta propia, decoración con globos", "Terraza", null},
            {"Carlos Mendoza", "975318642", 2, base.plusMinutes(180), 90, "PENDING", "Aniversario: postre con vela", "Salón", "Mesa 07"},
            {"Empresa Andina", "944556677", 10, tomorrow.atTime(13, 0).atZone(zone), 120, "CONFIRMED", "Almuerzo de trabajo, factura a nombre de la empresa", null, null},
            {"Rosa Huamán", "981236547", 2, tomorrow.atTime(20, 30).atZone(zone), 90, "PENDING", null, "Terraza", null},
        };
        for (var row : rows) {
            jdbc.sql("""
                    INSERT INTO reservations(id,organization_id,branch_id,customer_id,customer_name,phone,party_size,reserved_at,duration_minutes,status,notes,zone_id,table_id)
                    VALUES (:id,:org,:branch,(SELECT id FROM customers WHERE organization_id=:org AND phone=:phone),:name,:phone,:party,:at,:duration,:status,:notes,
                      (SELECT id FROM dining_zones WHERE branch_id=:branch AND name=:zone),(SELECT id FROM dining_tables WHERE branch_id=:branch AND label=:table LIMIT 1))
                    """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch())
                    .param("name", row[0]).param("phone", row[1]).param("party", row[2])
                    .param("at", Timestamp.from(((ZonedDateTime) row[3]).toInstant())).param("duration", row[4]).param("status", row[5])
                    .param("notes", row[6]).param("zone", row[7]).param("table", row[8]).update();
        }
    }

    /** Mostrador de la demo: pedidos para llevar y delivery en cada etapa (nuevo, en cocina, listo, por cobrar). */
    private void counterOrders(Demo demo) {
        var waiter = jdbc.sql("SELECT id FROM app_users WHERE branch_id=:branch AND role IN ('ORGANIZATION_ADMIN','BRANCH_MANAGER') LIMIT 1")
                .param("branch", demo.branch()).query(UUID.class).single();
        var dayStart = Timestamp.from(LocalDate.now(zone).atStartOfDay(zone).toInstant());
        var number = jdbc.sql("SELECT COALESCE(max(order_number),0) FROM table_sessions WHERE branch_id=:branch AND channel<>'DINE_IN' AND opened_at>=:start")
                .param("branch", demo.branch()).param("start", dayStart).query(Integer.class).single();
        // canal, cliente, celular, dirección, referencia, minutos, platos [nombre, cantidad, estado], pagado
        Object[][] orders = {
            {"DELIVERY", "Carlos Ruiz", "987111222", "Av. Larco 345, dpto. 502", "Frente al parque Kennedy", 14,
                new Object[][] {{"Lomo saltado", 2, "PREPARING"}, {"Chicha morada", 2, "SUBMITTED"}}, false},
            {"TAKEAWAY", "Ana Torres", "986333444", null, null, 22,
                new Object[][] {{"Ceviche clásico", 1, "READY"}, {"Pisco sour", 1, "READY"}}, true},
            {"DELIVERY", "Luis Medina", "985555666", "Calle Schell 120", "Edificio azul, timbre 3", 31,
                new Object[][] {{"Arroz con pollo", 2, "READY"}, {"Limonada frozen", 2, "READY"}}, false},
            {"TAKEAWAY", "Sofía Díaz", null, null, null, 40,
                new Object[][] {{"Ají de gallina", 1, "DELIVERED"}, {"Causa limeña", 1, "DELIVERED"}}, false},
            {"TAKEAWAY", "Jorge Paredes", "984777888", null, null, 2, new Object[][] {}, false},
        };
        for (var data : orders) {
            number++;
            var session = UUID.randomUUID();
            var channel = (String) data[0];
            var opened = Instant.now().minusSeconds(((Integer) data[5]) * 60L);
            jdbc.sql("""
                    INSERT INTO table_sessions(id,organization_id,branch_id,status,opened_by,opened_source,channel,label,order_number,
                      customer_name,customer_phone,delivery_address,delivery_reference,delivery_fee,opened_at)
                    VALUES (:id,:org,:branch,'OPEN',:user,'COUNTER',:channel,:label,:number,:name,:phone,:address,:reference,:fee,:opened)
                    """).param("id", session).param("org", demo.org()).param("branch", demo.branch()).param("user", waiter)
                    .param("channel", channel).param("label", (channel.equals("DELIVERY") ? "Delivery #" : "Para llevar #") + number)
                    .param("number", number).param("name", data[1]).param("phone", data[2]).param("address", data[3])
                    .param("reference", data[4]).param("fee", channel.equals("DELIVERY") ? new BigDecimal("5.00") : BigDecimal.ZERO)
                    .param("opened", Timestamp.from(opened)).update();
            var lines = (Object[][]) data[6];
            if (lines.length == 0) continue;
            var order = UUID.randomUUID();
            var submitted = opened.plusSeconds(60);
            var allDelivered = java.util.Arrays.stream(lines).allMatch(line -> "DELIVERED".equals(line[2]));
            jdbc.sql("INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status,submitted_at) VALUES (:id,:org,:branch,:session,1,:status,:at)")
                    .param("id", order).param("org", demo.org()).param("branch", demo.branch()).param("session", session)
                    .param("status", allDelivered ? "DELIVERED" : "SUBMITTED").param("at", Timestamp.from(submitted)).update();
            var total = BigDecimal.ZERO;
            for (var line : lines) {
                var item = jdbc.sql("SELECT id,price FROM menu_items WHERE organization_id=:org AND name=:name LIMIT 1")
                        .param("org", demo.org()).param("name", line[0])
                        .query((rs, row) -> new Object[] {rs.getObject("id", UUID.class), rs.getBigDecimal("price")}).optional();
                if (item.isEmpty()) continue;
                var status = (String) line[2];
                var price = (BigDecimal) item.get()[1];
                total = total.add(price.multiply(BigDecimal.valueOf((Integer) line[1])));
                jdbc.sql("""
                        INSERT INTO order_items(id,order_id,menu_item_id,item_name,unit_price,quantity,status,created_at,ready_at,created_by)
                        VALUES (:id,:order,:menu,:name,:price,:quantity,:status,:created,:ready,:user)
                        """).param("id", UUID.randomUUID()).param("user", waiter).param("order", order).param("menu", item.get()[0]).param("name", line[0])
                        .param("price", price).param("quantity", line[1]).param("status", status).param("created", Timestamp.from(submitted))
                        .param("ready", status.equals("READY") || status.equals("DELIVERED") ? Timestamp.from(Instant.now().minusSeconds(240)) : null).update();
            }
            if ((Boolean) data[7]) {
                jdbc.sql("""
                        INSERT INTO payments(id,organization_id,branch_id,session_id,method,amount,tip,registered_by,paid_at)
                        VALUES (:id,:org,:branch,:session,'YAPE',:amount,0,:user,:at)
                        """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch()).param("session", session)
                        .param("amount", total).param("user", waiter).param("at", Timestamp.from(submitted)).update();
            }
        }
    }

    /** Clientes de la demo: habituales, frecuentes, que dejaron de venir, nuevos y cumpleañeros del mes. */
    private void customers(Demo demo) {
        var existing = jdbc.sql("SELECT count(*) FROM customers WHERE organization_id=:org").param("org", demo.org()).query(Integer.class).single();
        if (existing > 0) return;
        var month = LocalDate.now(zone).getMonthValue();
        var random = new Random(7);
        // nombre, celular, perfil, mes de cumpleaños (0 = sin dato), nota
        Object[][] people = {
            {"Mariana Torres", "987654321", "VIP", month, "Alérgica a los mariscos con concha"},
            {"Jorge Castillo", "986512347", "VIP", 3, "Siempre pide mesa en la terraza"},
            {"Rosa Huamán", "981236547", "VIP", 7, null},
            {"Andrés Quispe", "975318642", "FREQ", month, null},
            {"Carla Mendoza", "964578213", "FREQ", 11, "Prefiere el ceviche poco picante"},
            {"Luis Paredes", "953214786", "FREQ", 1, null},
            {"Valeria Ríos Soto", "942167853", "FREQ", 5, null},
            {"Diego Fernández", "931245678", "FREQ", 0, null},
            {"Patricia Salazar", "928374615", "RISK", 9, null},
            {"Ricardo Vega", "917263548", "RISK", 2, "Celebró su aniversario aquí"},
            {"Sofía Navarro", "906152437", "RISK", 0, null},
            {"Miguel Ángel Rojas", "995142638", "RISK", 6, null},
            {"Lucía Chávez", "984031527", "NEW", month, null},
            {"Fernando Díaz", "973920416", "NEW", 0, null},
            {"Gabriela Ramos", "962819305", "NEW", 4, "Vegetariana"},
            {"Martín Gutiérrez", "951708294", "ONE", 8, null},
            {"Elena Cárdenas", "940697183", "ONE", 12, null},
            {"Pablo Herrera", "939586072", "ONE", 0, null},
            {"Natalia Flores", "928475961", "FREQ", 10, null},
            {"Óscar Villanueva", "917364850", "ONE", 0, null},
        };
        var now = Instant.now();
        for (var person : people) {
            var id = UUID.randomUUID();
            var birthMonth = (Integer) person[3];
            var birthday = birthMonth == 0 ? null : LocalDate.of(1978 + random.nextInt(20), birthMonth, 1 + random.nextInt(27));
            jdbc.sql("INSERT INTO customers(id,organization_id,full_name,phone,birthday,notes,created_at) VALUES (:id,:org,:name,:phone,:birthday,:notes,:created)")
                    .param("id", id).param("org", demo.org()).param("name", person[0]).param("phone", person[1])
                    .param("birthday", birthday).param("notes", person[4]).param("created", Timestamp.from(now.minusSeconds(86400L * 40))).update();
            var profile = (String) person[2];
            var count = switch (profile) { case "VIP" -> 11; case "FREQ" -> 5; case "RISK" -> 3; default -> 1; };
            // Los que dejaron de venir solo tienen visitas de hace más de 30 días; los nuevos, de esta semana.
            var range = switch (profile) {
                case "RISK" -> "ts.closed_at < now() - interval '31 days'";
                case "NEW" -> "ts.closed_at >= now() - interval '6 days'";
                default -> "ts.closed_at >= now() - interval '29 days'";
            };
            jdbc.sql("""
                    UPDATE table_sessions SET customer_id=:customer WHERE id IN (
                      SELECT ts.id FROM table_sessions ts WHERE ts.branch_id=:branch AND ts.status='CLOSED' AND ts.customer_id IS NULL
                        AND EXISTS (SELECT 1 FROM payments p WHERE p.session_id=ts.id) AND
                    """ + range + " ORDER BY random() LIMIT :count)")
                    .param("customer", id).param("branch", demo.branch()).param("count", count).update();
            jdbc.sql("""
                    INSERT INTO loyalty_movements(customer_id,session_id,points,reason,created_at)
                    SELECT :customer,ts.id,floor(COALESCE((SELECT sum(p.amount) FROM payments p WHERE p.session_id=ts.id),0)/10)::int,
                      'Consumo · '||COALESCE(t.label,ts.label,'Mostrador'),ts.closed_at
                    FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE ts.customer_id=:customer
                    """).param("customer", id).update();
            if ("VIP".equals(profile)) {
                jdbc.sql("INSERT INTO loyalty_movements(customer_id,points,reason,created_at) VALUES (:customer,-50,'Canje: pisco sour de cortesía',now() - interval '8 days')")
                        .param("customer", id).update();
            }
            jdbc.sql("""
                    UPDATE customers c SET
                      visits=(SELECT count(*) FROM table_sessions ts WHERE ts.customer_id=c.id),
                      total_spent=(SELECT COALESCE(sum(p.amount),0) FROM payments p JOIN table_sessions ts ON ts.id=p.session_id WHERE ts.customer_id=c.id),
                      last_visit_at=(SELECT max(ts.closed_at) FROM table_sessions ts WHERE ts.customer_id=c.id),
                      points=GREATEST(0,(SELECT COALESCE(sum(m.points),0) FROM loyalty_movements m WHERE m.customer_id=c.id)),
                      created_at=LEAST(c.created_at,COALESCE((SELECT min(ts.closed_at) FROM table_sessions ts WHERE ts.customer_id=c.id),c.created_at))
                    WHERE c.id=:customer
                    """).param("customer", id).update();
            if ("NEW".equals(profile)) {
                jdbc.sql("UPDATE customers SET created_at=COALESCE(last_visit_at,now()) - interval '1 hour' WHERE id=:customer").param("customer", id).update();
            }
        }
    }

    /** Caja de la demo: cierres de días anteriores (uno con faltante) y el turno de hoy abierto con movimientos. */
    private void cashShifts(Demo demo) {
        var owner = jdbc.sql("SELECT id FROM app_users WHERE branch_id=:branch AND role='ORGANIZATION_ADMIN' LIMIT 1")
                .param("branch", demo.branch()).query(UUID.class).single();
        var todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        // Un turno abierto de un día anterior se cierra cuadrado.
        jdbc.sql("""
                UPDATE cash_shifts s SET closed_at=s.opened_at + INTERVAL '11 hours',closed_by=s.opened_by,
                  expected_amount=s.opening_amount + COALESCE((SELECT sum(p.amount) FROM payments p WHERE p.cash_shift_id=s.id AND p.method='CASH'),0),
                  counted_amount=s.opening_amount + COALESCE((SELECT sum(p.amount) FROM payments p WHERE p.cash_shift_id=s.id AND p.method='CASH'),0)
                WHERE s.branch_id=:branch AND s.closed_at IS NULL AND s.opened_at < :today
                """).param("branch", demo.branch()).param("today", Timestamp.from(todayStart)).update();
        var history = jdbc.sql("SELECT count(*) FROM cash_shifts WHERE branch_id=:branch AND closed_at IS NOT NULL")
                .param("branch", demo.branch()).query(Integer.class).single();
        if (history == 0) {
            for (var daysAgo = 5; daysAgo >= 1; daysAgo--) {
                var day = LocalDate.now(zone).minusDays(daysAgo);
                var opened = day.atTime(11, 30).atZone(zone).toInstant();
                var closed = day.atTime(23, 10).atZone(zone).toInstant();
                var cash = jdbc.sql("SELECT COALESCE(sum(amount),0) FROM payments WHERE branch_id=:branch AND method='CASH' AND paid_at>=:from AND paid_at<:to")
                        .param("branch", demo.branch()).param("from", Timestamp.from(opened)).param("to", Timestamp.from(closed))
                        .query(BigDecimal.class).single();
                var expected = cash.add(new BigDecimal("200.00"));
                var difference = daysAgo == 3 ? new BigDecimal("-12.50") : daysAgo == 2 ? new BigDecimal("4.00") : BigDecimal.ZERO;
                var shift = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO cash_shifts(id,organization_id,branch_id,opened_by,closed_by,opened_at,closed_at,opening_amount,
                          counted_amount,expected_amount,opening_note,closing_note)
                        VALUES (:id,:org,:branch,:user,:user,:opened,:closed,200,:counted,:expected,'Fondo habitual',:note)
                        """).param("id", shift).param("org", demo.org()).param("branch", demo.branch()).param("user", owner)
                        .param("opened", Timestamp.from(opened)).param("closed", Timestamp.from(closed))
                        .param("counted", expected.add(difference)).param("expected", expected)
                        .param("note", difference.signum() < 0 ? "Se dio vuelto de más en la mesa 4" : difference.signum() > 0 ? "Propina en efectivo no registrada" : null)
                        .update();
                jdbc.sql("UPDATE payments SET cash_shift_id=:shift WHERE branch_id=:branch AND paid_at>=:from AND paid_at<:to AND cash_shift_id IS NULL")
                        .param("shift", shift).param("branch", demo.branch()).param("from", Timestamp.from(opened)).param("to", Timestamp.from(closed)).update();
            }
        }
        var open = jdbc.sql("SELECT id FROM cash_shifts WHERE branch_id=:branch AND closed_at IS NULL")
                .param("branch", demo.branch()).query(UUID.class).optional();
        var shift = open.orElseGet(() -> {
            var id = UUID.randomUUID();
            var opened = Instant.now().minusSeconds(6 * 3600);
            if (opened.isBefore(todayStart)) opened = todayStart;
            jdbc.sql("""
                    INSERT INTO cash_shifts(id,organization_id,branch_id,opened_by,opened_at,opening_amount,opening_note)
                    VALUES (:id,:org,:branch,:user,:opened,200,'Fondo en billetes de 10 y monedas')
                    """).param("id", id).param("org", demo.org()).param("branch", demo.branch()).param("user", owner)
                    .param("opened", Timestamp.from(opened)).update();
            jdbc.sql("""
                    INSERT INTO cash_movements(id,shift_id,kind,amount,reason,registered_by,created_at) VALUES
                      (:a,:shift,'OUT',45,'Compra de limones en el mercado',:user,:t1),
                      (:b,:shift,'IN',50,'Reposición de sencillo',:user,:t2)
                    """).param("a", UUID.randomUUID()).param("b", UUID.randomUUID()).param("shift", id).param("user", owner)
                    .param("t1", Timestamp.from(opened.plusSeconds(3600))).param("t2", Timestamp.from(opened.plusSeconds(7200))).update();
            return id;
        });
        jdbc.sql("""
                UPDATE payments SET cash_shift_id=:shift WHERE branch_id=:branch AND paid_at>=:today AND cash_shift_id IS NULL
                """).param("shift", shift).param("branch", demo.branch()).param("today", Timestamp.from(todayStart)).update();
    }

    private void closedVisit(Context context, Random random, Instant opened, int minutes, boolean rate) {
        var table = context.tables().get(random.nextInt(context.tables().size()));
        var waiter = context.waiters().get(random.nextInt(context.waiters().size()));
        var session = UUID.randomUUID();
        var closed = opened.plusSeconds(minutes * 60L);
        context.sessions().add(new Object[]{session, context.demo().org(), context.demo().branch(), table, "CLOSED",
                UUID.randomUUID(), waiter, Timestamp.from(opened), Timestamp.from(closed), waiter});
        var guest = UUID.randomUUID();
        context.guests().add(new Object[]{guest, session, GUESTS[random.nextInt(GUESTS.length)], true, false, Timestamp.from(opened)});
        var order = UUID.randomUUID();
        var submitted = opened.plusSeconds((3 + random.nextInt(8)) * 60L);
        context.orders().add(new Object[]{order, context.demo().org(), context.demo().branch(), session, 1, "DELIVERED", Timestamp.from(submitted)});
        var total = BigDecimal.ZERO;
        var lines = 2 + random.nextInt(3);
        for (var line = 0; line < lines; line++) {
            var item = pick(context, random);
            var quantity = 1 + (random.nextDouble() < 0.3 ? 1 : 0);
            var ready = submitted.plusSeconds((8 + random.nextInt(14)) * 60L);
            context.items().add(new Object[]{UUID.randomUUID(), order, guest, item.id(), item.name(), item.price(), quantity, "DELIVERED",
                    Timestamp.from(submitted), Timestamp.from(ready)});
            total = total.add(item.price().multiply(BigDecimal.valueOf(quantity)));
            for (var recipe : item.recipe().entrySet()) {
                context.movements().add(new Object[]{context.demo().org(), context.demo().branch(), recipe.getKey(),
                        recipe.getValue().multiply(BigDecimal.valueOf(-quantity)), item.name(), Timestamp.from(submitted)});
            }
        }
        var tip = random.nextDouble() < 0.45 ? total.multiply(BigDecimal.valueOf(0.1)).setScale(0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        context.payments().add(new Object[]{UUID.randomUUID(), context.demo().org(), context.demo().branch(), session,
                random.nextDouble() < 0.5 ? "CARD" : random.nextDouble() < 0.6 ? "YAPE" : "CASH", total, tip, waiter,
                Timestamp.from(closed.minusSeconds(120))});
        // Llamadas atendidas (tiempo de atención) y calificaciones con comentarios.
        if (random.nextDouble() < 0.6) {
            var requested = opened.plusSeconds((10 + random.nextInt(20)) * 60L);
            var speed = context.waiters().indexOf(waiter) == 0 ? 1 : context.waiters().indexOf(waiter) == 1 ? 3 : 5;
            context.requests().add(new Object[]{UUID.randomUUID(), context.demo().org(), context.demo().branch(), session,
                    random.nextBoolean() ? "WAITER_CALL" : "BILL", "RESOLVED", Timestamp.from(requested), waiter,
                    Timestamp.from(requested.plusSeconds((speed + random.nextInt(3)) * 60L)), Timestamp.from(requested.plusSeconds(600))});
        }
        if (rate && random.nextDouble() < 0.35) {
            var stars = random.nextDouble() < 0.7 ? 5 : random.nextDouble() < 0.7 ? 4 : 3;
            var comment = random.nextDouble() < 0.4 ? COMMENTS[random.nextInt(COMMENTS.length)] : null;
            context.ratings().add(new Object[]{UUID.randomUUID(), context.demo().org(), context.demo().branch(), session, guest, waiter,
                    stars, stars >= 4 ? "FRIENDLY,FOOD" : "SLOW", stars >= 4 ? comment : "La comida rica, pero demoró un poco", Timestamp.from(closed)});
        }
    }

    private void openVisit(Context context, UUID table, int minutesAgo, List<Line> lines, String request) {
        var now = Instant.now();
        var opened = now.minusSeconds((minutesAgo + 8L) * 60);
        var waiter = context.waiters().get(0);
        var session = UUID.randomUUID();
        context.sessions().add(new Object[]{session, context.demo().org(), context.demo().branch(), table, "OPEN",
                UUID.randomUUID(), waiter, Timestamp.from(opened), null, null});
        var people = new ArrayList<UUID>();
        for (var index = 0; index < 1 + Math.min(3, lines.size()); index++) {
            var guest = UUID.randomUUID();
            people.add(guest);
            context.guests().add(new Object[]{guest, session, GUESTS[(minutesAgo + index * 7) % GUESTS.length], index == 0, true, Timestamp.from(opened)});
        }
        var byMinutes = new java.util.TreeMap<Integer, List<Line>>(java.util.Comparator.reverseOrder());
        for (var line : lines) byMinutes.computeIfAbsent(line.minutesAgo(), key -> new ArrayList<>()).add(line);
        var round = 1;
        for (var entry : byMinutes.entrySet()) {
            var order = UUID.randomUUID();
            var submitted = now.minusSeconds(entry.getKey() * 60L);
            var statuses = entry.getValue().stream().map(Line::status).toList();
            var orderStatus = statuses.stream().allMatch("DELIVERED"::equals) ? "DELIVERED"
                    : statuses.stream().allMatch(status -> status.equals("READY") || status.equals("DELIVERED")) ? "READY"
                    : statuses.contains("PREPARING") || statuses.contains("READY") ? "PREPARING" : "SUBMITTED";
            context.orders().add(new Object[]{order, context.demo().org(), context.demo().branch(), session, round++, orderStatus, Timestamp.from(submitted)});
            var guestIndex = 0;
            for (var line : entry.getValue()) {
                var item = context.menu().stream().filter(candidate -> candidate.name().equals(line.name())).findFirst().orElseThrow();
                var ready = line.status().equals("READY") || line.status().equals("DELIVERED")
                        ? Timestamp.from(submitted.plusSeconds(Math.max(1, entry.getKey() - 2) * 60L)) : null;
                context.items().add(new Object[]{UUID.randomUUID(), order, people.get(guestIndex++ % people.size()), item.id(), item.name(),
                        item.price(), line.quantity(), line.status(), Timestamp.from(submitted), ready});
            }
        }
        if (request != null) {
            context.requests().add(new Object[]{UUID.randomUUID(), context.demo().org(), context.demo().branch(), session, request,
                    "PENDING", Timestamp.from(now.minusSeconds(90)), null, null, null});
            if (request.equals("BILL")) context.billNotes().add(new Object[]{session});
        }
    }

    private void flush(Context context) {
        template.batchUpdate("""
                INSERT INTO table_sessions(id,organization_id,branch_id,table_id,status,guest_token,opened_by,opened_at,closed_at,closed_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", context.sessions());
        template.batchUpdate("INSERT INTO guests(id,session_id,display_name,is_host,active,created_at,identity_claimed) VALUES (?,?,?,?,?,?,TRUE)",
                context.guests());
        template.batchUpdate("INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status,submitted_at) VALUES (?,?,?,?,?,?,?)",
                context.orders());
        template.batchUpdate("""
                INSERT INTO order_items(id,order_id,guest_id,menu_item_id,item_name,unit_price,quantity,status,created_at,ready_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", context.items());
        template.batchUpdate("""
                INSERT INTO payments(id,organization_id,branch_id,session_id,method,amount,tip,registered_by,paid_at)
                VALUES (?,?,?,?,?,?,?,?,?)""", context.payments());
        template.batchUpdate("""
                INSERT INTO service_requests(id,organization_id,branch_id,session_id,type,status,requested_at,handled_by,attended_at,handled_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", context.requests());
        template.batchUpdate("""
                INSERT INTO service_ratings(id,organization_id,branch_id,session_id,guest_id,waiter_id,stars,highlights,comment,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", context.ratings());
        template.batchUpdate("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,reference,created_at)
                VALUES (?,?,?,'SALE',?,?,?)""", context.movements());
        template.batchUpdate("""
                UPDATE table_sessions SET bill_split_mode='GUEST',bill_tip_percent=10,
                  bill_note='3 pagos separados (cada uno lo suyo) · propina 10 %' WHERE id=?""", context.billNotes());
        context.clear();
    }

    // ——— Utilidades de creación ———

    private Context context(Demo demo) {
        var tables = jdbc.sql("SELECT id FROM dining_tables WHERE branch_id=:branch AND active ORDER BY label")
                .param("branch", demo.branch()).query(UUID.class).list();
        var waiters = jdbc.sql("SELECT id FROM app_users WHERE branch_id=:branch AND role='WAITER' AND active ORDER BY full_name")
                .param("branch", demo.branch()).query(UUID.class).list();
        var recipes = new java.util.HashMap<UUID, Map<UUID, BigDecimal>>();
        jdbc.sql("SELECT menu_item_id,ingredient_id,quantity FROM recipe_lines r JOIN menu_items m ON m.id=r.menu_item_id WHERE m.organization_id=:org")
                .param("org", demo.org()).query((rs, row) -> {
                    recipes.computeIfAbsent(rs.getObject("menu_item_id", UUID.class), key -> new java.util.HashMap<>())
                            .put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("quantity"));
                    return null;
                }).list();
        var menu = jdbc.sql("SELECT id,name,price FROM menu_items WHERE organization_id=:org AND active ORDER BY name")
                .param("org", demo.org())
                .query((rs, row) -> new MenuEntry(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("price"),
                        recipes.getOrDefault(rs.getObject("id", UUID.class), Map.of()))).list();
        return new Context(demo, tables, waiters, menu);
    }

    /** Los más vendidos salen más seguido, como en un restaurante real. */
    private MenuEntry pick(Context context, Random random) {
        var popular = List.of("Ceviche clásico", "Lomo saltado", "Chicha morada", "Pisco sour");
        if (random.nextDouble() < 0.45) {
            var name = popular.get(random.nextInt(popular.size()));
            return context.menu().stream().filter(item -> item.name().equals(name)).findFirst().orElse(context.menu().get(0));
        }
        return context.menu().get(random.nextInt(context.menu().size()));
    }

    private void user(Demo demo, String name, String email, String role, String password) {
        jdbc.sql("""
                INSERT INTO app_users(id,organization_id,branch_id,full_name,email,password_hash,role)
                VALUES (:id,:org,:branch,:name,:email,:password,:role)
                """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch())
                .param("name", name).param("email", email).param("password", password).param("role", role).update();
    }

    private UUID zone(Demo demo, String name, String color, int order) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO dining_zones(id,organization_id,branch_id,name,color,display_order) VALUES (:id,:org,:branch,:name,:color,:order)")
                .param("id", id).param("org", demo.org()).param("branch", demo.branch()).param("name", name)
                .param("color", color).param("order", order).update();
        return id;
    }

    private void table(Demo demo, UUID zone, String label, int capacity, int x, int y, String shape) {
        jdbc.sql("""
                INSERT INTO dining_tables(organization_id,branch_id,label,capacity,zone_id,plan_x,plan_y,plan_shape)
                VALUES (:org,:branch,:label,:capacity,:zone,:x,:y,:shape)
                """).param("org", demo.org()).param("branch", demo.branch()).param("label", label).param("capacity", capacity)
                .param("zone", zone).param("x", x).param("y", y).param("shape", shape).update();
    }

    private void element(Demo demo, UUID zone, String kind, String label, int x, int y, int width, int height) {
        jdbc.sql("""
                INSERT INTO floor_elements(id,organization_id,branch_id,zone_id,kind,label,x,y,width,height)
                VALUES (:id,:org,:branch,:zone,:kind,:label,:x,:y,:w,:h)
                """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch()).param("zone", zone)
                .param("kind", kind).param("label", label).param("x", x).param("y", y).param("w", width).param("h", height).update();
    }

    private UUID station(Demo demo, String name, int order) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO kitchen_stations(id,organization_id,name,display_order) VALUES (:id,:org,:name,:order)")
                .param("id", id).param("org", demo.org()).param("name", name).param("order", order).update();
        return id;
    }

    private UUID category(Demo demo, String name, int order, UUID station) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO menu_categories(id,organization_id,name,display_order,station_id) VALUES (:id,:org,:name,:order,:station)")
                .param("id", id).param("org", demo.org()).param("name", name).param("order", order).param("station", station).update();
        return id;
    }

    private UUID ingredient(Demo demo, String name, String unit, String cost, String min, String stock) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO ingredients(id,organization_id,name,unit,cost_per_unit,min_stock,supplier_name,supplier_phone) VALUES (:id,:org,:name,:unit,:cost,:min,:supplier,NULL)")
                .param("id", id).param("org", demo.org()).param("name", name).param("unit", unit).param("cost", new BigDecimal(cost))
                .param("min", new BigDecimal(min)).param("supplier", name.equals("Pisco quebranta") ? "Bodega Tacama" : "Mercado Mayorista").update();
        jdbc.sql("INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity) VALUES (:branch,:id,:qty)")
                .param("branch", demo.branch()).param("id", id).param("qty", new BigDecimal(stock)).update();
        return id;
    }

    private void item(Demo demo, UUID category, String name, String description, String price, String tags, Map<UUID, String> recipe) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO menu_items(id,organization_id,category_id,name,description,price,active,tags)
                VALUES (:id,:org,:category,:name,:description,:price,TRUE,:tags)
                """).param("id", id).param("org", demo.org()).param("category", category).param("name", name)
                .param("description", description).param("price", new BigDecimal(price)).param("tags", tags).update();
        recipe.forEach((ingredient, quantity) -> jdbc.sql("INSERT INTO recipe_lines(menu_item_id,ingredient_id,quantity) VALUES (:item,:ingredient,:qty)")
                .param("item", id).param("ingredient", ingredient).param("qty", new BigDecimal(quantity)).update());
    }

    private void waitlist(Demo demo, String name, int party, int minutesAgo) {
        jdbc.sql("""
                INSERT INTO waitlist_entries(id,organization_id,branch_id,public_token,customer_name,phone,party_size,created_at)
                VALUES (:id,:org,:branch,:token,:name,NULL,:party,:at)
                """).param("id", UUID.randomUUID()).param("org", demo.org()).param("branch", demo.branch()).param("token", UUID.randomUUID())
                .param("name", name).param("party", party).param("at", Timestamp.from(Instant.now().minusSeconds(minutesAgo * 60L))).update();
    }

    private void stock(Demo demo, String ingredient, String quantity) {
        jdbc.sql("""
                UPDATE ingredient_stock SET quantity=:qty WHERE branch_id=:branch
                  AND ingredient_id=(SELECT id FROM ingredients WHERE organization_id=:org AND name=:name)
                """).param("qty", new BigDecimal(quantity)).param("branch", demo.branch()).param("org", demo.org())
                .param("name", ingredient).update();
    }

    private static final String[] GUESTS = {"Ana", "Jorge", "Camila", "Renzo", "Mariana", "Luis", "Sofía", "Andrés", "Valentina", "Miguel", "Rosa", "Pedro"};
    private static final String[] COMMENTS = {"¡El ceviche espectacular! Volveremos.", "Muy buena atención, súper amables.",
            "El lomo saltado estaba en su punto.", "Rápidos y atentos, gracias.", "Excelente pisco sour."};

    /** Opciones y extras realistas en algunos platos (una sola vez por demo). */
    private void modifiers(Demo demo) {
        var exists = jdbc.sql("SELECT count(*) FROM menu_modifier_groups WHERE organization_id=:org").param("org", demo.org()).query(Integer.class).single();
        if (exists > 0) return;
        modifierGroup(demo, "Lomo saltado", "Término de la carne", 1, 1, 0, new String[][]{{"Jugoso", "0"}, {"A punto", "0"}, {"Bien cocido", "0"}});
        modifierGroup(demo, "Lomo saltado", "Agrégale", 0, 3, 1, new String[][]{{"Huevo frito", "3.00"}, {"Plátano frito", "4.00"}, {"Porción de arroz", "5.00"}, {"Papas extra", "6.00"}});
        modifierGroup(demo, "Ceviche clásico", "¿Qué tan picante?", 1, 1, 0, new String[][]{{"Sin ají", "0"}, {"Normal", "0"}, {"Bien picante", "0"}});
        modifierGroup(demo, "Ceviche clásico", "Acompañamientos extra", 0, 2, 1, new String[][]{{"Chicharrón de pescado", "9.00"}, {"Choclo extra", "3.00"}, {"Camote extra", "3.00"}});
        modifierGroup(demo, "Pisco sour", "Tamaño", 1, 1, 0, new String[][]{{"Normal", "0"}, {"Catedral", "10.00"}});
        modifierGroup(demo, "Anticuchos", "Salsa", 0, 2, 0, new String[][]{{"Ají de pollería", "0"}, {"Huacatay", "0"}, {"Rocoto", "0"}});
    }

    private void modifierGroup(Demo demo, String itemName, String name, int min, int max, int order, String[][] options) {
        var item = jdbc.sql("SELECT id FROM menu_items WHERE organization_id=:org AND name=:name LIMIT 1")
                .param("org", demo.org()).param("name", itemName).query(UUID.class).optional();
        if (item.isEmpty()) return;
        var group = UUID.randomUUID();
        jdbc.sql("INSERT INTO menu_modifier_groups(id,organization_id,menu_item_id,name,min_select,max_select,display_order) VALUES (:id,:org,:item,:name,:min,:max,:order)")
                .param("id", group).param("org", demo.org()).param("item", item.get()).param("name", name)
                .param("min", min).param("max", max).param("order", order).update();
        for (var index = 0; index < options.length; index++) {
            jdbc.sql("INSERT INTO menu_modifier_options(id,group_id,name,price_delta,display_order) VALUES (:id,:group,:name,:price,:order)")
                    .param("id", UUID.randomUUID()).param("group", group).param("name", options[index][0])
                    .param("price", new BigDecimal(options[index][1])).param("order", index).update();
        }
    }

    private record Demo(UUID org, UUID branch) {}
    private record MenuEntry(UUID id, String name, BigDecimal price, Map<UUID, BigDecimal> recipe) {}
    private record Line(String name, int quantity, String status, int minutesAgo) {}
    private record Context(Demo demo, List<UUID> tables, List<UUID> waiters, List<MenuEntry> menu, List<Object[]> sessions,
                           List<Object[]> guests, List<Object[]> orders, List<Object[]> items, List<Object[]> payments,
                           List<Object[]> requests, List<Object[]> ratings, List<Object[]> movements, List<Object[]> billNotes) {
        Context(Demo demo, List<UUID> tables, List<UUID> waiters, List<MenuEntry> menu) {
            this(demo, tables, waiters, menu, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        void clear() {
            sessions.clear(); guests.clear(); orders.clear(); items.clear(); payments.clear(); requests.clear(); ratings.clear();
            movements.clear(); billNotes.clear();
        }
    }
    public record DemoAccess(JwtService.TokenResponse session, UUID dinerQr) {}
}
