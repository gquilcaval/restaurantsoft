package com.restos.security;

import com.restos.shared.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Bloqueo temporal en memoria para frenar ataques de fuerza bruta sin revelar si el correo existe. */
@Service
public class LoginAttemptService {
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration blockDuration;

    public LoginAttemptService(@Value("${restos.security.login-max-attempts:5}") int maxAttempts,
            @Value("${restos.security.login-block-minutes:15}") long blockMinutes) {
        this.maxAttempts = maxAttempts;
        this.blockDuration = Duration.ofMinutes(blockMinutes);
    }

    public void assertAllowed(String key) {
        var attempt = attempts.get(key);
        if (attempt == null) return;
        if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos. Espera unos minutos antes de volver a ingresar");
        }
        if (attempt.blockedUntil() != null) attempts.remove(key);
    }

    public void failed(String key) {
        attempts.compute(key, (ignored, current) -> {
            var count = current == null ? 1 : current.count() + 1;
            return count >= maxAttempts ? new Attempt(count, Instant.now().plus(blockDuration))
                    : new Attempt(count, null);
        });
    }

    public void succeeded(String key) { attempts.remove(key); }

    private record Attempt(int count, Instant blockedUntil) {}
}
