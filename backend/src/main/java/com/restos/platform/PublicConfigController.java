package com.restos.platform;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entrega al panel la dirección que deben usar los teléfonos para abrir los QR. */
@RestController
@RequestMapping("/api/public/config")
public class PublicConfigController {
    private final String publicBaseUrl;
    private final boolean demoMode;

    public PublicConfigController(@Value("${restos.public-base-url}") String publicBaseUrl,
            @Value("${restos.demo-data-enabled:true}") boolean demoMode) {
        this.publicBaseUrl = publicBaseUrl;
        this.demoMode = demoMode;
    }

    @GetMapping
    PublicConfiguration get() {
        var host = URI.create(publicBaseUrl).getHost();
        var networkReady = host != null && !host.equalsIgnoreCase("localhost")
                && !host.equals("127.0.0.1") && !host.equals("::1");
        return new PublicConfiguration(publicBaseUrl, networkReady, demoMode);
    }

    public record PublicConfiguration(String baseUrl, boolean networkReady, boolean demoMode) {}
}
