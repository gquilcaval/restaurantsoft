package com.restos.customers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Clientes frecuentes: el salón los busca y asocia; la administración gestiona fichas y reglas de puntos. */
@RestController
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/api/staff/customers")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<CustomerService.CustomerView> search(@RequestParam(defaultValue = "") String q,
                                              @RequestParam(defaultValue = "recent") String sort,
                                              @RequestParam(defaultValue = "ALL") String segment) {
        return customers.search(q, sort, segment);
    }

    @GetMapping("/api/admin/customers/summary")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.Summary summary() {
        return customers.summary();
    }

    @PostMapping("/api/staff/customers")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.CustomerView create(@Valid @RequestBody CustomerRequest request) {
        return customers.save(null, request.toData());
    }

    @GetMapping("/api/admin/customers/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.CustomerDetail detail(@PathVariable UUID id) { return customers.detail(id); }

    @PatchMapping("/api/admin/customers/{id}")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.CustomerView update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customers.save(id, request.toData());
    }

    @PostMapping("/api/admin/customers/{id}/points")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.CustomerView adjust(@PathVariable UUID id, @Valid @RequestBody AdjustRequest request) {
        return customers.adjustPoints(id, request.points(), request.reason());
    }

    @GetMapping("/api/admin/loyalty")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.Program program() { return customers.program(); }

    @PutMapping("/api/admin/loyalty")
    @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    CustomerService.Program saveProgram(@Valid @RequestBody ProgramRequest request) {
        return customers.saveProgram(new CustomerService.Program(request.enabled(), request.spendPerPoint(),
                request.pointValue(), request.minRedeem()));
    }

    @GetMapping("/api/staff/sessions/{sessionId}/loyalty")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.LoyaltyState state(@PathVariable UUID sessionId) { return customers.state(sessionId); }

    @PutMapping("/api/staff/sessions/{sessionId}/customer")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.LoyaltyState link(@PathVariable UUID sessionId, @RequestBody LinkRequest request) {
        return customers.link(sessionId, request.customerId());
    }

    @PostMapping("/api/staff/sessions/{sessionId}/loyalty/redeem")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.LoyaltyState redeem(@PathVariable UUID sessionId, @Valid @RequestBody RedeemRequest request) {
        return customers.redeem(sessionId, request.points());
    }

    @DeleteMapping("/api/staff/sessions/{sessionId}/loyalty/redeem")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    CustomerService.LoyaltyState cancelRedeem(@PathVariable UUID sessionId) { return customers.cancelRedeem(sessionId); }

    record CustomerRequest(@NotBlank @Size(max = 120) String fullName, @NotBlank @Size(max = 30) String phone,
                           @Email @Size(max = 180) String email, LocalDate birthday, @Size(max = 500) String notes) {
        CustomerService.CustomerData toData() {
            return new CustomerService.CustomerData(fullName, phone, email, birthday, notes);
        }
    }
    record AdjustRequest(@Min(-100000) @Max(100000) int points, @NotBlank @Size(max = 200) String reason) {}
    record ProgramRequest(boolean enabled,
                          @NotNull @DecimalMin("0.5") @Digits(integer = 6, fraction = 2) BigDecimal spendPerPoint,
                          @NotNull @DecimalMin("0.001") @Digits(integer = 4, fraction = 4) BigDecimal pointValue,
                          @Min(1) @Max(100000) int minRedeem) {}
    record LinkRequest(UUID customerId) {}
    record RedeemRequest(@Min(1) @Max(1000000) int points) {}
}
