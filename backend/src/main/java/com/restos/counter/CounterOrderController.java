package com.restos.counter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mostrador: alta y seguimiento de pedidos para llevar y delivery. */
@RestController
@RequestMapping("/api/staff/counter-orders")
@PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
public class CounterOrderController {
    private final CounterOrderService orders;

    private final com.restos.security.StaffPermissions permissions;

    public CounterOrderController(CounterOrderService orders, com.restos.security.StaffPermissions permissions) {
        this.orders = orders;
        this.permissions = permissions;
    }

    @GetMapping
    List<CounterOrderService.CounterOrderView> active() { permissions.requireCounter(); return orders.active(); }

    @PostMapping
    CounterOrderService.CounterOrderView create(@Valid @RequestBody CustomerRequest request) {
        permissions.requireCounter();
        return orders.create(request.toData());
    }

    @PatchMapping("/{sessionId}")
    CounterOrderService.CounterOrderView update(@PathVariable UUID sessionId, @Valid @RequestBody CustomerRequest request) {
        permissions.requireCounter();
        return orders.update(sessionId, request.toData());
    }

    record CustomerRequest(@Pattern(regexp = "TAKEAWAY|DELIVERY") String channel,
                           @NotBlank @Size(max = 120) String customerName,
                           @Size(max = 30) String customerPhone,
                           @Size(max = 250) String deliveryAddress,
                           @Size(max = 250) String deliveryReference,
                           @DecimalMin("0.00") @DecimalMax("999.99") @Digits(integer = 3, fraction = 2) BigDecimal deliveryFee) {
        CounterOrderService.CustomerData toData() {
            return new CounterOrderService.CustomerData(channel == null ? "TAKEAWAY" : channel, customerName,
                    customerPhone, deliveryAddress, deliveryReference, deliveryFee);
        }
    }
}
