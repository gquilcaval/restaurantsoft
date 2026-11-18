package com.restos.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Alta de tablets compartidas (encargados) e ingreso con PIN desde esas tablets (público). */
@RestController
public class DeviceController {
    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/admin/devices")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    DeviceService.RegisteredDevice register(@Valid @RequestBody DeviceRequest request) {
        return devices.register(request.name());
    }

    @GetMapping("/api/admin/devices")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    List<DeviceService.DeviceView> list() { return devices.list(); }

    @DeleteMapping("/api/admin/devices/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
    void revoke(@PathVariable UUID id) { devices.revoke(id); }

    @PostMapping("/api/auth/device/staff")
    DeviceService.DeviceStaff staff(@Valid @RequestBody DeviceTokenRequest request) {
        return devices.staff(request.deviceToken());
    }

    @PostMapping("/api/auth/pin-login")
    JwtService.TokenResponse pinLogin(@Valid @RequestBody PinLoginRequest request, HttpServletRequest http) {
        return devices.pinLogin(request.deviceToken(), request.userId(), request.pin(), http.getRemoteAddr());
    }

    record DeviceRequest(@NotBlank @Size(max = 80) String name) {}
    record DeviceTokenRequest(@NotBlank @Size(max = 200) String deviceToken) {}
    record PinLoginRequest(@NotBlank @Size(max = 200) String deviceToken, @NotNull UUID userId,
                           @NotBlank @Size(max = 6) String pin) {}
}
