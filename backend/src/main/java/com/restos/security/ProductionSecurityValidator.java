package com.restos.security;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Impide iniciar en modo producción con secretos de ejemplo, HTTP o datos de demostración. */
@Component
public class ProductionSecurityValidator implements InitializingBean {
    private static final String DEVELOPMENT_SECRET = "restos-development-secret-change-me-now";
    private final boolean productionMode;
    private final boolean demoMode;
    private final String jwtSecret;
    private final String publicBaseUrl;
    private final List<String> allowedOrigins;

    public ProductionSecurityValidator(
            @Value("${restos.production-mode:false}") boolean productionMode,
            @Value("${restos.demo-data-enabled:true}") boolean demoMode,
            @Value("${restos.security.jwt-secret}") String jwtSecret,
            @Value("${restos.public-base-url}") String publicBaseUrl,
            @Value("${restos.cors.allowed-origins}") List<String> allowedOrigins) {
        this.productionMode = productionMode;
        this.demoMode = demoMode;
        this.jwtSecret = jwtSecret;
        this.publicBaseUrl = publicBaseUrl;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void afterPropertiesSet() {
        if (!productionMode) return;
        if (demoMode) fail("DEMO_DATA_ENABLED debe ser false");
        if (jwtSecret.length() < 48 || DEVELOPMENT_SECRET.equals(jwtSecret)) {
            fail("JWT_SECRET debe ser aleatorio y tener al menos 48 caracteres");
        }
        if (!publicBaseUrl.startsWith("https://")) fail("PUBLIC_BASE_URL debe usar HTTPS");
        if (allowedOrigins.stream().anyMatch(this::isLocalOrigin)) {
            fail("CORS_ALLOWED_ORIGINS no puede incluir localhost en producción");
        }
    }

    private boolean isLocalOrigin(String origin) {
        return origin.contains("localhost") || origin.contains("127.0.0.1") || origin.contains("0.0.0.0");
    }

    private void fail(String message) {
        throw new IllegalStateException("Configuración insegura de RestOS: " + message);
    }
}
