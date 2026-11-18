package com.restos.tables;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/** API de solicitud pública y aprobación operativa para cambios de mesa. */
@RestController
public class TableTransferController {
    private final TableTransferService transfers;

    public TableTransferController(TableTransferService transfers) { this.transfers = transfers; }

    @GetMapping("/api/public/sessions/{sessionId}/available-tables")
    List<TableTransferService.AvailableTable> availableTables(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return transfers.availableTables(sessionId, tableToken, guestToken);
    }

    @PostMapping("/api/public/sessions/{sessionId}/table-transfers")
    TableTransferService.TransferView request(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken,
            @Valid @RequestBody TransferRequest request) {
        return transfers.request(sessionId, tableToken, guestToken, request.destinationQrPublicId());
    }

    @GetMapping("/api/public/table-transfers/{requestId}")
    TableTransferService.PublicTransferStatus status(@PathVariable UUID requestId,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return transfers.publicStatus(requestId, guestToken);
    }

    @DeleteMapping("/api/public/table-transfers/{requestId}")
    TableTransferService.TransferView cancel(@PathVariable UUID requestId,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return transfers.cancel(requestId, guestToken);
    }

    @GetMapping("/api/staff/table-transfers")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<TableTransferService.TransferView> pending() { return transfers.pending(); }

    @PostMapping("/api/staff/table-transfers/{requestId}/approve")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    TableTransferService.TransferView approve(@PathVariable UUID requestId) {
        return transfers.approve(requestId);
    }

    @PatchMapping("/api/staff/table-transfers/{requestId}/reject")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    TableTransferService.TransferView reject(@PathVariable UUID requestId,
            @RequestBody RejectRequest request) { return transfers.reject(requestId, request.reason()); }

    record TransferRequest(UUID destinationQrPublicId) {}
    record RejectRequest(@Size(max = 250) String reason) {}
}
