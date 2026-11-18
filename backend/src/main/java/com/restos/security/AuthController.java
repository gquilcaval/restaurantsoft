package com.restos.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entrada de autenticación para administradores, meseros y cocina. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final RestosUserDetailsService users;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService loginAttempts;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager, RestosUserDetailsService users,
                          JwtService jwtService, RefreshTokenService refreshTokens,
                          LoginAttemptService loginAttempts, PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
    }

    @PostMapping("/login")
    JwtService.TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        var key = request.email().trim().toLowerCase() + "|" + httpRequest.getRemoteAddr();
        loginAttempts.assertAllowed(key);
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
            loginAttempts.succeeded(key);
            var account = users.findAccount(request.email());
            return jwtService.create(account, account.branchId(), refreshTokens.issue(account.id(), account.branchId()));
        } catch (DisabledException exception) {
            // Solo con la contraseña correcta se explica que el bloqueo es por la suscripción del restaurante.
            var account = users.findAccount(request.email());
            if (passwordEncoder.matches(request.password(), account.passwordHash())) {
                loginAttempts.succeeded(key);
                throw new com.restos.shared.ApiException(HttpStatus.FORBIDDEN,
                        users.organizationBlocked(account.organizationId()) ? SubscriptionGate.SUSPENDED_MESSAGE
                                : "Tu usuario está desactivado. Habla con el encargado del local");
            }
            loginAttempts.failed(key);
            throw new com.restos.shared.ApiException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        } catch (AuthenticationException exception) {
            loginAttempts.failed(key);
            throw new com.restos.shared.ApiException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
    }

    @PostMapping("/refresh")
    JwtService.TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        var rotation = refreshTokens.rotate(request.refreshToken());
        var account = users.findAccount(rotation.userId());
        if (!account.active()) throw new com.restos.shared.ApiException(HttpStatus.UNAUTHORIZED, "Usuario inactivo");
        return jwtService.create(account, rotation.branchId(), rotation.refreshToken());
    }

    @PostMapping("/logout")
    void logout(@RequestBody RefreshRequest request) { refreshTokens.revoke(request.refreshToken()); }

    record LoginRequest(@Email String email, @NotBlank String password) {}
    record RefreshRequest(@NotBlank String refreshToken) {}
}
