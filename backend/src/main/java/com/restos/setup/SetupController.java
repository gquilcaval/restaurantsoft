package com.restos.setup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Asistente de puesta en marcha para dueños y encargados. */
@RestController
@RequestMapping("/api/admin/setup")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class SetupController {
    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/status")
    SetupService.SetupStatus status() { return setup.status(); }

    @GetMapping("/menu-template")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"plantilla-carta-restos.xlsx\"")
                .body(setup.template());
    }

    @PostMapping("/menu-import")
    SetupService.ImportResult importMenu(@RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        try (var input = file.getInputStream()) {
            return setup.importMenu(input, apply);
        }
    }

    @PostMapping("/tables")
    SetupService.TablesResult tables(@Valid @RequestBody TablesRequest request) {
        return setup.createTables(request.prefix(), request.from(), request.count(), request.capacity(), request.zoneId());
    }

    record TablesRequest(@Size(max = 20) String prefix, @Min(1) @Max(999) int from, @Min(1) @Max(100) int count,
                         @Min(1) @Max(50) int capacity, java.util.UUID zoneId) {}
}
