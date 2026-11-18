package com.restos.menu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API administrativa para categorías, productos, precios, imágenes y disponibilidad. */
@RestController
@RequestMapping("/api/admin/menu")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class MenuAdminController {
    private final MenuAdminService menu;

    public MenuAdminController(MenuAdminService menu) {
        this.menu = menu;
    }

    @GetMapping("/categories")
    List<MenuAdminService.CategoryView> categories() { return menu.categories(); }

    @PostMapping("/categories")
    MenuAdminService.CategoryView createCategory(@Valid @RequestBody CategoryRequest request) {
        return menu.createCategory(request.name(), request.displayOrder(), request.active(), request.stationId());
    }

    @PatchMapping("/categories/{id}")
    MenuAdminService.CategoryView updateCategory(@PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        return menu.updateCategory(id, request.name(), request.displayOrder(), request.active(), request.stationId());
    }

    @GetMapping("/items")
    List<MenuAdminService.ItemView> items() { return menu.items(); }

    @PostMapping("/items")
    MenuAdminService.ItemView createItem(@Valid @RequestBody ItemRequest request) {
        return menu.createItem(request.toData());
    }

    @PatchMapping("/items/{id}")
    MenuAdminService.ItemView updateItem(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return menu.updateItem(id, request.toData());
    }

    @GetMapping("/items/{id}/modifiers")
    List<MenuCatalog.ModifierGroupView> modifiers(@PathVariable UUID id) { return menu.modifiers(id); }

    @PutMapping("/items/{id}/modifiers")
    List<MenuCatalog.ModifierGroupView> replaceModifiers(@PathVariable UUID id,
            @Valid @RequestBody @Size(max = 10) List<@Valid GroupRequest> groups) {
        return menu.replaceModifiers(id, groups.stream().map(group -> new MenuAdminService.GroupData(
                group.name(), group.minSelect(), group.maxSelect(), group.options().stream()
                        .map(option -> new MenuAdminService.OptionData(option.name(), option.priceDelta(),
                                option.ingredientId(), option.ingredientQuantity())).toList()))
                .toList());
    }

    record OptionRequest(@NotBlank @Size(max = 80) String name,
                         @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal priceDelta,
                         UUID ingredientId, @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal ingredientQuantity) {}
    record GroupRequest(@NotBlank @Size(max = 80) String name, @Min(0) @Max(20) int minSelect,
                        @Min(1) @Max(20) int maxSelect, @NotNull @Size(max = 20) List<@Valid OptionRequest> options) {}

    @GetMapping("/stations")
    List<MenuAdminService.StationView> stations() { return menu.stations(); }

    @PostMapping("/stations")
    MenuAdminService.StationView createStation(@Valid @RequestBody StationRequest request) {
        return menu.saveStation(null, request.name(), request.displayOrder(), request.active());
    }

    @PatchMapping("/stations/{id}")
    MenuAdminService.StationView updateStation(@PathVariable UUID id, @Valid @RequestBody StationRequest request) {
        return menu.saveStation(id, request.name(), request.displayOrder(), request.active());
    }

    record StationRequest(@NotBlank @Size(max = 60) String name, @Min(0) int displayOrder, boolean active) {}
    record CategoryRequest(@NotBlank @Size(max = 80) String name, @Min(0) int displayOrder, UUID stationId,
                           boolean active) {}
    record ItemRequest(@NotNull UUID categoryId, @NotBlank @Size(max = 120) String name,
                       @Size(max = 600) String description, @NotNull @DecimalMin("0.00") BigDecimal price,
                       @Size(max = 500) String imageUrl, boolean active, @Size(max = 6) List<String> tags) {
        MenuAdminService.ItemData toData() {
            return new MenuAdminService.ItemData(categoryId, name, description, price, imageUrl, active, tags);
        }
    }
}
