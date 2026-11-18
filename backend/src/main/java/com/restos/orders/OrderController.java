package com.restos.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.restos.tables.TableService;

/** API compartida por comensal, cocina y mesero para el ciclo completo del pedido. */
@RestController
public class OrderController {
    private final OrderService orders;
    private final TableService tables;

    public OrderController(OrderService orders, TableService tables) {
        this.orders = orders;
        this.tables = tables;
    }

    @GetMapping("/api/public/sessions/{sessionId}/orders")
    OrderService.OrderSnapshot snapshot(@PathVariable UUID sessionId,
                                        @RequestHeader("X-Table-Token") UUID tableToken) {
        // La lectura también exige el token temporal para no exponer consumos de otras mesas.
        tables.verifyTableToken(sessionId, tableToken);
        return orders.snapshot(sessionId);
    }

    @PostMapping("/api/public/sessions/{sessionId}/cart/items")
    OrderService.OrderSnapshot add(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken,
            @Valid @RequestBody AddItemRequest request) {
        return orders.addItem(sessionId, tableToken, guestToken,
                new OrderService.AddItem(request.menuItemId(), request.quantity(), request.notes(),
                        request.optionIds()));
    }

    @DeleteMapping("/api/public/sessions/{sessionId}/cart/items/{itemId}")
    OrderService.OrderSnapshot remove(@PathVariable UUID sessionId, @PathVariable UUID itemId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return orders.removeItem(sessionId, tableToken, guestToken, itemId);
    }

    @PatchMapping("/api/public/sessions/{sessionId}/cart/items/{itemId}")
    OrderService.OrderSnapshot updateDraftItem(@PathVariable UUID sessionId, @PathVariable UUID itemId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken,
            @Valid @RequestBody UpdateItemRequest request) {
        return orders.updateDraftItem(sessionId, tableToken, guestToken, itemId,
                request.quantity(), request.notes());
    }

    @PostMapping("/api/public/sessions/{sessionId}/orders/submit")
    OrderService.OrderSnapshot submit(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return orders.submit(sessionId, tableToken, guestToken);
    }

    @GetMapping("/api/kitchen/orders")
    @PreAuthorize("hasAnyRole('KITCHEN','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<OrderService.KitchenOrder> kitchenOrders() {
        return orders.activeKitchenOrders();
    }

    @GetMapping("/api/staff/sessions/{sessionId}/orders")
    @PreAuthorize("hasAnyRole('KITCHEN','WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OrderService.OrderSnapshot staffSnapshot(@PathVariable UUID sessionId) {
        return orders.staffSnapshot(sessionId);
    }

    @PatchMapping("/api/staff/order-items/{itemId}/status")
    @PreAuthorize("hasAnyRole('KITCHEN','WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OrderService.OrderSnapshot status(@PathVariable UUID itemId, @RequestBody StatusRequest request) {
        return orders.updateItemStatus(itemId, request.status());
    }

    @PostMapping("/api/staff/sessions/{sessionId}/rounds")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OrderService.OrderSnapshot staffRound(@PathVariable UUID sessionId, @Valid @RequestBody StaffRoundRequest request) {
        return orders.submitStaffRound(sessionId, request.items().stream()
                .map(item -> new OrderService.AddItem(item.menuItemId(), item.quantity(), item.notes(),
                        item.optionIds()))
                .toList());
    }

    @PostMapping("/api/staff/orders/{orderId}/deliver")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OrderService.OrderSnapshot deliverRound(@PathVariable UUID orderId) {
        return orders.deliverRound(orderId);
    }

    @PostMapping("/api/staff/order-items/{itemId}/void")
    @PreAuthorize("hasAnyRole('KITCHEN','WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    OrderService.OrderSnapshot voidItem(@PathVariable UUID itemId, @Valid @RequestBody VoidRequest request) {
        return orders.voidItem(itemId, request.quantity(), request.reason());
    }

    record AddItemRequest(@NotNull UUID menuItemId, @Min(1) @Max(20) int quantity,
                          @Size(max = 300) String notes, @Size(max = 20) List<UUID> optionIds) {}
    record StaffRoundRequest(@NotEmpty @Size(max = 50) List<@Valid AddItemRequest> items) {}
    record VoidRequest(@Min(1) @Max(20) int quantity, @NotBlank @Size(max = 250) String reason) {}
    record UpdateItemRequest(@Min(1) @Max(20) int quantity, @Size(max = 300) String notes) {}
    record StatusRequest(String status) {}
}
