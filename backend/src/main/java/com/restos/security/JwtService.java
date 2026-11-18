package com.restos.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Emite tokens que incluyen el rol y el alcance multiempresa del usuario autenticado. */
@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final long accessTokenMinutes;

    public JwtService(JwtEncoder encoder,
                      @Value("${restos.security.access-token-minutes}") long accessTokenMinutes) {
        this.encoder = encoder;
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public TokenResponse create(RestosUserDetailsService.Account account) {
        return create(account, account.branchId(), null);
    }

    public TokenResponse create(RestosUserDetailsService.Account account, UUID branchId, String refreshToken) {
        var now = Instant.now();
        var builder = JwtClaimsSet.builder()
                .issuer("restos")
                .issuedAt(now)
                .expiresAt(now.plus(accessTokenMinutes, ChronoUnit.MINUTES))
                .subject(account.id().toString())
                .claim("name", account.fullName())
                .claim("role", account.role().name());
        // Quien entra solo con PIN no tiene correo.
        if (account.email() != null) builder.claim("email", account.email());
        if (account.organizationId() != null) builder.claim("organization_id", account.organizationId().toString());
        if (branchId != null) builder.claim("branch_id", branchId.toString());
        var claims = builder.build();
        return new TokenResponse(encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue(),
                "Bearer", accessTokenMinutes * 60, account.role().name(), account.fullName(),
                account.organizationId(), branchId, refreshToken);
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                                String role, String fullName, UUID organizationId, UUID branchId,
                                String refreshToken) {}
}
