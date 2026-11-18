package com.restos.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contrato único de eventos para facilitar versionado, trazabilidad y deduplicación en Angular.
 * audience (solo en el canal de meseros, eventos de una mesa): a quién debe sonarle el aviso.
 */
public record RealtimeEvent(UUID eventId, String type, Instant occurredAt, Object payload, Audience audience) {
    public static RealtimeEvent of(String type, Object payload) {
        return of(type, payload, null);
    }

    public static RealtimeEvent of(String type, Object payload, Audience audience) {
        return new RealtimeEvent(UUID.randomUUID(), type, Instant.now(), payload, audience);
    }

    /**
     * waiterId: mesero a cargo de la mesa (puede ser null). alert: quiénes reciben sonido y aviso;
     * null significa todo el equipo (mesa sin mesero, nadie en turno, o un aviso escalado).
     */
    public record Audience(UUID waiterId, List<UUID> alert) {
        public static Audience everyone(UUID waiterId) {
            return new Audience(waiterId, null);
        }
    }
}
