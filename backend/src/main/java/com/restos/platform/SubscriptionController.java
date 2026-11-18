package com.restos.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Suscripciones: el dueño consulta su estado; el propietario de la plataforma gestiona planes y pagos. */
@RestController
public class SubscriptionController {
    private final SubscriptionService subscriptions;

    public SubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping("/api/admin/subscription")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    SubscriptionService.SubscriptionView mine() { return subscriptions.mine(); }

    @GetMapping("/api/platform/organizations/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    SubscriptionService.SubscriptionView view(@PathVariable UUID id) { return subscriptions.view(id); }

    @PutMapping("/api/platform/organizations/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    SubscriptionService.SubscriptionView update(@PathVariable UUID id, @Valid @RequestBody PlanRequest request) {
        return subscriptions.update(id, new SubscriptionService.PlanData(request.plan(), request.monthlyFee(),
                request.maxBranches(), request.trialEndsAt(), request.billingNote()));
    }

    @GetMapping("/api/platform/organizations/{id}/subscription/payments")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    List<SubscriptionService.SubscriptionPayment> payments(@PathVariable UUID id) { return subscriptions.payments(id); }

    @PostMapping("/api/platform/organizations/{id}/subscription/payments")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    SubscriptionService.SubscriptionView registerPayment(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return subscriptions.registerPayment(id, request.amount(), request.months(), request.reference());
    }

    record PlanRequest(@NotBlank String plan,
                       @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal monthlyFee,
                       @Min(1) @Max(50) int maxBranches, LocalDate trialEndsAt, @Size(max = 300) String billingNote) {}
    record PaymentRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal amount,
                          @Min(1) @Max(24) int months, @Size(max = 120) String reference) {}
}
