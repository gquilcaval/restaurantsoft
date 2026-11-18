package com.restos.reservations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agenda de reservas para el anfitrión, los meseros y la administración. */
@RestController
@RequestMapping("/api/staff/reservations")
@PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
public class ReservationController {
    private final ReservationService reservations;

    private final com.restos.security.StaffPermissions permissions;

    public ReservationController(ReservationService reservations, com.restos.security.StaffPermissions permissions) {
        this.reservations = reservations;
        this.permissions = permissions;
    }

    @GetMapping
    List<ReservationService.ReservationView> day(@RequestParam LocalDate date) { permissions.requireReservations(); return reservations.day(date); }

    @GetMapping("/upcoming")
    List<ReservationService.ReservationView> upcoming(@RequestParam(defaultValue = "3") int hours,
                                                      @RequestParam(defaultValue = "30") int pastMinutes) {
        return reservations.upcoming(Math.max(1, Math.min(12, hours)), Math.max(0, Math.min(360, pastMinutes)));
    }

    @PostMapping
    ReservationService.ReservationView create(@Valid @RequestBody ReservationRequest request) {
        permissions.requireReservations();
        return reservations.save(null, request.toData());
    }

    @PatchMapping("/{id}")
    ReservationService.ReservationView update(@PathVariable UUID id, @Valid @RequestBody ReservationRequest request) {
        permissions.requireReservations();
        return reservations.save(id, request.toData());
    }

    @PostMapping("/{id}/status")
    ReservationService.ReservationView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        permissions.requireReservations();
        return reservations.changeStatus(id, request.status());
    }

    @PostMapping("/{id}/seat")
    ReservationService.ReservationView seat(@PathVariable UUID id, @RequestBody SeatRequest request) {
        return reservations.seat(id, request.tableId());
    }

    record ReservationRequest(@NotBlank @Size(max = 120) String customerName, @NotBlank @Size(max = 30) String phone,
                              @Min(1) @Max(200) int partySize, @NotNull LocalDateTime reservedAt,
                              @Min(15) @Max(480) int durationMinutes, UUID tableId, @Size(max = 300) String notes,
                              boolean confirmed, UUID zoneId) {
        ReservationService.ReservationData toData() {
            return new ReservationService.ReservationData(customerName, phone, partySize, reservedAt, durationMinutes,
                    tableId, notes, confirmed, zoneId);
        }
    }
    record StatusRequest(@NotBlank String status) {}
    record SeatRequest(UUID tableId) {}
}
