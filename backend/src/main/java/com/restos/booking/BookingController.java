package com.restos.booking;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Enlace público de reservas de cada sucursal y su activación desde Recepción. */
@RestController
public class BookingController {
    private final BookingService booking;

    public BookingController(BookingService booking) {
        this.booking = booking;
    }

    @GetMapping("/api/public/booking/{branchId}")
    BookingService.BookingPage page(@PathVariable UUID branchId) { return booking.page(branchId); }

    @GetMapping("/api/public/booking/{branchId}/availability")
    java.util.Map<String, List<UUID>> availability(@PathVariable UUID branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime time) {
        booking.page(branchId);
        return java.util.Map.of("busyTableIds", booking.busyTables(branchId, date, time));
    }

    @PostMapping("/api/public/booking/{branchId}")
    BookingService.Confirmation book(@PathVariable UUID branchId, @Valid @RequestBody Request request, HttpServletRequest http) {
        var forwarded = http.getHeader("X-Forwarded-For");
        var client = forwarded == null || forwarded.isBlank() ? http.getRemoteAddr() : forwarded.split(",")[0].trim();
        return booking.book(branchId, new BookingService.BookingRequest(request.name(), request.phone(), request.partySize(),
                request.date(), LocalTime.parse(request.time()), request.tableId(), request.zoneId(), request.notes()), branchId + ":" + client);
    }

    @GetMapping("/api/admin/booking")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    BookingService.BookingStatus status() { return booking.status(); }

    @PutMapping("/api/admin/booking")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    BookingService.BookingStatus setEnabled(@RequestBody Toggle toggle) { return booking.setEnabled(toggle.enabled()); }

    record Request(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 30) String phone,
                   @Min(1) @Max(30) int partySize, @NotNull LocalDate date, @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String time,
                   UUID tableId, UUID zoneId, @Size(max = 200) String notes) {}
    record Toggle(boolean enabled) {}
}
