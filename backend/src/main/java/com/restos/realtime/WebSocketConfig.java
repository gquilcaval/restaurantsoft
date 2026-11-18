package com.restos.realtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Configura STOMP; el broker simple podrá reemplazarse por RabbitMQ sin cambiar los clientes. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final StompSecurityInterceptor securityInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompSecurityInterceptor securityInterceptor,
            @Value("${restos.cors.allowed-origins}") List<String> configuredOrigins,
            @Value("${restos.public-base-url}") String publicBaseUrl) {
        this.securityInterceptor = securityInterceptor;
        var origins = new ArrayList<>(configuredOrigins);
        if (!origins.contains(publicBaseUrl)) origins.add(publicBaseUrl);
        this.allowedOrigins = origins.toArray(String[]::new);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(securityInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Latidos cada 10 s en ambos sentidos: así el servidor y el navegador detectan conexiones muertas
        // (celular bloqueado, wifi caído) en vez de quedarse esperando mensajes que nunca llegan.
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(scheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }
}
