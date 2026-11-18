package com.restos.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

/** Configuración operativa de mesas y cuentas del personal de una sucursal. */
@RestController
@RequestMapping("/api/admin/operations")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class OperationsAdminController {
    private final OperationsAdminService operations;

    public OperationsAdminController(OperationsAdminService operations) { this.operations = operations; }

    @GetMapping("/tables")
    List<OperationsAdminService.TableAdminView> tables() { return operations.tables(); }

    @PostMapping("/tables")
    OperationsAdminService.TableAdminView createTable(@Valid @RequestBody TableRequest request) {
        return operations.createTable(request.label(), request.capacity(), request.zoneId());
    }

    @PatchMapping("/tables/{id}")
    OperationsAdminService.TableAdminView updateTable(@PathVariable UUID id,
            @Valid @RequestBody TableRequest request) {
        return operations.updateTable(id, request.label(), request.capacity(), request.active(), request.zoneId());
    }

    @PostMapping("/tables/{id}/regenerate-qr")
    OperationsAdminService.TableAdminView regenerateQr(@PathVariable UUID id) {
        return operations.regenerateQr(id);
    }

    @GetMapping("/users")
    List<OperationsAdminService.UserAdminView> users() { return operations.users(); }

    @PostMapping("/users")
    OperationsAdminService.UserAdminView createUser(@Valid @RequestBody UserRequest request) {
        return operations.createUser(request.toData());
    }

    @PatchMapping("/users/{id}")
    OperationsAdminService.UserAdminView updateUser(@PathVariable UUID id,
            @Valid @RequestBody UserRequest request) {
        return operations.updateUser(id, request.toData());
    }

    @PutMapping("/users/{id}/pin")
    OperationsAdminService.UserAdminView setPin(@PathVariable UUID id, @Valid @RequestBody PinRequest request) {
        return operations.setPin(id, request.pin());
    }

    record PinRequest(@Pattern(regexp = "\\d{4,6}") String pin) {}
    record TableRequest(@NotBlank @Size(max = 30) String label, @Min(1) @Max(50) int capacity,
                        boolean active, UUID zoneId) {}
    /**
     * Solo el nombre y el rol son obligatorios. Con acceso al sistema basta el PIN (tablet del local);
     * correo y contraseña son opcionales. Con rol STAFF (sin acceso) el correo y la contraseña no se usan.
     */
    record UserRequest(@NotBlank @Size(max = 120) String fullName, @Size(max = 180) String email,
                       @Size(max = 72) String password,
                       @NotBlank @Pattern(regexp = "BRANCH_MANAGER|WAITER|KITCHEN|STAFF") String role, boolean active,
                       boolean canCounter, boolean canReservations,
                       @Size(max = 20) String documentNumber, @Size(max = 30) String phone,
                       @Size(max = 60) String jobTitle, java.time.LocalDate hiredOn,
                       @Pattern(regexp = "|\\d{4,6}") String pin) {
        OperationsAdminService.UserData toData() {
            return new OperationsAdminService.UserData(fullName, email, password, role, active, canCounter,
                    canReservations, documentNumber, phone, jobTitle, hiredOn, pin);
        }
    }
}
