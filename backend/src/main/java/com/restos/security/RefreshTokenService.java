package com.restos.security;

import com.restos.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Emite refresh tokens opacos, guarda solo su hash y los rota después de cada uso. */
@Service
public class RefreshTokenService {
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();
    private final long expirationDays;
    private final SubscriptionGate subscription;

    public RefreshTokenService(JdbcClient jdbc,
            @Value("${restos.security.refresh-token-days:7}") long expirationDays, SubscriptionGate subscription) {
        this.jdbc = jdbc;
        this.expirationDays = expirationDays;
        this.subscription = subscription;
    }

    @Transactional
    public String issue(UUID userId, UUID branchId) {
        var raw = newToken();
        jdbc.sql("""
                INSERT INTO refresh_tokens(user_id,branch_id,token_hash,expires_at)
                VALUES (:user,:branch,:hash,:expires)
                """).param("user", userId).param("branch", branchId).param("hash", hash(raw))
                .param("expires", Timestamp.from(Instant.now().plus(expirationDays, ChronoUnit.DAYS))).update();
        return raw;
    }

    @Transactional
    public Rotation rotate(String rawToken) {
        var token = jdbc.sql("""
                        SELECT rt.id,rt.user_id,rt.branch_id FROM refresh_tokens rt
                        JOIN app_users u ON u.id=rt.user_id AND u.active
                        LEFT JOIN organizations o ON o.id=u.organization_id
                        LEFT JOIN branches b ON b.id=rt.branch_id
                        WHERE rt.token_hash=:hash AND NOT rt.revoked AND rt.expires_at>NOW()
                          AND COALESCE(o.active,TRUE) AND COALESCE(b.active,TRUE)
                          AND """ + SubscriptionGate.ACCESS_SQL + """
                        FOR UPDATE OF rt
                        """).param("hash", hash(rawToken)).param("graceDays", subscription.graceDays())
                .query((rs, row) -> new StoredToken(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getObject("branch_id", UUID.class))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "La sesión ya no es válida"));
        jdbc.sql("UPDATE refresh_tokens SET revoked=TRUE,last_used_at=NOW() WHERE id=:id")
                .param("id", token.id()).update();
        return new Rotation(token.userId(), token.branchId(), issue(token.userId(), token.branchId()));
    }

    @Transactional
    public String replaceForBranch(String rawToken, UUID userId, UUID branchId) {
        var updated = jdbc.sql("""
                UPDATE refresh_tokens rt SET revoked=TRUE,last_used_at=NOW()
                WHERE rt.token_hash=:hash AND rt.user_id=:user AND NOT rt.revoked AND rt.expires_at>NOW()
                  AND EXISTS (SELECT 1 FROM branches b JOIN organizations o ON o.id=b.organization_id
                              WHERE b.id=:branch AND b.active AND o.active AND """ + SubscriptionGate.ACCESS_SQL + """
                              )
                """).param("hash", hash(rawToken)).param("user", userId).param("branch", branchId)
                .param("graceDays", subscription.graceDays()).update();
        if (updated == 0) throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesión ya no es válida");
        return issue(userId, branchId);
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        jdbc.sql("UPDATE refresh_tokens SET revoked=TRUE,last_used_at=NOW() WHERE token_hash=:hash")
                .param("hash", hash(rawToken)).update();
    }

    /** Compartido con los dispositivos autorizados: mismo formato opaco y mismo hash. */
    String newToken() {
        var bytes = new byte[48]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private record StoredToken(UUID id, UUID userId, UUID branchId) {}
    public record Rotation(UUID userId, UUID branchId, String refreshToken) {}
}
