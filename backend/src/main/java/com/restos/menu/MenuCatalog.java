package com.restos.menu;

import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Lectura de la carta compartida por comensales y personal: disponibilidad por sucursal y opciones. */
@Component
public class MenuCatalog {
    /** Etiquetas permitidas en la carta. */
    public static final List<String> TAGS = List.of("SPICY", "VEGAN", "VEGETARIAN", "GLUTEN_FREE", "NEW", "CHEF");
    private final JdbcClient jdbc;
    private final String zone;

    public MenuCatalog(JdbcClient jdbc,
                       @org.springframework.beans.factory.annotation.Value("${restos.business-zone:America/Lima}") String zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    public static List<String> parseTags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(TAGS::contains).toList();
    }

    public static String joinTags(List<String> tags) {
        if (tags == null) return "";
        return String.join(",", tags.stream().filter(TAGS::contains).distinct().toList());
    }

    /** Productos visibles de la organización con su estado de agotado en la sucursal indicada. */
    public List<MenuItemView> menu(UUID organizationId, UUID branchId) {
        var groups = modifierGroups(organizationId);
        return jdbc.sql("""
                        WITH sold AS (
                          SELECT oi.menu_item_id,
                                 sum(oi.quantity) FILTER (WHERE o.submitted_at >= date_trunc('day', NOW() AT TIME ZONE :zone) AT TIME ZONE :zone) today,
                                 sum(oi.quantity) week
                          FROM order_items oi JOIN orders o ON o.id=oi.order_id
                          WHERE o.branch_id=:branch AND o.submitted_at >= NOW() - INTERVAL '7 days' AND oi.status<>'CANCELLED'
                          GROUP BY oi.menu_item_id
                        ), ranked AS (
                          SELECT menu_item_id,today,week,rank() OVER (ORDER BY week DESC) week_rank FROM sold
                        )
                        SELECT mi.id,mi.category_id,mc.name category_name,mi.name,mi.description,mi.price,mi.image_url,mi.tags,
                               NOT EXISTS (SELECT 1 FROM menu_item_unavailability u
                                           WHERE u.menu_item_id=mi.id AND u.branch_id=:branch) available,
                               COALESCE(r.today,0) sold_today,
                               COALESCE(r.week_rank <= 3 AND r.week >= 3, FALSE) popular
                        FROM menu_items mi
                        JOIN menu_categories mc ON mc.id=mi.category_id AND mc.active
                        LEFT JOIN ranked r ON r.menu_item_id=mi.id
                        WHERE mi.organization_id=:org AND mi.active
                        ORDER BY mc.display_order,mc.name,mi.name
                        """).param("org", organizationId).param("branch", branchId).param("zone", zone)
                .query((rs, row) -> {
                    var id = rs.getObject("id", UUID.class);
                    return new MenuItemView(id, rs.getObject("category_id", UUID.class),
                            rs.getString("category_name"), rs.getString("name"), rs.getString("description"),
                            rs.getBigDecimal("price"), rs.getString("image_url"), rs.getBoolean("available"),
                            groups.getOrDefault(id, List.of()), parseTags(rs.getString("tags")),
                            rs.getBoolean("popular"), rs.getInt("sold_today"));
                }).list();
    }

    /** Grupos de opciones por producto, ordenados como se configuraron en la carta. */
    public Map<UUID, List<ModifierGroupView>> modifierGroups(UUID organizationId) {
        var rows = jdbc.sql("""
                        SELECT g.id group_id,g.menu_item_id,g.name group_name,g.min_select,g.max_select,
                               o.id option_id,o.name option_name,o.price_delta,o.ingredient_id,o.ingredient_quantity
                        FROM menu_modifier_groups g
                        LEFT JOIN menu_modifier_options o ON o.group_id=g.id
                        WHERE g.organization_id=:org
                        ORDER BY g.menu_item_id,g.display_order,g.name,o.display_order,o.name
                        """).param("org", organizationId)
                .query((rs, row) -> new ModifierRow(rs.getObject("group_id", UUID.class),
                        rs.getObject("menu_item_id", UUID.class), rs.getString("group_name"),
                        rs.getInt("min_select"), rs.getInt("max_select"), rs.getObject("option_id", UUID.class),
                        rs.getString("option_name"), rs.getBigDecimal("price_delta"),
                        rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("ingredient_quantity"))).list();
        var groups = new LinkedHashMap<UUID, ModifierGroupBuilder>();
        for (var row : rows) {
            var group = groups.computeIfAbsent(row.groupId(), id -> new ModifierGroupBuilder(row));
            if (row.optionId() != null) {
                group.options.add(new ModifierOptionView(row.optionId(), row.optionName(), row.priceDelta(),
                        row.ingredientId(), row.ingredientQuantity()));
            }
        }
        var result = new LinkedHashMap<UUID, List<ModifierGroupView>>();
        for (var group : groups.values()) {
            result.computeIfAbsent(group.menuItemId, id -> new ArrayList<>()).add(group.build());
        }
        return result;
    }

    /**
     * Valida un producto pedible en la sucursal y las opciones elegidas.
     * El precio unitario resultante incluye los recargos de los extras.
     */
    public PricedItem price(UUID organizationId, UUID branchId, UUID menuItemId, List<UUID> optionIds) {
        var item = jdbc.sql("""
                        SELECT mi.id,mi.name,mi.price,
                               NOT EXISTS (SELECT 1 FROM menu_item_unavailability u
                                           WHERE u.menu_item_id=mi.id AND u.branch_id=:branch) available
                        FROM menu_items mi JOIN menu_categories mc ON mc.id=mi.category_id AND mc.active
                        WHERE mi.id=:item AND mi.organization_id=:org AND mi.active
                        """).param("item", menuItemId).param("org", organizationId).param("branch", branchId)
                .query((rs, row) -> new BaseItem(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBigDecimal("price"), rs.getBoolean("available"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "El producto no está disponible"));
        if (!item.available()) {
            throw new ApiException(HttpStatus.CONFLICT, item.name() + " está agotado por hoy");
        }
        var requested = optionIds == null ? List.<UUID>of() : optionIds;
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Hay opciones repetidas en el pedido");
        }
        var groups = modifierGroups(organizationId).getOrDefault(menuItemId, List.of());
        var selected = new ArrayList<SelectedModifier>();
        var matched = 0;
        var order = 0;
        for (var group : groups) {
            var chosen = group.options().stream().filter(option -> requested.contains(option.id())).toList();
            if (chosen.size() < group.minSelect() || chosen.size() > group.maxSelect()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, group.minSelect() == group.maxSelect()
                        ? "Elige " + group.minSelect() + " opción(es) en «" + group.name() + "»"
                        : "Elige entre " + group.minSelect() + " y " + group.maxSelect()
                                + " opciones en «" + group.name() + "»");
            }
            for (var option : chosen) {
                selected.add(new SelectedModifier(group.name(), option.name(), option.priceDelta(), order++,
                        option.ingredientId(), option.ingredientQuantity()));
            }
            matched += chosen.size();
        }
        if (matched != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Alguna opción ya no pertenece a " + item.name());
        }
        var unitPrice = selected.stream().map(SelectedModifier::priceDelta).reduce(item.price(), BigDecimal::add);
        return new PricedItem(item.id(), item.name(), unitPrice, selected);
    }

    /** Guarda la copia inmutable de las opciones elegidas para un plato del pedido. */
    public void saveSelection(UUID orderItemId, List<SelectedModifier> modifiers) {
        for (var modifier : modifiers) {
            jdbc.sql("""
                    INSERT INTO order_item_modifiers(order_item_id,group_name,option_name,price_delta,display_order,
                      ingredient_id,ingredient_quantity)
                    VALUES (:item,:group,:option,:delta,:order,:ingredient,:quantity)
                    """).param("item", orderItemId).param("group", modifier.groupName())
                    .param("option", modifier.optionName()).param("delta", modifier.priceDelta())
                    .param("order", modifier.displayOrder()).param("ingredient", modifier.ingredientId())
                    .param("quantity", modifier.ingredientQuantity()).update();
        }
    }

    /** Copia las opciones al dividir un plato, por ejemplo al anular solo parte de las unidades. */
    public void copySelection(UUID sourceItemId, UUID targetItemId) {
        jdbc.sql("""
                INSERT INTO order_item_modifiers(order_item_id,group_name,option_name,price_delta,display_order,
                  ingredient_id,ingredient_quantity)
                SELECT :target,group_name,option_name,price_delta,display_order,ingredient_id,ingredient_quantity
                FROM order_item_modifiers WHERE order_item_id=:source
                """).param("target", targetItemId).param("source", sourceItemId).update();
    }

    private static final class ModifierGroupBuilder {
        private final UUID id;
        private final UUID menuItemId;
        private final String name;
        private final int minSelect;
        private final int maxSelect;
        private final List<ModifierOptionView> options = new ArrayList<>();

        private ModifierGroupBuilder(ModifierRow row) {
            this.id = row.groupId();
            this.menuItemId = row.menuItemId();
            this.name = row.groupName();
            this.minSelect = row.minSelect();
            this.maxSelect = row.maxSelect();
        }

        private ModifierGroupView build() {
            return new ModifierGroupView(id, name, minSelect, maxSelect, List.copyOf(options));
        }
    }

    private record ModifierRow(UUID groupId, UUID menuItemId, String groupName, int minSelect, int maxSelect,
                               UUID optionId, String optionName, BigDecimal priceDelta, UUID ingredientId,
                               BigDecimal ingredientQuantity) {}
    private record BaseItem(UUID id, String name, BigDecimal price, boolean available) {}
    public record ModifierOptionView(UUID id, String name, BigDecimal priceDelta, UUID ingredientId,
                                     BigDecimal ingredientQuantity) {}
    public record ModifierGroupView(UUID id, String name, int minSelect, int maxSelect,
                                    List<ModifierOptionView> options) {}
    public record MenuItemView(UUID id, UUID categoryId, String categoryName, String name, String description,
                               BigDecimal price, String imageUrl, boolean available,
                               List<ModifierGroupView> modifierGroups, List<String> tags, boolean popular,
                               int soldToday) {}
    public record SelectedModifier(String groupName, String optionName, BigDecimal priceDelta, int displayOrder,
                                   UUID ingredientId, BigDecimal ingredientQuantity) {}
    public record PricedItem(UUID menuItemId, String name, BigDecimal unitPrice, List<SelectedModifier> modifiers) {}
}
