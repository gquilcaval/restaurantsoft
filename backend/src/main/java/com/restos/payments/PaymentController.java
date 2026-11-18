package com.restos.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cobro manual para mesero o administrador; no procesa dinero con terceros. */
@RestController
@RequestMapping("/api/staff/sessions/{sessionId}/payments")
@PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    PaymentService.PaymentSummary register(@PathVariable UUID sessionId, @Valid @RequestBody PaymentRequest request) {
        return payments.register(sessionId, request.method(), request.amount(), request.reference(),
                request.tip(), request.received(), request.guestId());
    }

    @GetMapping
    PaymentService.PaymentSummary summary(@PathVariable UUID sessionId) {
        return payments.summaryForStaff(sessionId);
    }

    @PostMapping("/close")
    PaymentService.ClosedSession close(@PathVariable UUID sessionId) {
        return payments.close(sessionId);
    }

    @PostMapping("/begin")
    PaymentService.CheckoutView beginCheckout(@PathVariable UUID sessionId) {
        return payments.beginCheckout(sessionId);
    }

    /** Descuento de la atención; enviar {@code kind} nulo lo retira. */
    @PutMapping("/discount")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    PaymentService.PaymentSummary discount(@PathVariable UUID sessionId, @Valid @RequestBody DiscountRequest request) {
        return payments.applyDiscount(sessionId, request.kind(), request.value(), request.reason());
    }

    record PaymentRequest(@NotBlank String method, @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,
                          @Size(max = 100) String reference,
                          @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal tip,
                          @DecimalMin("0.00") @Digits(integer=10,fraction=2) BigDecimal received,
                          UUID guestId) {}
    record DiscountRequest(String kind, @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal value,
                           @Size(max = 250) String reason) {}
}
