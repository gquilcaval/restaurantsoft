package com.restos.menu;

import com.restos.audit.AuditService;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mantiene el catálogo de una organización sin exponer productos de otros restaurantes. */
@Service
public class MenuAdminService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final MenuCatalog catalog;
    private final AuditService audit;

    public MenuAdminService(JdbcClient jdbc, CurrentUser currentUser, MenuCatalog catalog, AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.catalog = catalog;
    }

    public List<CategoryView> categories() {
        var organizationId = currentUser.require().organizationId();
        return jdbc.sql("""
                        SELECT id,name,display_order,active,station_id FROM menu_categories
                        WHERE organization_id=:org ORDER BY display_order,name
                        """).param("org", organizationId)
                .query((rs, row) -> new CategoryView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("display_order"), rs.getBoolean("active"), rs.getObject("station_id", UUID.class)))
                .list();
    }

    @Transactional
    public CategoryView createCategory(String name, int displayOrder, boolean active, UUID stationId) {
        var id = UUID.randomUUID();
        var organizationId = currentUser.require().organizationId();
        requireStation(stationId, organizationId);
        jdbc.sql("""
                INSERT INTO menu_categories(id,organization_id,name,display_order,active,station_id)
                VALUES (:id,:org,:name,:displayOrder,:active,:station)
                """).param("id", id).param("org", organizationId).param("name", name.trim())
                .param("displayOrder", displayOrder).param("active", active).param("station", stationId).update();
        return new CategoryView(id, name.trim(), displayOrder, active, stationId);
    }

    @Transactional
    public CategoryView updateCategory(UUID id, String name, int displayOrder, boolean active, UUID stationId) {
        var organizationId = currentUser.require().organizationId();
        requireStation(stationId, organizationId);
        var updated = jdbc.sql("""
                UPDATE menu_categories SET name=:name,display_order=:displayOrder,active=:active,station_id=:station,
                  updated_at=NOW() WHERE id=:id AND organization_id=:org
                """).param("name", name.trim()).param("displayOrder", displayOrder).param("active", active)
                .param("station", stationId).param("id", id).param("org", organizationId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Categoría no encontrada");
        return new CategoryView(id, name.trim(), displayOrder, active, stationId);
    }

    /** Estaciones de preparación de la organización (cocina, barra, postres…). */
    public List<StationView> stations() {
        var organizationId = currentUser.require().organizationId();
        return jdbc.sql("""
                        SELECT id,name,display_order,active FROM kitchen_stations
                        WHERE organization_id=:org ORDER BY display_order,name
                        """).param("org", organizationId)
                .query((rs, row) -> new StationView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("display_order"), rs.getBoolean("active"))).list();
    }

    @Transactional
    public StationView saveStation(UUID id, String name, int displayOrder, boolean active) {
        var organizationId = currentUser.require().organizationId();
        var duplicate = jdbc.sql("""
                SELECT count(*) FROM kitchen_stations WHERE organization_id=:org AND lower(name)=lower(:name)
                  AND id<>:id
                """).param("org", organizationId).param("name", name.trim())
                .param("id", id == null ? new UUID(0, 0) : id).query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe una estación con ese nombre");
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO kitchen_stations(id,organization_id,name,display_order,active)
                    VALUES (:id,:org,:name,:order,:active)
                    """).param("id", id).param("org", organizationId).param("name", name.trim())
                    .param("order", displayOrder).param("active", active).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE kitchen_stations SET name=:name,display_order=:order,active=:active
                    WHERE id=:id AND organization_id=:org
                    """).param("name", name.trim()).param("order", displayOrder).param("active", active)
                    .param("id", id).param("org", organizationId).update();
            if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Estación no encontrada");
        }
        return new StationView(id, name.trim(), displayOrder, active);
    }

    private void requireStation(UUID stationId, UUID organizationId) {
        if (stationId == null) return;
        var exists = jdbc.sql("SELECT count(*) FROM kitchen_stations WHERE id=:id AND organization_id=:org")
                .param("id", stationId).param("org", organizationId).query(Integer.class).single();
        if (exists == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Estación inválida");
    }

    public List<ItemView> items() {
        var scope = currentUser.require();
        var organizationId = scope.organizationId();
        return jdbc.sql("""
                        SELECT mi.id,mi.category_id,mc.name category_name,mi.name,mi.description,mi.price,
                               mi.image_url,mi.active,mi.tags,
                               NOT EXISTS (SELECT 1 FROM menu_item_unavailability u
                                           WHERE u.menu_item_id=mi.id AND u.branch_id=:branch) available,
                               (SELECT count(*) FROM menu_modifier_groups g WHERE g.menu_item_id=mi.id) modifier_groups
                        FROM menu_items mi JOIN menu_categories mc ON mc.id=mi.category_id
                        WHERE mi.organization_id=:org ORDER BY mc.display_order,mi.name
                        """).param("org", organizationId).param("branch", scope.branchId())
                .query((rs, row) -> mapItem(rs)).list();
    }

    @Transactional
    public ItemView createItem(ItemData data) {
        var organizationId = currentUser.require().organizationId();
        var categoryName = requireCategory(data.categoryId(), organizationId);
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO menu_items(id,organization_id,category_id,name,description,price,image_url,active,tags)
                VALUES (:id,:org,:category,:name,:description,:price,:imageUrl,:active,:tags)
                """).param("id", id).param("org", organizationId).param("category", data.categoryId())
                .param("name", data.name().trim()).param("description", normalize(data.description()))
                .param("price", data.price()).param("imageUrl", normalize(data.imageUrl()))
                .param("active", data.active()).param("tags", MenuCatalog.joinTags(data.tags())).update();
        return new ItemView(id, data.categoryId(), categoryName, data.name().trim(),
                normalize(data.description()), data.price(), normalize(data.imageUrl()), data.active(), true, 0,
                MenuCatalog.parseTags(MenuCatalog.joinTags(data.tags())));
    }

    @Transactional
    public ItemView updateItem(UUID id, ItemData data) {
        var organizationId = currentUser.require().organizationId();
        var categoryName = requireCategory(data.categoryId(), organizationId);
        var previousPrice = jdbc.sql("SELECT price FROM menu_items WHERE id=:id AND organization_id=:org")
                .param("id", id).param("org", organizationId).query(BigDecimal.class).optional().orElse(null);
        if (previousPrice != null && previousPrice.compareTo(data.price()) != 0) {
            audit.record("PRICE_CHANGED", data.name().trim() + ": S/ " + previousPrice.toPlainString() + " → S/ "
                    + data.price().toPlainString(), data.price(), id);
        }
        var updated = jdbc.sql("""
                UPDATE menu_items SET category_id=:category,name=:name,description=:description,price=:price,
                  image_url=:imageUrl,active=:active,tags=:tags,updated_at=NOW()
                WHERE id=:id AND organization_id=:org
                """).param("category", data.categoryId()).param("name", data.name().trim())
                .param("description", normalize(data.description())).param("price", data.price())
                .param("imageUrl", normalize(data.imageUrl())).param("active", data.active())
                .param("tags", MenuCatalog.joinTags(data.tags())).param("id", id).param("org", organizationId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado");
        return items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    /** Grupos de opciones de un producto tal como los ve el administrador. */
    public List<MenuCatalog.ModifierGroupView> modifiers(UUID itemId) {
        var organizationId = currentUser.require().organizationId();
        requireItem(itemId, organizationId);
        return catalog.modifierGroups(organizationId).getOrDefault(itemId, List.of());
    }

    /**
     * Reemplaza la configuración completa de opciones del producto. Los pedidos existentes guardan una
     * copia de lo elegido, por eso borrar o renombrar opciones no altera el historial.
     */
    @Transactional
    public List<MenuCatalog.ModifierGroupView> replaceModifiers(UUID itemId, List<GroupData> groups) {
        var organizationId = currentUser.require().organizationId();
        requireItem(itemId, organizationId);
        jdbc.sql("DELETE FROM menu_modifier_groups WHERE menu_item_id=:item").param("item", itemId).update();
        var groupOrder = 0;
        for (var group : groups) {
            if (group.options().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El grupo «" + group.name().trim() + "» no tiene opciones");
            }
            if (group.minSelect() > group.maxSelect()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "En «" + group.name().trim() + "» el mínimo no puede superar al máximo");
            }
            if (group.minSelect() > group.options().size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "«" + group.name().trim() + "» exige más opciones de las que tiene");
            }
            var groupId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO menu_modifier_groups(id,organization_id,menu_item_id,name,min_select,max_select,display_order)
                    VALUES (:id,:org,:item,:name,:min,:max,:order)
                    """).param("id", groupId).param("org", organizationId).param("item", itemId)
                    .param("name", group.name().trim()).param("min", group.minSelect())
                    .param("max", Math.min(group.maxSelect(), group.options().size()))
                    .param("order", groupOrder++).update();
            var optionOrder = 0;
            for (var option : group.options()) {
                var usesIngredient = option.ingredientId() != null && option.ingredientQuantity() != null
                        && option.ingredientQuantity().signum() > 0;
                if (usesIngredient) {
                    var exists = jdbc.sql("SELECT count(*) FROM ingredients WHERE id=:id AND organization_id=:org")
                            .param("id", option.ingredientId()).param("org", organizationId).query(Integer.class).single();
                    if (exists == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Insumo inválido en «" + option.name().trim() + "»");
                }
                jdbc.sql("""
                        INSERT INTO menu_modifier_options(id,group_id,name,price_delta,display_order,ingredient_id,ingredient_quantity)
                        VALUES (:id,:group,:name,:delta,:order,:ingredient,:quantity)
                        """).param("id", UUID.randomUUID()).param("group", groupId).param("name", option.name().trim())
                        .param("delta", option.priceDelta() == null ? BigDecimal.ZERO : option.priceDelta())
                        .param("order", optionOrder++).param("ingredient", usesIngredient ? option.ingredientId() : null)
                        .param("quantity", usesIngredient ? option.ingredientQuantity() : null).update();
            }
        }
        return catalog.modifierGroups(organizationId).getOrDefault(itemId, List.of());
    }

    private void requireItem(UUID itemId, UUID organizationId) {
        var exists = jdbc.sql("SELECT count(*) FROM menu_items WHERE id=:id AND organization_id=:org")
                .param("id", itemId).param("org", organizationId).query(Integer.class).single();
        if (exists == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado");
    }

    private String requireCategory(UUID categoryId, UUID organizationId) {
        return jdbc.sql("SELECT name FROM menu_categories WHERE id=:id AND organization_id=:org")
                .param("id", categoryId).param("org", organizationId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Categoría inválida"));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ItemView mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemView(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
                rs.getString("category_name"), rs.getString("name"), rs.getString("description"),
                rs.getBigDecimal("price"), rs.getString("image_url"), rs.getBoolean("active"),
                rs.getBoolean("available"), rs.getInt("modifier_groups"), MenuCatalog.parseTags(rs.getString("tags")));
    }

    public record CategoryView(UUID id, String name, int displayOrder, boolean active, UUID stationId) {}
    public record StationView(UUID id, String name, int displayOrder, boolean active) {}
    public record ItemData(UUID categoryId, String name, String description, BigDecimal price,
                           String imageUrl, boolean active, List<String> tags) {}
    public record ItemView(UUID id, UUID categoryId, String categoryName, String name, String description,
                           BigDecimal price, String imageUrl, boolean active, boolean available,
                           int modifierGroups, List<String> tags) {}
    public record OptionData(String name, BigDecimal priceDelta, UUID ingredientId, BigDecimal ingredientQuantity) {}
    public record GroupData(String name, int minSelect, int maxSelect, List<OptionData> options) {}
}
