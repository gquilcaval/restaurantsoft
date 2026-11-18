package com.restos.realtime;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import com.restos.shared.ApiException;

/** Autentica la conexión STOMP y limita suscripciones al restaurante, sucursal o mesa correctos. */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {
    private final JwtDecoder jwtDecoder;
    private final JdbcClient jdbc;

    public StompSecurityInterceptor(JwtDecoder jwtDecoder, JdbcClient jdbc) {
        this.jwtDecoder = jwtDecoder;
        this.jdbc = jdbc;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Recuperamos el accessor mutable original; envolver el mensaje crearía una copia y perdería el usuario.
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;
        if (accessor.getCommand() == StompCommand.CONNECT) authenticate(accessor);
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) authorize(accessor);
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        var authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            Jwt jwt = jwtDecoder.decode(authorization.substring(7));
            accessor.setUser(new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role")))));
            return;
        }
        var tableToken = accessor.getFirstNativeHeader("X-Table-Token");
        if (tableToken != null) {
            var sessionId = jdbc.sql("SELECT id FROM table_sessions WHERE guest_token=:token AND status='OPEN'")
                    .param("token", UUID.fromString(tableToken)).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sesión de mesa inválida"));
            accessor.setUser(new TablePrincipal(sessionId));
        }
    }

    private void authorize(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        String destination = accessor.getDestination();
        if (principal == null || destination == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Suscripción no autorizada");
        }
        if (principal instanceof TablePrincipal table) {
            var expected = "/topic/table-sessions/" + table.sessionId();
            if (!destination.equals(expected)) throw new ApiException(HttpStatus.FORBIDDEN, "Canal de mesa no autorizado");
            return;
        }
        if (principal instanceof JwtAuthenticationToken authentication) {
            var jwt = authentication.getToken();
            var branch = jwt.getClaimAsString("branch_id");
            var role = jwt.getClaimAsString("role");
            if (destination.startsWith("/topic/branches/") &&
                    (branch == null || !destination.startsWith("/topic/branches/" + branch + "/"))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Canal de sucursal no autorizado");
            }
            if (destination.endsWith("/kitchen") &&
                    !List.of("KITCHEN", "BRANCH_MANAGER", "ORGANIZATION_ADMIN").contains(role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Canal de cocina no autorizado");
            }
            if (destination.endsWith("/waiters") &&
                    !List.of("WAITER", "BRANCH_MANAGER", "ORGANIZATION_ADMIN").contains(role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Canal de meseros no autorizado");
            }
        }
    }

    private record TablePrincipal(UUID sessionId) implements Principal {
        @Override public String getName() { return "table:" + sessionId; }
    }
}
