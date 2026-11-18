package com.restos.counter;

import com.restos.customers.CustomerService;
import com.restos.payments.PaymentService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedidos para llevar y delivery. Se modelan como atenciones sin mesa para reutilizar cocina, cobro,
 * caja y reportes; cada uno recibe un número correlativo diario por sucursal.
 */
@Service
public class CounterOrderService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final PaymentService payments;
    private final ZoneId zone;
    private final CustomerService customers;

    public CounterOrderService(JdbcClient jdbc, CurrentUser currentUser, RealtimePublisher realtime,
                               PaymentService payments, @Value("${restos.business-zone:America/Lima}") String zone,
                               CustomerService customers) {
        this.customers = customers;
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.payments = payments;
        this.zone = ZoneId.of(zone);
    }

    public List<CounterOrderView> active() {
        var branch = currentUser.require().branchId();
        return jdbc.sql("SELECT id FROM table_sessions WHERE branch_id=:branch AND channel<>'DINE_IN' AND status='OPEN' ORDER BY opened_at")
                .param("branch", branch).query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public CounterOrderView create(CustomerData data) {
        var scope = currentUser.require();
        validate(data);
        // El bloqueo de la sucursal evita que dos cajas entreguen el mismo número de pedido.
        jdbc.sql("SELECT id FROM branches WHERE id=:branch FOR UPDATE").param("branch", scope.branchId())
                .query(UUID.class).single();
        var dayStart = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        var number = jdbc.sql("""
                SELECT COALESCE(max(order_number),0)+1 FROM table_sessions
                WHERE branch_id=:branch AND channel<>'DINE_IN' AND opened_at>=:start
                """).param("branch", scope.branchId()).param("start", dayStart).query(Integer.class).single();
        var id = UUID.randomUUID();
        var label = (data.channel().equals("DELIVERY") ? "Delivery #" : "Para llevar #") + number;
        // Con celular, el pedido queda en la ficha del cliente y acumula puntos al cerrarse.
        var customerId = normalize(data.customerPhone()) == null ? null
                : customers.findOrCreate(data.customerName().trim(), data.customerPhone());
        jdbc.sql("""
                INSERT INTO table_sessions(id,organization_id,branch_id,status,opened_by,opened_source,channel,label,
                  order_number,customer_name,customer_phone,delivery_address,delivery_reference,delivery_fee,customer_id)
                VALUES (:id,:org,:branch,'OPEN',:user,'COUNTER',:channel,:label,:number,:name,:phone,:address,:reference,:fee,:customer)
                """).param("id", id).param("org", scope.organizationId()).param("branch", scope.branchId())
                .param("user", scope.userId()).param("channel", data.channel()).param("label", label)
                .param("number", number).param("name", data.customerName().trim())
                .param("phone", normalize(data.customerPhone())).param("address", normalize(data.deliveryAddress()))
                .param("reference", normalize(data.deliveryReference())).param("fee", fee(data))
                .param("customer", customerId).update();
        var view = view(id);
        realtime.waiters(scope.branchId(), "COUNTER_ORDER_CREATED", view);
        return view;
    }

    @Transactional
    public CounterOrderView update(UUID sessionId, CustomerData data) {
        var scope = currentUser.require();
        var channel = jdbc.sql("""
                SELECT channel FROM table_sessions
                WHERE id=:id AND branch_id=:branch AND channel<>'DINE_IN' AND status='OPEN' FOR UPDATE
                """).param("id", sessionId).param("branch", scope.branchId()).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pedido no encontrado"));
        var paidCount = jdbc.sql("SELECT count(*) FROM payments WHERE session_id=:id").param("id", sessionId)
                .query(Integer.class).single();
        var normalized = new CustomerData(channel, data.customerName(), data.customerPhone(), data.deliveryAddress(),
                data.deliveryReference(), data.deliveryFee());
        validate(normalized);
        var currentFee = jdbc.sql("SELECT delivery_fee FROM table_sessions WHERE id=:id").param("id", sessionId)
                .query(BigDecimal.class).single();
        if (paidCount > 0 && currentFee.compareTo(fee(normalized)) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "El pedido ya tiene pagos; el costo de envío no puede cambiar");
        }
        jdbc.sql("""
                UPDATE table_sessions SET customer_name=:name,customer_phone=:phone,delivery_address=:address,
                  delivery_reference=:reference,delivery_fee=:fee,updated_at=NOW() WHERE id=:id
                """).param("name", normalized.customerName().trim()).param("phone", normalize(normalized.customerPhone()))
                .param("address", normalize(normalized.deliveryAddress()))
                .param("reference", normalize(normalized.deliveryReference())).param("fee", fee(normalized))
                .param("id", sessionId).update();
        var view = view(sessionId);
        realtime.waiters(scope.branchId(), "COUNTER_ORDER_UPDATED", view);
        return view;
    }

    private CounterOrderView view(UUID sessionId) {
        var base = jdbc.sql("""
                        SELECT ts.id,ts.channel,ts.label,ts.order_number,ts.customer_name,ts.customer_phone,
                          ts.delivery_address,ts.delivery_reference,ts.delivery_fee,ts.opened_at,
                          count(oi.id) FILTER (WHERE o.status<>'DRAFT' AND oi.status<>'CANCELLED') items,
                          count(oi.id) FILTER (WHERE o.status<>'DRAFT' AND oi.status IN ('SUBMITTED','PREPARING')) in_kitchen,
                          count(oi.id) FILTER (WHERE o.status<>'DRAFT' AND oi.status='READY') ready,
                          count(oi.id) FILTER (WHERE o.status<>'DRAFT' AND oi.status='DELIVERED') delivered
                        FROM table_sessions ts
                        LEFT JOIN orders o ON o.session_id=ts.id
                        LEFT JOIN order_items oi ON oi.order_id=o.id
                        WHERE ts.id=:id GROUP BY ts.id
                        """).param("id", sessionId)
                .query((rs, row) -> new Base(rs.getObject("id", UUID.class), rs.getString("channel"),
                        rs.getString("label"), rs.getInt("order_number"), rs.getString("customer_name"),
                        rs.getString("customer_phone"), rs.getString("delivery_address"),
                        rs.getString("delivery_reference"), rs.getBigDecimal("delivery_fee"),
                        rs.getTimestamp("opened_at").toInstant(), rs.getInt("items"), rs.getInt("in_kitchen"),
                        rs.getInt("ready"), rs.getInt("delivered"))).single();
        var summary = payments.summary(sessionId);
        var stage = base.items() == 0 ? "NEW"
                : base.inKitchen() > 0 ? "IN_KITCHEN"
                : base.ready() > 0 ? "READY" : "DELIVERED";
        return new CounterOrderView(base.id(), base.channel(), base.label(), base.orderNumber(), base.customerName(),
                base.customerPhone(), base.deliveryAddress(), base.deliveryReference(), base.deliveryFee(),
                base.openedAt(), base.items(), stage, summary.total(), summary.paid(), summary.remaining());
    }

    private void validate(CustomerData data) {
        if (!data.channel().equals("TAKEAWAY") && !data.channel().equals("DELIVERY")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tipo de pedido inválido");
        }
        if (data.customerName() == null || data.customerName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el nombre del cliente");
        }
        if (data.channel().equals("DELIVERY")
                && (normalize(data.deliveryAddress()) == null || normalize(data.customerPhone()) == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Un delivery necesita dirección y teléfono de contacto");
        }
    }

    private BigDecimal fee(CustomerData data) {
        return data.channel().equals("DELIVERY") && data.deliveryFee() != null ? data.deliveryFee() : BigDecimal.ZERO;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Base(UUID id, String channel, String label, int orderNumber, String customerName,
                        String customerPhone, String deliveryAddress, String deliveryReference, BigDecimal deliveryFee,
                        Instant openedAt, int items, int inKitchen, int ready, int delivered) {}
    public record CustomerData(String channel, String customerName, String customerPhone, String deliveryAddress,
                               String deliveryReference, BigDecimal deliveryFee) {}
    public record CounterOrderView(UUID sessionId, String channel, String label, int orderNumber, String customerName,
                                   String customerPhone, String deliveryAddress, String deliveryReference,
                                   BigDecimal deliveryFee, Instant openedAt, int itemCount, String stage,
                                   BigDecimal total, BigDecimal paid, BigDecimal remaining) {}
}
