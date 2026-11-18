package com.restos.tables;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints de apertura para personal y unión pública mediante el QR físico. */
@RestController
public class TableController {
    private final TableService tables;

    public TableController(TableService tables) {
        this.tables = tables;
    }

    @GetMapping("/api/staff/tables")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    List<TableService.TableView> list() {
        return tables.listBranchTables();
    }

    @PostMapping("/api/staff/tables/{tableId}/open")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    TableService.SessionOpened open(@PathVariable UUID tableId) {
        return tables.open(tableId);
    }

    @PostMapping("/api/public/tables/{qrPublicId}/join")
    TableService.GuestJoined join(@PathVariable UUID qrPublicId, @Valid @RequestBody JoinRequest request) {
        return tables.join(qrPublicId, request.displayName(), request.mode());
    }

    @PostMapping("/api/public/sessions/{sessionId}/guests/{guestId}/claim")
    TableService.GuestIdentityClaim claim(@PathVariable UUID sessionId, @PathVariable UUID guestId,
            @org.springframework.web.bind.annotation.RequestHeader("X-Table-Token") UUID tableToken) {
        return tables.claimLegacyIdentity(sessionId, tableToken, guestId);
    }

    @PostMapping("/api/public/sessions/{sessionId}/leave")
    TableService.GuestLeft leave(@PathVariable UUID sessionId,
            @org.springframework.web.bind.annotation.RequestHeader("X-Table-Token") UUID tableToken,
            @org.springframework.web.bind.annotation.RequestHeader("X-Guest-Token") UUID guestToken) {
        return tables.leave(sessionId, tableToken, guestToken);
    }

    @PostMapping("/api/staff/sessions/{sessionId}/guests/{guestId}/retire")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    TableService.GuestLeft retireGuest(@PathVariable UUID sessionId, @PathVariable UUID guestId,
            @RequestBody RetireGuestRequest request) {
        return tables.retireByStaff(sessionId, guestId, request.reason());
    }

    @PostMapping("/api/staff/sessions/{sessionId}/cancel")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    TableService.SessionClosed cancel(@PathVariable UUID sessionId, @RequestBody CancelRequest request) {
        return tables.cancel(sessionId, request.reason());
    }

    @PostMapping("/api/staff/sessions/{sessionId}/reactivate")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    TableService.SessionReviewed reactivate(@PathVariable UUID sessionId) {
        return tables.reactivate(sessionId);
    }

    @PostMapping("/api/staff/sessions/{sessionId}/host")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER','WAITER')")
    TableService.HostTransferred transferHost(@PathVariable UUID sessionId,
            @RequestBody TransferHostRequest request) {
        return tables.transferHost(sessionId, request.guestId());
    }

    /** mode: vacío (normal), RECLAIM («soy yo», vuelve a entrar con su identidad) o NEW («soy otra persona»). */
    record JoinRequest(@NotBlank @Size(max = 60) String displayName,
                       @jakarta.validation.constraints.Pattern(regexp = "RECLAIM|NEW") String mode) {}
    record CancelRequest(@Size(max = 250) String reason) {}
    record TransferHostRequest(UUID guestId) {}
    record RetireGuestRequest(@Size(max = 250) String reason) {}
}
