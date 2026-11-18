package com.restos.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Lista de espera: el anfitrión la gestiona y el cliente sigue su turno con un enlace. */
@RestController
public class WaitlistController {
    private final WaitlistService waitlist;

    public WaitlistController(WaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    @GetMapping("/api/staff/waitlist")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    WaitlistService.WaitlistBoard board() { return waitlist.board(); }

    @PostMapping("/api/staff/waitlist")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    WaitlistService.WaitlistEntry create(@Valid @RequestBody CreateRequest request) {
        return waitlist.create(request.name(), request.phone(), request.partySize(), request.notes());
    }

    @PostMapping("/api/staff/waitlist/{id}/notify")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    WaitlistService.WaitlistEntry notifyReady(@PathVariable UUID id, @RequestBody TableRequest request) {
        return waitlist.notifyReady(id, request.tableId());
    }

    @PostMapping("/api/staff/waitlist/{id}/seat")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    WaitlistService.WaitlistEntry seat(@PathVariable UUID id, @RequestBody TableRequest request) {
        return waitlist.seat(id, request.tableId());
    }

    @PostMapping("/api/staff/waitlist/{id}/close")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    WaitlistService.WaitlistEntry close(@PathVariable UUID id, @RequestBody CloseRequest request) {
        return waitlist.close(id, request.noShow());
    }

    @GetMapping("/api/public/waitlist/{token}")
    WaitlistService.PublicWaitlist publicView(@PathVariable UUID token) { return waitlist.publicView(token); }

    @PostMapping("/api/public/waitlist/{token}/cancel")
    WaitlistService.PublicWaitlist publicCancel(@PathVariable UUID token) { return waitlist.publicCancel(token); }

    record CreateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 30) String phone,
                         @Min(1) @Max(50) int partySize, @Size(max = 200) String notes) {}
    record TableRequest(UUID tableId) {}
    record CloseRequest(boolean noShow) {}
}
