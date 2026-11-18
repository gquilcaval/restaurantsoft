package com.restos.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Regla única de acceso por suscripción: un restaurante entra mientras dure su prueba o su periodo
 * pagado más unos días de gracia. El propietario de la plataforma nunca queda bloqueado.
 */
@Component
public class SubscriptionGate {
    public static final String SUSPENDED_MESSAGE =
            "El servicio de este restaurante está suspendido. Comunícate con soporte de RestOS para reactivarlo";
    /**
     * Fragmento SQL sobre el alias {@code o} de organizations; requiere el parámetro {@code :graceDays}.
     * Las fechas nulas se tratan como «sin vigencia» para que el resultado nunca sea NULL.
     */
    public static final String ACCESS_SQL = "(o.id IS NULL OR COALESCE(o.trial_ends_at >= CURRENT_DATE, FALSE)"
            + " OR COALESCE(o.paid_until + :graceDays >= CURRENT_DATE, FALSE))";
    private final int graceDays;
    private final int trialDays;

    public SubscriptionGate(@Value("${restos.billing.grace-days:5}") int graceDays,
                            @Value("${restos.billing.trial-days:14}") int trialDays) {
        this.graceDays = graceDays;
        this.trialDays = trialDays;
    }

    public int graceDays() { return graceDays; }
    public int trialDays() { return trialDays; }
}
