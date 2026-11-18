package com.restos.security;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Carga credenciales y alcance organizacional para autenticación de personal. */
@Service
public class RestosUserDetailsService implements UserDetailsService {
    private final JdbcClient jdbcClient;
    private final SubscriptionGate subscription;

    public RestosUserDetailsService(JdbcClient jdbcClient, SubscriptionGate subscription) {
        this.jdbcClient = jdbcClient;
        this.subscription = subscription;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return findAccount(username).toUserDetails();
    }

    public Account findAccount(String email) {
        return jdbcClient.sql("""
                        SELECT u.id, u.organization_id, u.branch_id, u.full_name, u.email, u.password_hash, u.role,
                          (u.active AND COALESCE(o.active,TRUE) AND
                           (u.role IN ('PLATFORM_ADMIN','ORGANIZATION_ADMIN') OR COALESCE(b.active,TRUE)) AND
                           """ + SubscriptionGate.ACCESS_SQL + """
                          ) active
                        FROM app_users u
                        LEFT JOIN organizations o ON o.id=u.organization_id
                        LEFT JOIN branches b ON b.id=u.branch_id
                        WHERE lower(u.email) = lower(:email)
                        """)
                .param("email", email).param("graceDays", subscription.graceDays())
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active")))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
    }

    /** Verdadero cuando el restaurante está suspendido o su suscripción venció (fuera del periodo de gracia). */
    public boolean organizationBlocked(UUID organizationId) {
        if (organizationId == null) return false;
        return jdbcClient.sql("SELECT NOT (o.active AND " + SubscriptionGate.ACCESS_SQL + ") FROM organizations o WHERE o.id=:id")
                .param("id", organizationId).param("graceDays", subscription.graceDays())
                .query(Boolean.class).optional().orElse(false);
    }

    public Account findAccount(UUID userId) {
        return jdbcClient.sql("""
                        SELECT u.id, u.organization_id, u.branch_id, u.full_name, u.email, u.password_hash, u.role,
                          (u.active AND COALESCE(o.active,TRUE) AND
                           (u.role IN ('PLATFORM_ADMIN','ORGANIZATION_ADMIN') OR COALESCE(b.active,TRUE)) AND
                           """ + SubscriptionGate.ACCESS_SQL + """
                          ) active
                        FROM app_users u
                        LEFT JOIN organizations o ON o.id=u.organization_id
                        LEFT JOIN branches b ON b.id=u.branch_id
                        WHERE u.id=:id
                        """).param("id", userId).param("graceDays", subscription.graceDays())
                .query((rs, row) -> new Account(
                        rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("password_hash"), UserRole.valueOf(rs.getString("role")),
                        rs.getBoolean("active"))).optional()
                .orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));
    }

    public record Account(UUID id, UUID organizationId, UUID branchId, String fullName,
                          String email, String passwordHash, UserRole role, boolean active) {
        UserDetails toUserDetails() {
            return User.withUsername(email)
                    .password(passwordHash)
                    .roles(role.name())
                    .disabled(!active)
                    .build();
        }
    }
}
