package com.restos.security;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import com.restos.shared.ApiException;

/** Centraliza la lectura segura del usuario y su alcance desde el JWT validado. */
@Component
public class CurrentUser {
    public Scope require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Se requiere autenticación");
        }
        return new Scope(
                UUID.fromString(jwt.getSubject()),
                uuidClaim(jwt, "organization_id"),
                uuidClaim(jwt, "branch_id"),
                UserRole.valueOf(jwt.getClaimAsString("role")));
    }

    /** Alcance del personal si la petición viene autenticada; vacío para comensales del QR. */
    public java.util.Optional<Scope> find() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt)) return java.util.Optional.empty();
        return java.util.Optional.of(require());
    }

    private UUID uuidClaim(Jwt jwt, String name) {
        var value = jwt.getClaimAsString(name);
        return value == null ? null : UUID.fromString(value);
    }

    public record Scope(UUID userId, UUID organizationId, UUID branchId, UserRole role) {}
}
