package com.restos.inventory;

import com.restos.audit.AuditService;
import com.restos.menu.AvailabilityService;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventario por sucursal con kardex. Las recetas descuentan insumos cuando la ronda llega a cocina y los
 * devuelven si un plato se anula antes de prepararse. El stock puede quedar negativo: nunca se frena una venta,
 * pero la diferencia queda a la vista para corregirla con un conteo.
 */
@Service
public class InventoryService {
    public static final Set<String> UNITS = Set.of("KG", "G", "L", "ML", "UNIDAD");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ZoneId zone;
    private final AvailabilityService availability;

    public InventoryService(JdbcClient jdbc, CurrentUser currentUser, AuditService audit,
                            @Value("${restos.business-zone:America/Lima}") String zone, AvailabilityService availability) {
        this.availability = availability;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    public List<IngredientView> ingredients() {
        var scope = currentUser.require();
        return jdbc.sql("""
                        SELECT i.id,i.name,i.unit,i.cost_per_unit,i.min_stock,i.active,COALESCE(s.quantity,0) stock,
                          (SELECT count(*) FROM recipe_lines r WHERE r.ingredient_id=i.id) used_in
                        FROM ingredients i
                        LEFT JOIN ingredient_stock s ON s.ingredient_id=i.id AND s.branch_id=:branch
                        WHERE i.organization_id=:org ORDER BY i.active DESC,lower(i.name)
                        """).param("org", scope.organizationId()).param("branch", scope.branchId())
                .query((rs, row) -> {
                    var stock = rs.getBigDecimal("stock");
                    var cost = rs.getBigDecimal("cost_per_unit");
                    var min = rs.getBigDecimal("min_stock");
                    return new IngredientView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit"),
                            cost, min, rs.getBoolean("active"), stock, stock.max(BigDecimal.ZERO).multiply(cost).setScale(2, RoundingMode.HALF_UP),
                            min.signum() > 0 && stock.compareTo(min) <= 0, rs.getInt("used_in"));
                }).list();
    }

    @Transactional
    public IngredientView save(UUID id, String name, String unit, BigDecimal cost, BigDecimal minStock, boolean active) {
        var scope = currentUser.require();
        if (!UNITS.contains(unit)) throw new ApiException(HttpStatus.BAD_REQUEST, "Unidad inválida");
        var duplicate = jdbc.sql("""
                SELECT count(*) FROM ingredients WHERE organization_id=:org AND lower(name)=lower(:name) AND id<>:id
                """).param("org", scope.organizationId()).param("name", name.trim())
                .param("id", id == null ? new UUID(0, 0) : id).query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe un insumo con ese nombre");
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO ingredients(id,organization_id,name,unit,cost_per_unit,min_stock,active)
                    VALUES (:id,:org,:name,:unit,:cost,:min,:active)
                    """).param("id", id).param("org", scope.organizationId()).param("name", name.trim())
                    .param("unit", unit).param("cost", cost).param("min", minStock).param("active", active).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE ingredients SET name=:name,unit=:unit,cost_per_unit=:cost,min_stock=:min,active=:active
                    WHERE id=:id AND organization_id=:org
                    """).param("name", name.trim()).param("unit", unit).param("cost", cost).param("min", minStock)
                    .param("active", active).param("id", id).param("org", scope.organizationId()).update();
            if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Insumo no encontrado");
        }
        var savedId = id;
        return ingredients().stream().filter(item -> item.id().equals(savedId)).findFirst().orElseThrow();
    }

    /** Una compra suma stock y actualiza el costo con promedio ponderado. */
    @Transactional
    public IngredientView purchase(UUID ingredientId, BigDecimal quantity, BigDecimal unitCost, String reference) {
        var scope = currentUser.require();
        var ingredient = lock(ingredientId);
        if (quantity.signum() <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "La cantidad debe ser mayor que cero");
        var stock = stock(ingredientId, scope.branchId()).max(BigDecimal.ZERO);
        var newCost = stock.signum() == 0 ? unitCost
                : stock.multiply(ingredient.cost()).add(quantity.multiply(unitCost))
                        .divide(stock.add(quantity), 4, RoundingMode.HALF_UP);
        jdbc.sql("UPDATE ingredients SET cost_per_unit=:cost WHERE id=:id").param("cost", newCost).param("id", ingredientId).update();
        move(ingredientId, "PURCHASE", quantity, unitCost, normalize(reference));
        audit.record("STOCK_PURCHASE", "Compra de " + plain(quantity) + " " + ingredient.unit().toLowerCase() + " de "
                + ingredient.name() + (normalize(reference) == null ? "" : " · " + reference.trim()),
                quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP), ingredientId);
        return byId(ingredientId);
    }

    @Transactional
    public IngredientView waste(UUID ingredientId, BigDecimal quantity, String reason) {
        var ingredient = lock(ingredientId);
        if (quantity.signum() <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "La cantidad debe ser mayor que cero");
        move(ingredientId, "WASTE", quantity.negate(), ingredient.cost(), reason.trim());
        audit.record("STOCK_WASTE", "Merma de " + plain(quantity) + " " + ingredient.unit().toLowerCase() + " de "
                + ingredient.name() + " · " + reason.trim(), quantity.multiply(ingredient.cost()).setScale(2, RoundingMode.HALF_UP), ingredientId);
        return byId(ingredientId);
    }

    /** El conteo físico fija el stock real y registra la diferencia como ajuste. */
    @Transactional
    public IngredientView count(UUID ingredientId, BigDecimal counted, String note) {
        var scope = currentUser.require();
        var ingredient = lock(ingredientId);
        if (counted.signum() < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "El conteo no puede ser negativo");
        var difference = counted.subtract(stock(ingredientId, scope.branchId()));
        if (difference.signum() != 0) {
            move(ingredientId, "ADJUSTMENT", difference, ingredient.cost(), normalize(note) == null ? "Conteo físico" : note.trim());
            audit.record("STOCK_COUNT", "Conteo de " + ingredient.name() + ": " + plain(counted) + " "
                    + ingredient.unit().toLowerCase() + " (diferencia " + (difference.signum() > 0 ? "+" : "") + plain(difference) + ")",
                    difference.multiply(ingredient.cost()).setScale(2, RoundingMode.HALF_UP), ingredientId);
        }
        return byId(ingredientId);
    }

    public List<MovementView> movements(UUID ingredientId, LocalDate from, LocalDate to) {
        var scope = currentUser.require();
        return jdbc.sql("""
                        SELECT m.id,m.created_at,m.kind,m.quantity,m.unit_cost,m.reference,i.name,i.unit,
                          COALESCE(u.full_name,'Sistema') user_name
                        FROM stock_movements m JOIN ingredients i ON i.id=m.ingredient_id
                        LEFT JOIN app_users u ON u.id=m.created_by
                        WHERE m.branch_id=:branch AND m.created_at>=:start AND m.created_at<:end
                          AND (CAST(:ingredient AS uuid) IS NULL OR m.ingredient_id=CAST(:ingredient AS uuid))
                        ORDER BY m.created_at DESC LIMIT 300
                        """).param("branch", scope.branchId())
                .param("start", from.atStartOfDay(zone).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime())
                .param("ingredient", ingredientId == null ? null : ingredientId.toString())
                .query((rs, row) -> new MovementView(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("kind"), rs.getString("name"), rs.getString("unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"), rs.getString("reference"), rs.getString("user_name"))).list();
    }

    public RecipeView recipe(UUID menuItemId) {
        var scope = currentUser.require();
        var price = jdbc.sql("SELECT price FROM menu_items WHERE id=:id AND organization_id=:org")
                .param("id", menuItemId).param("org", scope.organizationId()).query(BigDecimal.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        var lines = jdbc.sql("""
                        SELECT r.ingredient_id,i.name,i.unit,r.quantity,i.cost_per_unit FROM recipe_lines r
                        JOIN ingredients i ON i.id=r.ingredient_id WHERE r.menu_item_id=:item ORDER BY lower(i.name)
                        """).param("item", menuItemId)
                .query((rs, row) -> new RecipeLine(rs.getObject("ingredient_id", UUID.class), rs.getString("name"),
                        rs.getString("unit"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("quantity").multiply(rs.getBigDecimal("cost_per_unit")).setScale(2, RoundingMode.HALF_UP)))
                .list();
        var cost = lines.stream().map(RecipeLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        var foodCost = price.signum() == 0 ? BigDecimal.ZERO
                : cost.multiply(BigDecimal.valueOf(100)).divide(price, 1, RoundingMode.HALF_UP);
        return new RecipeView(menuItemId, price, lines, cost, price.subtract(cost), foodCost);
    }

    @Transactional
    public RecipeView saveRecipe(UUID menuItemId, List<LineData> lines) {
        var scope = currentUser.require();
        recipe(menuItemId);
        var seen = new HashSet<UUID>();
        for (var line : lines) {
            if (!seen.add(line.ingredientId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Hay insumos repetidos en la receta");
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Cada insumo de la receta necesita una cantidad mayor que cero");
            }
            var exists = jdbc.sql("SELECT count(*) FROM ingredients WHERE id=:id AND organization_id=:org")
                    .param("id", line.ingredientId()).param("org", scope.organizationId()).query(Integer.class).single();
            if (exists == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Insumo inválido en la receta");
        }
        jdbc.sql("DELETE FROM recipe_lines WHERE menu_item_id=:item").param("item", menuItemId).update();
        for (var line : lines) {
            jdbc.sql("INSERT INTO recipe_lines(menu_item_id,ingredient_id,quantity) VALUES (:item,:ingredient,:quantity)")
                    .param("item", menuItemId).param("ingredient", line.ingredientId()).param("quantity", line.quantity()).update();
        }
        availability.syncAutomatic(scope.branchId());
        return recipe(menuItemId);
    }

    /** Descuenta los insumos de toda una ronda recién enviada a cocina. */
    public void consumeOrder(UUID orderId) {
        var userId = currentUser.find().map(CurrentUser.Scope::userId).orElse(null);
        jdbc.sql("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,unit_cost,reference,order_item_id,created_by)
                SELECT o.organization_id,o.branch_id,r.ingredient_id,'SALE',-(r.quantity*oi.quantity),i.cost_per_unit,
                       oi.item_name,oi.id,:user
                FROM orders o JOIN order_items oi ON oi.order_id=o.id
                JOIN recipe_lines r ON r.menu_item_id=oi.menu_item_id JOIN ingredients i ON i.id=r.ingredient_id
                WHERE o.id=:order AND oi.status<>'CANCELLED'
                """).param("order", orderId).param("user", userId).update();
        jdbc.sql("""
                INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity)
                SELECT o.branch_id,r.ingredient_id,-sum(r.quantity*oi.quantity)
                FROM orders o JOIN order_items oi ON oi.order_id=o.id JOIN recipe_lines r ON r.menu_item_id=oi.menu_item_id
                WHERE o.id=:order AND oi.status<>'CANCELLED' GROUP BY o.branch_id,r.ingredient_id
                ON CONFLICT (branch_id,ingredient_id) DO UPDATE SET quantity=ingredient_stock.quantity+EXCLUDED.quantity
                """).param("order", orderId).update();
        // Los extras elegidos (p. ej. «queso extra») también consumen su insumo.
        jdbc.sql("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,unit_cost,reference,order_item_id,created_by)
                SELECT o.organization_id,o.branch_id,m.ingredient_id,'SALE',-(m.ingredient_quantity*oi.quantity),i.cost_per_unit,
                       oi.item_name||' · '||m.option_name,oi.id,:user
                FROM orders o JOIN order_items oi ON oi.order_id=o.id
                JOIN order_item_modifiers m ON m.order_item_id=oi.id JOIN ingredients i ON i.id=m.ingredient_id
                WHERE o.id=:order AND oi.status<>'CANCELLED'
                """).param("order", orderId).param("user", userId).update();
        jdbc.sql("""
                INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity)
                SELECT o.branch_id,m.ingredient_id,-sum(m.ingredient_quantity*oi.quantity)
                FROM orders o JOIN order_items oi ON oi.order_id=o.id JOIN order_item_modifiers m ON m.order_item_id=oi.id
                WHERE o.id=:order AND oi.status<>'CANCELLED' AND m.ingredient_id IS NOT NULL GROUP BY o.branch_id,m.ingredient_id
                ON CONFLICT (branch_id,ingredient_id) DO UPDATE SET quantity=ingredient_stock.quantity+EXCLUDED.quantity
                """).param("order", orderId).update();
        jdbc.sql("SELECT branch_id FROM orders WHERE id=:order").param("order", orderId).query(UUID.class).optional()
                .ifPresent(availability::syncAutomatic);
    }

    /**
     * Devuelve los insumos de unidades anuladas antes de prepararse. Solo si la venta original descontó stock,
     * así un plato enviado antes de cargar su receta no genera stock que nunca salió.
     */
    public void returnVoided(UUID originalItemId, UUID voidedItemId, int quantity) {
        var consumed = jdbc.sql("SELECT count(*) FROM stock_movements WHERE order_item_id=:item AND kind='SALE'")
                .param("item", originalItemId).query(Integer.class).single();
        if (consumed == 0) return;
        var userId = currentUser.find().map(CurrentUser.Scope::userId).orElse(null);
        jdbc.sql("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,unit_cost,reference,order_item_id,created_by)
                SELECT o.organization_id,o.branch_id,r.ingredient_id,'VOID_RETURN',r.quantity*:quantity,i.cost_per_unit,
                       'Anulación: '||oi.item_name,:voided,:user
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                JOIN recipe_lines r ON r.menu_item_id=oi.menu_item_id JOIN ingredients i ON i.id=r.ingredient_id
                WHERE oi.id=:item
                """).param("quantity", quantity).param("voided", voidedItemId).param("user", userId)
                .param("item", originalItemId).update();
        jdbc.sql("""
                INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity)
                SELECT o.branch_id,r.ingredient_id,r.quantity*:quantity
                FROM order_items oi JOIN orders o ON o.id=oi.order_id JOIN recipe_lines r ON r.menu_item_id=oi.menu_item_id
                WHERE oi.id=:item
                ON CONFLICT (branch_id,ingredient_id) DO UPDATE SET quantity=ingredient_stock.quantity+EXCLUDED.quantity
                """).param("quantity", quantity).param("item", originalItemId).update();
        jdbc.sql("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,unit_cost,reference,order_item_id,created_by)
                SELECT o.organization_id,o.branch_id,m.ingredient_id,'VOID_RETURN',m.ingredient_quantity*:quantity,i.cost_per_unit,
                       'Anulación: '||oi.item_name||' · '||m.option_name,:voided,:user
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                JOIN order_item_modifiers m ON m.order_item_id=oi.id JOIN ingredients i ON i.id=m.ingredient_id
                WHERE oi.id=:item
                """).param("quantity", quantity).param("voided", voidedItemId).param("user", userId)
                .param("item", originalItemId).update();
        jdbc.sql("""
                INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity)
                SELECT o.branch_id,m.ingredient_id,sum(m.ingredient_quantity*:quantity)
                FROM order_items oi JOIN orders o ON o.id=oi.order_id JOIN order_item_modifiers m ON m.order_item_id=oi.id
                WHERE oi.id=:item AND m.ingredient_id IS NOT NULL GROUP BY o.branch_id,m.ingredient_id
                ON CONFLICT (branch_id,ingredient_id) DO UPDATE SET quantity=ingredient_stock.quantity+EXCLUDED.quantity
                """).param("quantity", quantity).param("item", originalItemId).update();
        jdbc.sql("SELECT o.branch_id FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE oi.id=:item")
                .param("item", originalItemId).query(UUID.class).optional().ifPresent(availability::syncAutomatic);
    }

    /** Costo de insumos de lo vendido en un periodo (ventas menos devoluciones por anulación). */
    public BigDecimal costOfSales(UUID branchId, Instant start, Instant end) {
        return jdbc.sql("""
                SELECT COALESCE(-sum(quantity*unit_cost),0) FROM stock_movements
                WHERE branch_id=:branch AND kind IN ('SALE','VOID_RETURN') AND created_at>=:start AND created_at<:end
                """).param("branch", branchId).param("start", java.sql.Timestamp.from(start))
                .param("end", java.sql.Timestamp.from(end)).query(BigDecimal.class).single().setScale(2, RoundingMode.HALF_UP);
    }

    private void move(UUID ingredientId, String kind, BigDecimal quantity, BigDecimal unitCost, String reference) {
        var scope = currentUser.require();
        jdbc.sql("""
                INSERT INTO stock_movements(organization_id,branch_id,ingredient_id,kind,quantity,unit_cost,reference,created_by)
                VALUES (:org,:branch,:ingredient,:kind,:quantity,:cost,:reference,:user)
                """).param("org", scope.organizationId()).param("branch", scope.branchId()).param("ingredient", ingredientId)
                .param("kind", kind).param("quantity", quantity).param("cost", unitCost).param("reference", reference)
                .param("user", scope.userId()).update();
        jdbc.sql("""
                INSERT INTO ingredient_stock(branch_id,ingredient_id,quantity) VALUES (:branch,:ingredient,:quantity)
                ON CONFLICT (branch_id,ingredient_id) DO UPDATE SET quantity=ingredient_stock.quantity+EXCLUDED.quantity
                """).param("branch", scope.branchId()).param("ingredient", ingredientId).param("quantity", quantity).update();
        availability.syncAutomatic(scope.branchId());
    }

    private Ingredient lock(UUID ingredientId) {
        var scope = currentUser.require();
        return jdbc.sql("""
                        SELECT name,unit,cost_per_unit FROM ingredients WHERE id=:id AND organization_id=:org FOR UPDATE
                        """).param("id", ingredientId).param("org", scope.organizationId())
                .query((rs, row) -> new Ingredient(rs.getString("name"), rs.getString("unit"), rs.getBigDecimal("cost_per_unit")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Insumo no encontrado"));
    }

    private BigDecimal stock(UUID ingredientId, UUID branchId) {
        return jdbc.sql("SELECT COALESCE((SELECT quantity FROM ingredient_stock WHERE ingredient_id=:id AND branch_id=:branch),0)")
                .param("id", ingredientId).param("branch", branchId).query(BigDecimal.class).single();
    }

    private IngredientView byId(UUID id) {
        return ingredients().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Ingredient(String name, String unit, BigDecimal cost) {}
    public record IngredientView(UUID id, String name, String unit, BigDecimal costPerUnit, BigDecimal minStock,
                                 boolean active, BigDecimal stock, BigDecimal stockValue, boolean low, int usedIn) {}
    public record MovementView(UUID id, Instant createdAt, String kind, String ingredient, String unit,
                               BigDecimal quantity, BigDecimal unitCost, String reference, String userName) {}
    public record RecipeLine(UUID ingredientId, String name, String unit, BigDecimal quantity, BigDecimal cost) {}
    public record RecipeView(UUID menuItemId, BigDecimal price, List<RecipeLine> lines, BigDecimal cost,
                             BigDecimal margin, BigDecimal foodCostPercent) {}
    public record LineData(UUID ingredientId, BigDecimal quantity) {}
}
