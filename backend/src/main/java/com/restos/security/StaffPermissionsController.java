package com.restos.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Qué módulos opcionales puede usar la persona conectada (para armar su menú). */
@RestController
@RequestMapping("/api/staff/permissions")
@PreAuthorize("isAuthenticated()")
public class StaffPermissionsController {
    private final StaffPermissions permissions;

    public StaffPermissionsController(StaffPermissions permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    StaffPermissions.View mine() { return permissions.mine(); }
}
