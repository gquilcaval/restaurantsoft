package com.restos.realtime;

import com.restos.waiters.AlertAudience;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Publica cambios confirmados por la base de datos hacia mesa, cocina y meseros. */
@Component
public class RealtimePublisher {
    private final SimpMessagingTemplate messaging;
    private final AlertAudience audience;

    public RealtimePublisher(SimpMessagingTemplate messaging, @Lazy AlertAudience audience) {
        this.messaging = messaging;
        this.audience = audience;
    }

    public void table(UUID sessionId, String type, Object payload) {
        messaging.convertAndSend("/topic/table-sessions/" + sessionId, RealtimeEvent.of(type, payload));
    }

    public void kitchen(UUID branchId, String type, Object payload) {
        messaging.convertAndSend("/topic/branches/" + branchId + "/kitchen", RealtimeEvent.of(type, payload));
    }

    /**
     * Todos los meseros reciben el evento (para tener el salón al día), pero si es de una mesa
     * solo le suena a su mesero a cargo, o a quien lo reemplace si no está en turno.
     */
    public void waiters(UUID branchId, String type, Object payload) {
        send(branchId, RealtimeEvent.of(type, payload, audience.forPayload(payload)));
    }

    /** Aviso escalado: suena en el celular de todo el equipo, sin importar quién atiende la mesa. */
    public void waitersEveryone(UUID branchId, String type, Object payload, UUID sessionId) {
        var current = sessionId == null ? null : audience.forSession(sessionId);
        send(branchId, RealtimeEvent.of(type, payload, RealtimeEvent.Audience.everyone(current == null ? null : current.waiterId())));
    }

    private void send(UUID branchId, RealtimeEvent event) {
        messaging.convertAndSend("/topic/branches/" + branchId + "/waiters", event);
    }
}
