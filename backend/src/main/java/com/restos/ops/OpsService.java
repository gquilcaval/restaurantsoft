package com.restos.ops;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Operación proactiva: demoras por estación de cocina, pronóstico de insumos que se acaban hoy y la
 * lista de compras sugerida para enviar al proveedor.
 */
@Service
public class OpsService {
    /** Minutos de espera a partir de los cuales un plato se considera atrasado. */
    private static final int LATE_MINUTES = 15;
    private static final int BUSY_MINUTES = 10;
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final ZoneId zone;

    public OpsService(JdbcClient jdbc, CurrentUser currentUser, @Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.zone = ZoneId.of(zone);
    }

    /** Estado de cada estación: cola actual, espera promedio, tiempo real de preparación hoy y mesas afectadas. */
    public StationBoard stations() {
        var branch = currentUser.require().branchId();
        var start = Timestamp.from(LocalDate.now(zone).atStartOfDay(zone).toInstant());
        var rows = jdbc.sql("""
                        WITH items AS (
                          SELECT COALESCE(c.station_id::text,'') station,oi.status,oi.quantity,o.submitted_at,oi.ready_at,
                                 COALESCE(t.label,ts.label) label
                          FROM order_items oi JOIN orders o ON o.id=oi.order_id
                          JOIN table_sessions ts ON ts.id=o.session_id LEFT JOIN dining_tables t ON t.id=ts.table_id
                          JOIN menu_items mi ON mi.id=oi.menu_item_id JOIN menu_categories c ON c.id=mi.category_id
                          WHERE o.branch_id=:branch AND o.submitted_at IS NOT NULL
                            AND (oi.status IN ('SUBMITTED','PREPARING') OR oi.ready_at>=:start)
                        )
                        SELECT i.station,COALESCE(k.name,'Cocina') name,
                          COALESCE(sum(i.quantity) FILTER (WHERE i.status IN ('SUBMITTED','PREPARING')),0) pending,
                          avg(EXTRACT(EPOCH FROM (NOW()-i.submitted_at))/60) FILTER (WHERE i.status IN ('SUBMITTED','PREPARING')) wait_avg,
                          max(EXTRACT(EPOCH FROM (NOW()-i.submitted_at))/60) FILTER (WHERE i.status IN ('SUBMITTED','PREPARING')) wait_max,
                          avg(EXTRACT(EPOCH FROM (i.ready_at-i.submitted_at))/60) FILTER (WHERE i.ready_at>=:start) prep_avg,
                          COALESCE(string_agg(DISTINCT i.label,', ') FILTER (WHERE i.status IN ('SUBMITTED','PREPARING')
                            AND i.submitted_at < NOW() - (:late * INTERVAL '1 minute')),'') late_tables
                        FROM items i LEFT JOIN kitchen_stations k ON k.id::text=i.station
                        GROUP BY i.station,k.name ORDER BY 2
                        """).param("branch", branch).param("start", start).param("late", LATE_MINUTES)
                .query((rs, row) -> {
                    var waitAvg = rs.getObject("wait_avg") == null ? 0 : (int) Math.round(rs.getDouble("wait_avg"));
                    var waitMax = rs.getObject("wait_max") == null ? 0 : (int) Math.round(rs.getDouble("wait_max"));
                    var prep = rs.getObject("prep_avg") == null ? null : Math.round(rs.getDouble("prep_avg") * 10) / 10.0;
                    var late = rs.getString("late_tables");
                    var status = waitAvg >= LATE_MINUTES || waitMax >= LATE_MINUTES + 5 ? "LATE"
                            : waitAvg >= BUSY_MINUTES ? "BUSY" : "OK";
                    return new StationStatus(rs.getString("station"), rs.getString("name"), rs.getInt("pending"), waitAvg,
                            waitMax, prep, status, late == null || late.isBlank() ? List.of() : List.of(late.split(", ")));
                }).list();
        var alerts = new ArrayList<OpsAlert>();
        for (var station : rows) {
            if (!station.status().equals("LATE")) continue;
            var tables = station.lateTables().isEmpty() ? "" : ". Avisa a " + join(station.lateTables());
            alerts.add(new OpsAlert("STATION_LATE:" + station.id(), "warning", "⏱",
                    station.name() + " está atrasada",
                    station.name() + " lleva " + station.waitAverage() + " min de espera en promedio" + tables + "."));
        }
        var clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", new java.util.Locale("es", "PE")).withZone(zone);
        for (var item : forecast()) {
            if (item.level().equals("OK")) continue;
            alerts.add(new OpsAlert("STOCK:" + item.ingredientId() + ":" + item.level(), item.level().equals("OUT") ? "error" : "warning",
                    "📦", item.level().equals("OUT") ? item.name() + " se agotó" : item.name() + " se acaba hoy",
                    item.level().equals("OUT") ? "Ya no queda stock de " + item.name().toLowerCase() + ". Revisa los platos que lo usan."
                            : "Al ritmo de hoy, " + item.name().toLowerCase() + " se acaba alrededor de las "
                            + clock.format(item.runsOutAt()).replace(".", "") + "."));
        }
        return new StationBoard(rows, alerts, Instant.now());
    }

    /** Al ritmo de consumo de hoy, cuándo se acaba cada insumo. */
    public List<StockForecast> forecast() {
        var scope = currentUser.require();
        var start = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        var closing = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
        var now = Instant.now();
        return jdbc.sql("""
                        SELECT i.id,i.name,i.unit,i.min_stock,COALESCE(s.quantity,0) stock,
                          -sum(m.quantity) consumed,min(m.created_at) first_sale
                        FROM stock_movements m JOIN ingredients i ON i.id=m.ingredient_id
                        LEFT JOIN ingredient_stock s ON s.ingredient_id=i.id AND s.branch_id=m.branch_id
                        WHERE m.branch_id=:branch AND m.kind IN ('SALE','VOID_RETURN') AND m.created_at>=:start AND i.active
                        GROUP BY i.id,i.name,i.unit,i.min_stock,s.quantity
                        HAVING -sum(m.quantity) > 0
                        """).param("branch", scope.branchId()).param("start", Timestamp.from(start))
                .query((rs, row) -> {
                    var stock = rs.getBigDecimal("stock");
                    var consumed = rs.getBigDecimal("consumed");
                    var first = rs.getTimestamp("first_sale").toInstant();
                    // Al menos una hora de referencia evita proyectar con el primer plato del día.
                    var hours = Math.max(1.0, (now.toEpochMilli() - first.toEpochMilli()) / 3_600_000.0);
                    var perHour = consumed.doubleValue() / hours;
                    Instant runsOut = null;
                    if (perHour > 0 && stock.signum() > 0) runsOut = now.plusSeconds((long) (stock.doubleValue() / perHour * 3600));
                    if (stock.signum() <= 0) runsOut = now;
                    var level = runsOut != null && runsOut.isBefore(closing) ? (stock.signum() <= 0 ? "OUT" : "TODAY") : "OK";
                    return new StockForecast(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                            stock, consumed, BigDecimal.valueOf(perHour).setScale(3, RoundingMode.HALF_UP), runsOut, level,
                            rs.getBigDecimal("min_stock"));
                }).list().stream()
                .sorted((a, b) -> a.runsOutAt() == null ? 1 : b.runsOutAt() == null ? -1 : a.runsOutAt().compareTo(b.runsOutAt()))
                .toList();
    }

    /**
     * Insumos que conviene comprar: los que están bajo el mínimo o se acabarán hoy. La cantidad sugerida
     * cubre dos días de consumo promedio (o deja el stock en el doble del mínimo).
     */
    public List<ShoppingItem> shoppingList() {
        var scope = currentUser.require();
        var todayRunsOut = forecast().stream().filter(item -> !item.level().equals("OK")).map(StockForecast::ingredientId).toList();
        var since = Timestamp.from(Instant.now().minusSeconds(7L * 24 * 3600));
        return jdbc.sql("""
                        SELECT i.id,i.name,i.unit,i.cost_per_unit,i.min_stock,i.supplier_name,i.supplier_phone,
                          COALESCE(s.quantity,0) stock,
                          COALESCE((SELECT -sum(m.quantity) FROM stock_movements m WHERE m.ingredient_id=i.id
                             AND m.branch_id=:branch AND m.kind IN ('SALE','VOID_RETURN','WASTE') AND m.created_at>=:since),0)/7.0 daily
                        FROM ingredients i LEFT JOIN ingredient_stock s ON s.ingredient_id=i.id AND s.branch_id=:branch
                        WHERE i.organization_id=:org AND i.active
                        ORDER BY i.supplier_name NULLS LAST,lower(i.name)
                        """).param("branch", scope.branchId()).param("org", scope.organizationId()).param("since", since)
                .query((rs, row) -> {
                    var id = rs.getObject("id", UUID.class);
                    var stock = rs.getBigDecimal("stock");
                    var min = rs.getBigDecimal("min_stock");
                    var daily = rs.getBigDecimal("daily").max(BigDecimal.ZERO);
                    var low = min.signum() > 0 && stock.compareTo(min) <= 0;
                    var runsOut = todayRunsOut.contains(id);
                    if (!low && !runsOut) return null;
                    var target = daily.multiply(BigDecimal.valueOf(2)).max(min.multiply(BigDecimal.valueOf(2)));
                    var suggested = target.subtract(stock.max(BigDecimal.ZERO)).max(min.signum() > 0 ? min : BigDecimal.ONE);
                    suggested = rs.getString("unit").equals("UNIDAD") || rs.getString("unit").equals("G") || rs.getString("unit").equals("ML")
                            ? suggested.setScale(0, RoundingMode.CEILING) : suggested.setScale(1, RoundingMode.CEILING);
                    var cost = rs.getBigDecimal("cost_per_unit");
                    return new ShoppingItem(id, rs.getString("name"), rs.getString("unit"), stock, min,
                            daily.setScale(2, RoundingMode.HALF_UP), suggested, cost.multiply(suggested).setScale(2, RoundingMode.HALF_UP),
                            runsOut ? "Se acaba hoy" : "Bajo el mínimo", rs.getString("supplier_name"), rs.getString("supplier_phone"));
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }

    public void updateSupplier(UUID ingredientId, String name, String phone) {
        var scope = currentUser.require();
        var cleanPhone = phone == null ? null : phone.replaceAll("[^0-9+]", "");
        var updated = jdbc.sql("UPDATE ingredients SET supplier_name=:name,supplier_phone=:phone WHERE id=:id AND organization_id=:org")
                .param("name", name == null || name.isBlank() ? null : name.trim())
                .param("phone", cleanPhone == null || cleanPhone.isBlank() ? null : cleanPhone)
                .param("id", ingredientId).param("org", scope.organizationId()).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Insumo no encontrado");
    }

    private String join(List<String> labels) {
        if (labels.size() == 1) return labels.get(0);
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " y " + labels.get(labels.size() - 1);
    }

    public record StationStatus(String id, String name, int pending, int waitAverage, int waitMax, Double prepAverage,
                                String status, List<String> lateTables) {}
    public record OpsAlert(String key, String tone, String icon, String title, String body) {}
    public record StationBoard(List<StationStatus> stations, List<OpsAlert> alerts, Instant generatedAt) {}
    public record StockForecast(UUID ingredientId, String name, String unit, BigDecimal stock, BigDecimal consumedToday,
                                BigDecimal perHour, Instant runsOutAt, String level, BigDecimal minStock) {}
    public record ShoppingItem(UUID ingredientId, String name, String unit, BigDecimal stock, BigDecimal minStock,
                               BigDecimal dailyUse, BigDecimal suggested, BigDecimal estimatedCost, String reason,
                               String supplierName, String supplierPhone) {}
}
