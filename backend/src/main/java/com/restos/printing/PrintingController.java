package com.restos.printing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Configuración de impresoras (encargado/dueño), acciones del mesero y la conexión del programa puente. */
@RestController
public class PrintingController {
    private final PrintingService printing;
    private final PrintingSettingsService settings;

    public PrintingController(PrintingService printing, PrintingSettingsService settings) {
        this.printing = printing;
        this.settings = settings;
    }

    // ——— Configuración ———
    @GetMapping("/api/admin/printing")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    PrintingSettingsService.Overview overview() { return settings.overview(); }

    @PostMapping("/api/admin/printing/printers")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    PrintingSettingsService.PrinterView create(@Valid @RequestBody PrinterRequest request) {
        return settings.savePrinter(null, request.name(), request.host(), request.port(), request.paperWidth(), true);
    }

    @PutMapping("/api/admin/printing/printers/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    PrintingSettingsService.PrinterView update(@PathVariable UUID id, @Valid @RequestBody PrinterRequest request) {
        return settings.savePrinter(id, request.name(), request.host(), request.port(), request.paperWidth(), request.active() == null || request.active());
    }

    @DeleteMapping("/api/admin/printing/printers/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void delete(@PathVariable UUID id) { settings.deletePrinter(id); }

    @PostMapping("/api/admin/printing/printers/{id}/test")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void test(@PathVariable UUID id) { settings.test(id); }

    @PutMapping("/api/admin/printing/routes")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void route(@RequestBody RouteRequest request) { settings.setRoute(request.stationId(), request.printerId()); }

    @PostMapping("/api/admin/printing/agents")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    PrintingSettingsService.AgentView createAgent(@RequestBody AgentRequest request) { return settings.createAgent(request.name()); }

    @DeleteMapping("/api/admin/printing/agents/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void revokeAgent(@PathVariable UUID id) { settings.revokeAgent(id); }

    // ——— Mesero ———
    @PostMapping("/api/staff/print-jobs/{id}/retry")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void retry(@PathVariable UUID id) { printing.retry(id); }

    @PostMapping("/api/staff/orders/{orderId}/reprint")
    @PreAuthorize("hasAnyRole('WAITER','BRANCH_MANAGER','ORGANIZATION_ADMIN')")
    void reprint(@PathVariable UUID orderId) { printing.reprint(orderId); }

    // ——— Programa puente (público: se identifica con su token) ———
    @PostMapping("/api/public/print-agent/pair")
    PrintingService.Paired pair(@Valid @RequestBody PairRequest request) { return printing.pair(request.code(), request.version()); }

    @PostMapping("/api/public/print-agent/poll")
    List<PrintingService.AgentJob> poll(@RequestHeader("X-Agent-Token") String token, @RequestBody(required = false) PollRequest request) {
        return printing.poll(token, request == null ? null : request.version());
    }

    @PostMapping("/api/public/print-agent/jobs/{id}/result")
    void result(@RequestHeader("X-Agent-Token") String token, @PathVariable UUID id, @RequestBody ResultRequest request) {
        printing.result(token, id, request.ok(), request.error());
    }

    record PrinterRequest(@NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 120) String host,
                          @Min(1) @Max(65535) int port, int paperWidth, Boolean active) {}
    record RouteRequest(UUID stationId, UUID printerId) {}
    record AgentRequest(@Size(max = 60) String name) {}
    record PairRequest(@NotBlank @Size(max = 6) String code, @Size(max = 20) String version) {}
    record PollRequest(@Size(max = 20) String version) {}
    record ResultRequest(boolean ok, @Size(max = 500) String error) {}
}
