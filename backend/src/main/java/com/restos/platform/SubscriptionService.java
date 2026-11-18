package com.restos.platform;

import com.restos.security.CurrentUser;
import com.restos.security.SubscriptionGate;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suscripción del restaurante a RestOS. El cobro de la mensualidad se registra manualmente (transferencia,
 * Yape, etc.); cada pago extiende la vigencia y el acceso se corta solo al terminar el periodo de gracia.
 */
@Service
public class SubscriptionService {
    public static final List<String> PLANS = List.of("ESENCIAL", "ESTANDAR", "PROFESIONAL");
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final SubscriptionGate gate;

    public SubscriptionService(JdbcClient jdbc, CurrentUser currentUser, SubscriptionGate gate) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.gate = gate;
    }

    /** Estado visible para el dueño del restaurante. */
    public SubscriptionView mine() {
        return view(currentUser.require().organizationId());
    }

    public SubscriptionView view(UUID organizationId) {
        return jdbc.sql("""
                        SELECT o.active,o.plan,o.monthly_fee,o.max_branches,o.trial_ends_at,o.paid_until,o.billing_note,
                          (SELECT count(*) FROM branches b WHERE b.organization_id=o.id AND b.active) branches
                        FROM organizations o WHERE o.id=:id
                        """).param("id", organizationId)
                .query((rs, row) -> build(rs.getBoolean("active"), rs.getString("plan"), rs.getBigDecimal("monthly_fee"),
                        rs.getInt("max_branches"), rs.getObject("trial_ends_at", LocalDate.class),
                        rs.getObject("paid_until", LocalDate.class), rs.getString("billing_note"), rs.getInt("branches")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado"));
    }

    SubscriptionView build(boolean active, String plan, BigDecimal fee, int maxBranches, LocalDate trialEndsAt,
                           LocalDate paidUntil, String note, int branches) {
        var today = LocalDate.now();
        var accessUntil = latest(trialEndsAt, paidUntil);
        String state;
        if (!active) state = "SUSPENDED";
        else if (paidUntil != null && !paidUntil.isBefore(today)) state = "ACTIVE";
        else if (trialEndsAt != null && !trialEndsAt.isBefore(today)) state = "TRIAL";
        else if (paidUntil != null && !paidUntil.plusDays(gate.graceDays()).isBefore(today)) state = "GRACE";
        else state = "EXPIRED";
        var daysLeft = accessUntil == null ? 0 : ChronoUnit.DAYS.between(today, accessUntil);
        var graceEndsAt = paidUntil == null ? null : paidUntil.plusDays(gate.graceDays());
        return new SubscriptionView(state, plan, fee, maxBranches, branches, trialEndsAt, paidUntil, graceEndsAt,
                daysLeft, note);
    }

    @Transactional
    public SubscriptionView update(UUID organizationId, PlanData data) {
        if (!PLANS.contains(data.plan())) throw new ApiException(HttpStatus.BAD_REQUEST, "Plan inválido");
        var branches = jdbc.sql("SELECT count(*) FROM branches WHERE organization_id=:id AND active")
                .param("id", organizationId).query(Integer.class).single();
        if (data.maxBranches() < branches) throw new ApiException(HttpStatus.CONFLICT,
                "El restaurante ya tiene " + branches + " sucursales activas");
        var updated = jdbc.sql("""
                UPDATE organizations SET plan=:plan,monthly_fee=:fee,max_branches=:max,trial_ends_at=:trial,
                  billing_note=:note,updated_at=NOW() WHERE id=:id
                """).param("plan", data.plan()).param("fee", data.monthlyFee()).param("max", data.maxBranches())
                .param("trial", data.trialEndsAt()).param("note", normalize(data.billingNote()))
                .param("id", organizationId).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado");
        return view(organizationId);
    }

    /** Un pago extiende la vigencia desde su vencimiento actual, o desde hoy si ya había vencido. */
    @Transactional
    public SubscriptionView registerPayment(UUID organizationId, BigDecimal amount, int months, String reference) {
        var scope = currentUser.require();
        // Se envuelve en un registro porque una vigencia nula es válida (primer pago del restaurante).
        var paidUntil = jdbc.sql("SELECT paid_until FROM organizations WHERE id=:id FOR UPDATE")
                .param("id", organizationId).query((rs, row) -> new PaidUntil(rs.getObject("paid_until", LocalDate.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Restaurante no encontrado"))
                .date();
        var today = LocalDate.now();
        var base = paidUntil == null || paidUntil.isBefore(today) ? today : paidUntil;
        var next = base.plusMonths(months);
        jdbc.sql("UPDATE organizations SET paid_until=:until,updated_at=NOW() WHERE id=:id")
                .param("until", next).param("id", organizationId).update();
        jdbc.sql("""
                INSERT INTO subscription_payments(organization_id,amount,months,paid_until,reference,registered_by)
                VALUES (:org,:amount,:months,:until,:reference,:user)
                """).param("org", organizationId).param("amount", amount).param("months", months)
                .param("until", next).param("reference", normalize(reference)).param("user", scope.userId()).update();
        return view(organizationId);
    }

    public List<SubscriptionPayment> payments(UUID organizationId) {
        return jdbc.sql("""
                        SELECT p.id,p.amount,p.months,p.paid_until,p.reference,p.created_at,u.full_name
                        FROM subscription_payments p JOIN app_users u ON u.id=p.registered_by
                        WHERE p.organization_id=:id ORDER BY p.created_at DESC LIMIT 50
                        """).param("id", organizationId)
                .query((rs, row) -> new SubscriptionPayment(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getInt("months"), rs.getObject("paid_until", LocalDate.class), rs.getString("reference"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("full_name"))).list();
    }

    /** Límite de sucursales del plan, verificado antes de crear una nueva. */
    public void ensureBranchAvailable(UUID organizationId) {
        var view = view(organizationId);
        if (view.activeBranches() >= view.maxBranches()) throw new ApiException(HttpStatus.CONFLICT,
                "Tu plan incluye " + view.maxBranches() + (view.maxBranches() == 1 ? " sucursal" : " sucursales")
                        + ". Comunícate con RestOS para ampliarlo");
    }

    private LocalDate latest(LocalDate first, LocalDate second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PaidUntil(LocalDate date) {}
    public record PlanData(String plan, BigDecimal monthlyFee, int maxBranches, LocalDate trialEndsAt,
                           String billingNote) {}
    public record SubscriptionView(String state, String plan, BigDecimal monthlyFee, int maxBranches,
                                   int activeBranches, LocalDate trialEndsAt, LocalDate paidUntil,
                                   LocalDate graceEndsAt, long daysLeft, String billingNote) {}
    public record SubscriptionPayment(UUID id, BigDecimal amount, int months, LocalDate paidUntil, String reference,
                                      Instant createdAt, String registeredBy) {}
}
