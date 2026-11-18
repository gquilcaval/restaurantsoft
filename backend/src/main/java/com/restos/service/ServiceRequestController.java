package com.restos.service;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Entrada pública para solicitar atención y panel operativo del mesero. */
@RestController
public class ServiceRequestController {
    private final ServiceRequestService requests;

    public ServiceRequestController(ServiceRequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/api/public/sessions/{sessionId}/service-requests")
    ServiceRequestService.RequestView create(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken,
            @RequestBody CreateRequest request) {
        return requests.create(sessionId, tableToken, guestToken, request.type(), request.splitMode(),
                request.splitParts(), request.tipPercent(), request.reason());
    }

    /** El comensal responde «¿Ya te atendieron?». */
    @PostMapping("/api/public/sessions/{sessionId}/service-requests/{requestId}/feedback")
    ServiceRequestService.RequestView feedback(@PathVariable UUID sessionId, @PathVariable UUID requestId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken,
            @RequestBody FeedbackRequest request) {
        return requests.feedback(sessionId, tableToken, guestToken, requestId, request.attended());
    }

    @GetMapping("/api/public/sessions/{sessionId}/service-requests")
    List<ServiceRequestService.RequestView> activeForSession(@PathVariable UUID sessionId,
            @RequestHeader("X-Table-Token") UUID tableToken,
            @RequestHeader("X-Guest-Token") UUID guestToken) {
        return requests.activeForSession(sessionId, tableToken, guestToken);
    }

    @GetMapping("/api/staff/service-requests")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    List<ServiceRequestService.RequestView> pending() {
        return requests.pending();
    }

    @PatchMapping("/api/staff/service-requests/{requestId}")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    ServiceRequestService.RequestView handle(@PathVariable UUID requestId, @RequestBody StatusRequest request) {
        return requests.handle(requestId, request.status());
    }

    record CreateRequest(String type, String splitMode, Integer splitParts, Integer tipPercent, String reason) {}
    record FeedbackRequest(boolean attended) {}
    record StatusRequest(String status) {}
}
