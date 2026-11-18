package com.restos.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import com.restos.audit.AuditService;
import com.restos.cash.CashService;
import com.restos.customers.CustomerService;
import java.util.LinkedHashMap;

/** Gestiona pagos parciales y separa el cobro del cierre definitivo de la mesa. */
@Service
public class PaymentService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final CashService cash;
    private final AuditService audit;
    private final CustomerService customers;

    private final com.restos.service.AttendedCallCloser calls;

    private final com.restos.waiters.WaiterAssignmentService assignments;

    public PaymentService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime, CashService cash,
                          AuditService audit, CustomerService customers, com.restos.service.AttendedCallCloser calls,
                          com.restos.waiters.WaiterAssignmentService assignments) {
        this.assignments = assignments;
        this.calls = calls;
        this.customers = customers;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.cash = cash;
        this.audit = audit;
    }

    /** Registra un abono sin liberar la mesa; varios comensales pueden pagar por separado. */
    @Transactional
    public PaymentSummary register(UUID sessionId, String method, BigDecimal amount, String reference,
                                   BigDecimal tip, BigDecimal received, UUID guestId) {
        var scope = currentUser.require();
        if (!ListMethods.VALID.contains(method)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Método de pago inválido");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El monto debe ser mayor que cero");
        }
        var context = lockOpenSession(sessionId, scope.branchId());
        var checkout = jdbc.sql("""
                SELECT count(*) FROM service_requests
                WHERE session_id=:session AND type='BILL' AND status='IN_PROGRESS'
                """).param("session", sessionId).query(Integer.class).single();
        if (checkout == 0) throw new ApiException(HttpStatus.CONFLICT,
                "Primero debes preparar la cuenta para cerrar el consumo");
        // En mesa se cobra al final del servicio; para llevar y delivery se suele cobrar por adelantado.
        if (context.channel().equals("DINE_IN")) ensureAllItemsDelivered(sessionId);
        if (guestId != null) {
            var belongs = jdbc.sql("SELECT count(*) FROM guests WHERE id=:guest AND session_id=:session")
                    .param("guest", guestId).param("session", sessionId).query(Integer.class).single();
            if (belongs == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "El comensal no pertenece a esta mesa");
        }
        var before = summary(sessionId);
        if (amount.compareTo(before.remaining()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "El monto supera el saldo pendiente");
        }
        var cleanTip = tip == null ? BigDecimal.ZERO : tip;
        if (cleanTip.signum() < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "La propina no puede ser negativa");
        // El vuelto solo existe en efectivo: lo recibido cubre el pago más la propina.
        BigDecimal change = BigDecimal.ZERO;
        if (received != null) {
            if (!method.equals("CASH")) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "El monto recibido solo se registra para pagos en efectivo");
            var due = amount.add(cleanTip);
            if (received.compareTo(due) < 0) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "El efectivo recibido no cubre el pago y la propina");
            change = received.subtract(due);
        }

        // Un cobro sin caja abierta sigue siendo válido, pero queda explícitamente sin turno asignado.
        cash.lockBranch(context.branchId());
        var shiftId = cash.openShiftId(context.branchId());
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments(id,organization_id,branch_id,session_id,method,amount,reference,registered_by,
                  cash_shift_id,tip,amount_received,change_given,guest_id)
                VALUES (:id,:org,:branch,:session,:method,:amount,:reference,:user,:shift,:tip,:received,:change,:guest)
                """).param("id", id).param("org", context.organizationId()).param("branch", context.branchId())
                .param("session", sessionId).param("method", method).param("amount", amount)
                .param("reference", normalize(reference)).param("user", scope.userId()).param("shift",shiftId)
                .param("tip", cleanTip).param("received", received).param("change", change)
                .param("guest", guestId).update();
        assignments.claimIfUnassigned(sessionId);
        var result = summary(sessionId);
        audit.record("PAYMENT_REGISTERED", "Cobro en " + methodLabel(method) + " · " + label(sessionId)
                + (cleanTip.signum() > 0 ? " · propina S/ " + cleanTip.toPlainString() : ""), amount, sessionId);
        realtime.table(sessionId, "PAYMENT_REGISTERED", result);
        realtime.waiters(context.branchId(), "PAYMENT_REGISTERED", result);
        calls.closeForSession(sessionId);
        return result;
    }

    /** Inicia el cobro desde el panel aunque la solicitud haya sido verbal y no enviada por QR. */
    @Transactional
    public CheckoutView beginCheckout(UUID sessionId) {
        var scope = currentUser.require();
        var context = lockOpenSession(sessionId, scope.branchId());
        var label = jdbc.sql("""
                SELECT COALESCE(t.label,ts.label) FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=:session
                """).param("session", sessionId).query(String.class).single();
        var active = jdbc.sql("""
                SELECT id,status,requested_at FROM service_requests
                WHERE session_id=:session AND type='BILL' AND status IN ('PENDING','IN_PROGRESS')
                """).param("session", sessionId)
                .query((rs, row) -> new CheckoutView(rs.getObject("id", UUID.class), sessionId, label,
                        rs.getString("status"), rs.getTimestamp("requested_at").toInstant())).optional();
        CheckoutView result;
        if (active.isPresent()) {
            var current = active.get();
            if (current.status().equals("PENDING")) {
                jdbc.sql("UPDATE service_requests SET status='IN_PROGRESS',handled_by=:user,updated_at=NOW() WHERE id=:id")
                        .param("user", scope.userId()).param("id", current.id()).update();
            }
            result = new CheckoutView(current.id(), sessionId, label, "IN_PROGRESS", current.requestedAt());
        } else {
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO service_requests(id,organization_id,branch_id,session_id,type,status,handled_by)
                    VALUES (:id,:org,:branch,:session,'BILL','IN_PROGRESS',:user)
                    """).param("id", id).param("org", context.organizationId()).param("branch", context.branchId())
                    .param("session", sessionId).param("user", scope.userId()).update();
            result = new CheckoutView(id, sessionId, label, "IN_PROGRESS", Instant.now());
        }
        realtime.table(sessionId, "SERVICE_REQUEST_IN_PROGRESS", result);
        realtime.waiters(context.branchId(), "SERVICE_REQUEST_IN_PROGRESS", result);
        return result;
    }

    /** Devuelve total, pagos acumulados y saldo para recuperar el cobro tras una recarga. */
    public PaymentSummary summaryForStaff(UUID sessionId) {
        var scope = currentUser.require();
        var exists = jdbc.sql("SELECT count(*) FROM table_sessions WHERE id=:session AND branch_id=:branch")
                .param("session", sessionId).param("branch", scope.branchId()).query(Integer.class).single();
        if (exists == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Sesión no encontrada");
        return summary(sessionId);
    }

    /** Libera la mesa únicamente cuando el consumo está servido y pagado por completo. */
    @Transactional
    public ClosedSession close(UUID sessionId) {
        var scope = currentUser.require();
        var context = lockOpenSession(sessionId, scope.branchId());
        ensureAllItemsDelivered(sessionId);
        var balance = summary(sessionId);
        if (balance.subtotal().add(balance.deliveryFee()).compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "La mesa no tiene consumos por cobrar; cancela la atención para liberarla");
        }
        if (balance.remaining().compareTo(BigDecimal.ZERO) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Todavía existe un saldo pendiente de " + balance.remaining());
        }
        var drafts = jdbc.sql("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.session_id=:session AND o.status='DRAFT'
                """).param("session", sessionId).query(Integer.class).single();
        if (drafts > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Hay productos sin enviar en la selección de la mesa; deben quitarse antes de cerrar");
        }
        jdbc.sql("""
                UPDATE service_requests SET status='RESOLVED',handled_by=:user,handled_at=NOW(),updated_at=NOW()
                WHERE session_id=:session AND status IN ('PENDING','IN_PROGRESS')
                """).param("user", scope.userId()).param("session", sessionId).update();
        jdbc.sql("UPDATE guests SET active=FALSE,left_at=COALESCE(left_at,NOW()),updated_at=NOW() WHERE session_id=:session AND active")
                .param("session", sessionId).update();
        jdbc.sql("""
                UPDATE table_sessions SET status='CLOSED',closed_at=NOW(),closed_by=:user,
                  close_reason='Cuenta pagada y atención cerrada',updated_at=NOW() WHERE id=:session
                """).param("session", sessionId).param("user", scope.userId()).update();
        var tableLabel = jdbc.sql("""
                SELECT COALESCE(t.label,ts.label) FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=:session
                """).param("session", sessionId).query(String.class).single();
        var result = new ClosedSession(sessionId, tableLabel, Instant.now());
        audit.record("SESSION_CLOSED", "Cuenta cerrada · " + tableLabel, balance.paid(), sessionId);
        customers.onSessionClosed(sessionId, balance.paid());
        // Con comanda impresa nadie marca «listo»: al cerrar la cuenta, lo que quedaba se da por servido.
        jdbc.sql("""
                UPDATE order_items oi SET status='DELIVERED',updated_at=NOW()
                FROM orders o
                WHERE o.id=oi.order_id AND o.session_id=:session
                  AND oi.status IN ('SUBMITTED','PREPARING','READY') AND EXISTS (
                    SELECT 1 FROM print_routes pr WHERE pr.branch_id=o.branch_id
                      AND pr.station_id IS NOT DISTINCT FROM (SELECT mc.station_id FROM menu_items mi JOIN menu_categories mc ON mc.id=mi.category_id WHERE mi.id=oi.menu_item_id))
                """).param("session", sessionId).update();
        jdbc.sql("""
                UPDATE orders o SET status='DELIVERED',updated_at=NOW() WHERE o.session_id=:session AND o.status IN ('SUBMITTED','PREPARING','READY')
                  AND NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id=o.id AND oi.status NOT IN ('DELIVERED','CANCELLED'))
                """).param("session", sessionId).update();
        realtime.table(sessionId, "TABLE_SESSION_CLOSED", result);
        realtime.waiters(context.branchId(), "TABLE_SESSION_CLOSED", result);
        return result;
    }

    private Context lockOpenSession(UUID sessionId, UUID branchId) {
        var context = jdbc.sql("""
                        SELECT organization_id,branch_id,status,channel FROM table_sessions
                        WHERE id=:session AND branch_id=:branch FOR UPDATE
                        """).param("session", sessionId).param("branch", branchId)
                .query((rs, row) -> new Context(rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getString("status"), rs.getString("channel")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sesión no encontrada"));
        if (!context.status().equals("OPEN")) throw new ApiException(HttpStatus.CONFLICT, "La mesa ya está cerrada");
        return context;
    }

    private void ensureAllItemsDelivered(UUID sessionId) {
        var pending = jdbc.sql("""
                SELECT count(*) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.session_id=:session AND oi.status NOT IN ('DELIVERED','CANCELLED') AND o.status <> 'DRAFT'
                """).param("session", sessionId).query(Integer.class).single();
        if (pending > 0) throw new ApiException(HttpStatus.CONFLICT, "Hay productos pendientes de servir");
    }

    /** Total, pagos, saldo, estado de la cuenta y reparto por comensal para cobrar por separado. */
    public PaymentSummary summary(UUID sessionId) {
        var consumption = jdbc.sql("""
                        SELECT oi.guest_id,COALESCE(sum(oi.unit_price*oi.quantity),0) amount FROM order_items oi
                        JOIN orders o ON o.id=oi.order_id
                        WHERE o.session_id=:session AND o.status <> 'DRAFT' AND oi.status <> 'CANCELLED'
                        GROUP BY oi.guest_id
                        """).param("session", sessionId)
                .query((rs, row) -> new Consumption(rs.getObject("guest_id", UUID.class), rs.getBigDecimal("amount")))
                .list();
        var subtotal = consumption.stream().map(Consumption::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var session = jdbc.sql("""
                SELECT discount_kind,discount_value,discount_reason,delivery_fee FROM table_sessions WHERE id=:session
                """).param("session", sessionId)
                .query((rs, row) -> new SessionCharges(rs.getString("discount_kind") == null ? null
                        : new DiscountView(rs.getString("discount_kind"), rs.getBigDecimal("discount_value"),
                        discountAmount(rs.getString("discount_kind"), rs.getBigDecimal("discount_value"), subtotal),
                        rs.getString("discount_reason")), rs.getBigDecimal("delivery_fee")))
                .optional().orElse(new SessionCharges(null, BigDecimal.ZERO));
        var discount = session.discount();
        var discountAmount = discount == null ? BigDecimal.ZERO : discount.amount();
        var total = subtotal.subtract(discountAmount).add(session.deliveryFee());
        var entries = jdbc.sql("""
                SELECT id,method,amount,reference,paid_at,tip,amount_received,change_given,guest_id FROM payments
                WHERE session_id=:session ORDER BY paid_at
                """).param("session", sessionId)
                .query((rs, row) -> new PaymentView(rs.getObject("id", UUID.class), rs.getString("method"),
                        rs.getBigDecimal("amount"), rs.getString("reference"),
                        rs.getTimestamp("paid_at").toInstant(), rs.getBigDecimal("tip"),
                        rs.getBigDecimal("amount_received"), rs.getBigDecimal("change_given"),
                        rs.getObject("guest_id", UUID.class))).list();
        var paid = entries.stream().map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var tips = entries.stream().map(PaymentView::tip).reduce(BigDecimal.ZERO, BigDecimal::add);
        var bill = jdbc.sql("""
                SELECT id,status FROM service_requests
                WHERE session_id=:session AND type='BILL' AND status IN ('PENDING','IN_PROGRESS')
                """).param("session", sessionId)
                .query((rs, row) -> new BillState(rs.getObject("id", UUID.class), rs.getString("status")))
                .optional().orElse(null);
        var preference = jdbc.sql("""
                        SELECT bill_split_mode,bill_split_parts,bill_tip_percent,bill_note FROM table_sessions
                        WHERE id=:session AND bill_split_mode IS NOT NULL
                        """).param("session", sessionId)
                .query((rs, row) -> new BillPreference(rs.getString("bill_split_mode"),
                        (Integer) rs.getObject("bill_split_parts", Integer.class),
                        rs.getInt("bill_tip_percent"), rs.getString("bill_note"))).optional().orElse(null);
        return new PaymentSummary(sessionId, subtotal, discount, session.deliveryFee(), total, paid,
                total.subtract(paid).max(BigDecimal.ZERO), tips, entries,
                shares(sessionId, consumption, subtotal, discountAmount, entries), bill, preference);
    }

    /**
     * Parte de cada comensal: su consumo menos la porción proporcional del descuento.
     * Lo pedido por el mesero queda como consumo compartido de la mesa.
     */
    private List<GuestShare> shares(UUID sessionId, List<Consumption> consumption, BigDecimal subtotal,
                                    BigDecimal discount, List<PaymentView> entries) {
        var names = new LinkedHashMap<UUID, String>();
        jdbc.sql("SELECT id,display_name FROM guests WHERE session_id=:session ORDER BY created_at")
                .param("session", sessionId)
                .query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("display_name"))).list();
        return consumption.stream().map(item -> {
            var share = subtotal.signum() == 0 ? BigDecimal.ZERO
                    : discount.multiply(item.amount()).divide(subtotal, 2, RoundingMode.HALF_UP);
            var paidByGuest = item.guestId() == null ? BigDecimal.ZERO : entries.stream()
                    .filter(entry -> item.guestId().equals(entry.guestId()))
                    .map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var due = item.amount().subtract(share);
            return new GuestShare(item.guestId(), item.guestId() == null ? "Compartido de la mesa"
                    : names.getOrDefault(item.guestId(), "Comensal"), item.amount(), due, paidByGuest,
                    due.subtract(paidByGuest).max(BigDecimal.ZERO));
        }).sorted((a, b) -> Boolean.compare(a.guestId() == null, b.guestId() == null)).toList();
    }

    /** Un porcentaje se redondea al céntimo; un monto fijo nunca supera el consumo. */
    public static BigDecimal discountAmount(String kind, BigDecimal value, BigDecimal subtotal) {
        var amount = kind.equals("PERCENT")
                ? subtotal.multiply(value).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : value;
        return amount.min(subtotal).max(BigDecimal.ZERO);
    }

    /** Aplica o retira el descuento de la atención; solo antes de registrar pagos para no alterar abonos. */
    @Transactional
    public PaymentSummary applyDiscount(UUID sessionId, String kind, BigDecimal value, String reason) {
        var scope = currentUser.require();
        var context = lockOpenSession(sessionId, scope.branchId());
        var payments = jdbc.sql("SELECT count(*) FROM payments WHERE session_id=:session")
                .param("session", sessionId).query(Integer.class).single();
        if (payments > 0) throw new ApiException(HttpStatus.CONFLICT,
                "La cuenta ya tiene pagos registrados; el descuento no puede modificarse");
        var redeemed = jdbc.sql("SELECT redeemed_points FROM table_sessions WHERE id=:session")
                .param("session", sessionId).query(Integer.class).single();
        if (redeemed > 0) throw new ApiException(HttpStatus.CONFLICT,
                "La cuenta tiene un canje de puntos; cancélalo antes de cambiar el descuento");
        if (kind == null) {
            jdbc.sql("""
                    UPDATE table_sessions SET discount_kind=NULL,discount_value=NULL,discount_reason=NULL,
                      discount_by=:user,discount_at=NOW(),updated_at=NOW() WHERE id=:session
                    """).param("user", scope.userId()).param("session", sessionId).update();
        } else {
            if (!kind.equals("PERCENT") && !kind.equals("AMOUNT")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Tipo de descuento inválido");
            }
            if (value == null || value.signum() <= 0
                    || (kind.equals("PERCENT") && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "El descuento debe ser mayor que cero"
                        + (kind.equals("PERCENT") ? " y no superar el 100 %" : ""));
            }
            var cleanReason = normalize(reason);
            if (cleanReason == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el motivo del descuento");
            jdbc.sql("""
                    UPDATE table_sessions SET discount_kind=:kind,discount_value=:value,discount_reason=:reason,
                      discount_by=:user,discount_at=NOW(),updated_at=NOW() WHERE id=:session
                    """).param("kind", kind).param("value", value).param("reason", cleanReason)
                    .param("user", scope.userId()).param("session", sessionId).update();
        }
        var result = summary(sessionId);
        audit.record(kind == null ? "DISCOUNT_REMOVED" : "DISCOUNT_APPLIED", kind == null
                ? "Descuento retirado · " + label(sessionId)
                : "Descuento " + (kind.equals("PERCENT") ? value.stripTrailingZeros().toPlainString() + " %" : "S/ " + value.toPlainString())
                        + " · " + label(sessionId) + " · " + normalize(reason),
                result.discount() == null ? null : result.discount().amount(), sessionId);
        realtime.table(sessionId, "DISCOUNT_UPDATED", result);
        realtime.waiters(context.branchId(), "DISCOUNT_UPDATED", result);
        return result;
    }

    private String label(UUID sessionId) {
        return jdbc.sql("""
                SELECT COALESCE(t.label,ts.label) FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                WHERE ts.id=:session
                """).param("session", sessionId).query(String.class).single();
    }

    private static String methodLabel(String method) {
        return switch (method) { case "CASH" -> "efectivo"; case "CARD" -> "tarjeta"; case "YAPE" -> "Yape";
            case "PLIN" -> "Plin"; default -> method; };
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class ListMethods {
        private static final java.util.Set<String> VALID = java.util.Set.of("CASH", "CARD", "YAPE", "PLIN");
    }
    private record Context(UUID organizationId, UUID branchId, String status, String channel) {}
    public record PaymentView(UUID id, String method, BigDecimal amount, String reference, Instant paidAt,
                              BigDecimal tip, BigDecimal amountReceived, BigDecimal changeGiven, UUID guestId) {}
    public record GuestShare(UUID guestId, String name, BigDecimal consumed, BigDecimal due, BigDecimal paid,
                             BigDecimal pending) {}
    public record BillState(UUID requestId, String status) {}
    private record Consumption(UUID guestId, BigDecimal amount) {}
    private record SessionCharges(DiscountView discount, BigDecimal deliveryFee) {}
    public record DiscountView(String kind, BigDecimal value, BigDecimal amount, String reason) {}
    public record PaymentSummary(UUID sessionId, BigDecimal subtotal, DiscountView discount, BigDecimal deliveryFee,
                                 BigDecimal total, BigDecimal paid, BigDecimal remaining, BigDecimal tips,
                                 List<PaymentView> payments, List<GuestShare> shares, BillState bill,
                                 BillPreference preference) {}
    /** Lo que eligió la mesa al pedir la cuenta desde el celular. */
    public record BillPreference(String mode, Integer parts, int tipPercent, String note) {}
    public record CheckoutView(UUID id, UUID sessionId, String tableLabel, String status, Instant requestedAt) {}
    public record ClosedSession(UUID sessionId, String tableLabel, Instant closedAt) {}
}
