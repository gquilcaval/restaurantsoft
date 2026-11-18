package com.restos.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Insumos, compras, mermas, conteos y recetas de la sucursal actual. */
@RestController
@RequestMapping("/api/admin/inventory")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/ingredients")
    List<InventoryService.IngredientView> ingredients() { return inventory.ingredients(); }

    @PostMapping("/ingredients")
    InventoryService.IngredientView create(@Valid @RequestBody IngredientRequest request) {
        return inventory.save(null, request.name(), request.unit(), request.costPerUnit(), request.minStock(), request.active());
    }

    @PatchMapping("/ingredients/{id}")
    InventoryService.IngredientView update(@PathVariable UUID id, @Valid @RequestBody IngredientRequest request) {
        return inventory.save(id, request.name(), request.unit(), request.costPerUnit(), request.minStock(), request.active());
    }

    @PostMapping("/ingredients/{id}/purchases")
    InventoryService.IngredientView purchase(@PathVariable UUID id, @Valid @RequestBody PurchaseRequest request) {
        return inventory.purchase(id, request.quantity(), request.unitCost(), request.reference());
    }

    @PostMapping("/ingredients/{id}/waste")
    InventoryService.IngredientView waste(@PathVariable UUID id, @Valid @RequestBody WasteRequest request) {
        return inventory.waste(id, request.quantity(), request.reason());
    }

    @PostMapping("/ingredients/{id}/count")
    InventoryService.IngredientView count(@PathVariable UUID id, @Valid @RequestBody CountRequest request) {
        return inventory.count(id, request.counted(), request.note());
    }

    @GetMapping("/movements")
    List<InventoryService.MovementView> movements(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                  @RequestParam(required = false) UUID ingredientId) {
        return inventory.movements(ingredientId, from, to);
    }

    @GetMapping("/recipes/{menuItemId}")
    InventoryService.RecipeView recipe(@PathVariable UUID menuItemId) { return inventory.recipe(menuItemId); }

    @PutMapping("/recipes/{menuItemId}")
    InventoryService.RecipeView saveRecipe(@PathVariable UUID menuItemId,
                                           @Valid @RequestBody @Size(max = 40) List<@Valid LineRequest> lines) {
        return inventory.saveRecipe(menuItemId, lines.stream()
                .map(line -> new InventoryService.LineData(line.ingredientId(), line.quantity())).toList());
    }

    record IngredientRequest(@NotBlank @Size(max = 100) String name, @NotBlank String unit,
                             @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal costPerUnit,
                             @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal minStock,
                             boolean active) {}
    record PurchaseRequest(@NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                           @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal unitCost,
                           @Size(max = 200) String reference) {}
    record WasteRequest(@NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                        @NotBlank @Size(max = 200) String reason) {}
    record CountRequest(@NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal counted,
                        @Size(max = 200) String note) {}
    record LineRequest(@NotNull UUID ingredientId,
                       @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal quantity) {}
}
