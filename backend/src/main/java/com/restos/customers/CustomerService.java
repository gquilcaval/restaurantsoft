package com.restos.customers;

import com.restos.audit.AuditService;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clientes frecuentes de la organización (compartidos entre sucursales) y su programa de puntos.
 * El celular identifica al cliente; los puntos se ganan al cerrar la cuenta y se canjean como descuento.
 */
@Service
public class CustomerService {
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final RealtimePublisher realtime;

    private static final String ZONE = "America/Lima";

    public CustomerService(JdbcClient jdbc, CurrentUser currentUser, AuditService audit, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
        this.realtime = realtime;
    }

    public List<CustomerView> search(String query, String sort) { return search(query, sort, "ALL"); }

    /** Búsqueda con grupo: frecuentes, para recuperar (no vuelven hace 30 días), cumpleaños del mes o nuevos. */
    public List<CustomerView> search(String query, String sort, String segment) {
        var org = currentUser.require().organizationId();
        var term = query == null ? "" : query.trim().toLowerCase();
        var digits = term.replaceAll("\\D", "");
        var order = switch (sort == null ? "" : sort) {
            case "spent" -> "c.total_spent DESC";
            case "visits" -> "c.visits DESC";
            case "points" -> "c.points DESC";
            default -> "c.last_visit_at DESC NULLS LAST, c.created_at DESC";
        };
        var filter = switch (segment == null ? "" : segment) {
            case "FREQUENT" -> " AND c.visits>=3";
            case "AT_RISK" -> " AND c.visits>=2 AND c.last_visit_at < now() - interval '30 days'";
            case "BIRTHDAY" -> " AND extract(month FROM c.birthday)=extract(month FROM now() AT TIME ZONE '" + ZONE + "')";
            case "NEW" -> " AND c.created_at >= now() - interval '30 days'";
            default -> "";
        };
        if ("BIRTHDAY".equals(segment)) order = "extract(day FROM c.birthday), c.full_name";
        return jdbc.sql("""
                        SELECT c.* FROM customers c WHERE c.organization_id=:org
                          AND (:term='' OR lower(c.full_name) LIKE '%'||:term||'%' OR (:digits<>'' AND c.phone LIKE '%'||:digits||'%'))
                        """ + filter + " ORDER BY " + order + " LIMIT 200")
                .param("org", org).param("term", term).param("digits", digits)
                .query((rs, row) -> map(rs)).list();
    }

    /** Cifras de la cartera de clientes para la cabecera del módulo. */
    public Summary summary() {
        var org = currentUser.require().organizationId();
        return jdbc.sql("""
                        SELECT count(*) total,
                          count(*) FILTER (WHERE visits>=3) frequent,
                          count(*) FILTER (WHERE visits>=2 AND last_visit_at < now() - interval '30 days') at_risk,
                          count(*) FILTER (WHERE extract(month FROM birthday)=extract(month FROM now() AT TIME ZONE :zone)) birthdays,
                          count(*) FILTER (WHERE created_at >= now() - interval '30 days') new_customers,
                          count(*) FILTER (WHERE last_visit_at >= now() - interval '30 days') active,
                          COALESCE(sum(points),0) points,
                          COALESCE(avg(total_spent) FILTER (WHERE visits>0),0) avg_spent
                        FROM customers WHERE organization_id=:org
                        """)
                .param("org", org).param("zone", ZONE)
                .query((rs, row) -> new Summary(rs.getLong("total"), rs.getLong("frequent"), rs.getLong("at_risk"),
                        rs.getLong("birthdays"), rs.getLong("new_customers"), rs.getLong("active"), rs.getLong("points"),
                        rs.getBigDecimal("avg_spent").setScale(2, RoundingMode.HALF_UP)))
                .single();
    }

    public CustomerDetail detail(UUID id) {
        var customer = find(id);
        var visits = jdbc.sql("""
                        SELECT ts.id,ts.closed_at,COALESCE(t.label,ts.label) label,b.name branch,
                          (SELECT COALESCE(sum(p.amount),0) FROM payments p WHERE p.session_id=ts.id) paid
                        FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id JOIN branches b ON b.id=ts.branch_id
                        WHERE ts.customer_id=:id AND ts.status='CLOSED' ORDER BY ts.closed_at DESC LIMIT 20
                        """).param("id", id)
                .query((rs, row) -> new Visit(rs.getObject("id", UUID.class), rs.getTimestamp("closed_at").toInstant(),
                        rs.getString("label"), rs.getString("branch"), rs.getBigDecimal("paid"))).list();
        var movements = jdbc.sql("""
                        SELECT id,points,reason,created_at FROM loyalty_movements WHERE customer_id=:id
                        ORDER BY created_at DESC LIMIT 30
                        """).param("id", id)
                .query((rs, row) -> new PointsMovement(rs.getObject("id", UUID.class), rs.getInt("points"),
                        rs.getString("reason"), rs.getTimestamp("created_at").toInstant())).list();
        var favorites = jdbc.sql("""
                        SELECT oi.item_name,sum(oi.quantity) quantity FROM order_items oi JOIN orders o ON o.id=oi.order_id
                        JOIN table_sessions ts ON ts.id=o.session_id
                        WHERE ts.customer_id=:id AND oi.status<>'CANCELLED' AND o.status<>'DRAFT'
                        GROUP BY oi.item_name ORDER BY quantity DESC LIMIT 5
                        """).param("id", id)
                .query((rs, row) -> new Favorite(rs.getString("item_name"), rs.getInt("quantity"))).list();
        return new CustomerDetail(customer, visits, movements, favorites);
    }

    @Transactional
    public CustomerView save(UUID id, CustomerData data) {
        var org = currentUser.require().organizationId();
        var phone = normalizePhone(data.phone());
        var duplicate = jdbc.sql("SELECT count(*) FROM customers WHERE organization_id=:org AND phone=:phone AND id<>:id")
                .param("org", org).param("phone", phone).param("id", id == null ? new UUID(0, 0) : id)
                .query(Integer.class).single();
        if (duplicate > 0) throw new ApiException(HttpStatus.CONFLICT, "Ya existe un cliente con ese celular");
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO customers(id,organization_id,full_name,phone,email,birthday,notes)
                    VALUES (:id,:org,:name,:phone,:email,:birthday,:notes)
                    """).param("id", id).param("org", org).param("name", data.fullName().trim()).param("phone", phone)
                    .param("email", normalize(data.email())).param("birthday", data.birthday())
                    .param("notes", normalize(data.notes())).update();
        } else {
            var updated = jdbc.sql("""
                    UPDATE customers SET full_name=:name,phone=:phone,email=:email,birthday=:birthday,notes=:notes
                    WHERE id=:id AND organization_id=:org
                    """).param("name", data.fullName().trim()).param("phone", phone).param("email", normalize(data.email()))
                    .param("birthday", data.birthday()).param("notes", normalize(data.notes()))
                    .param("id", id).param("org", org).update();
            if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
        return find(id);
    }

    /** Busca por celular y, si no existe, lo registra; usado por mostrador y reservas. */
    @Transactional
    public UUID findOrCreate(String fullName, String phone) {
        var org = currentUser.require().organizationId();
        var normalized = normalizePhone(phone);
        return jdbc.sql("SELECT id FROM customers WHERE organization_id=:org AND phone=:phone")
                .param("org", org).param("phone", normalized).query(UUID.class).optional()
                .orElseGet(() -> save(null, new CustomerData(fullName, normalized, null, null, null)).id());
    }

    /** Asocia (o retira) el cliente de una atención abierta para que acumule puntos al cerrar. */
    @Transactional
    public LoyaltyState link(UUID sessionId, UUID customerId) {
        var scope = currentUser.require();
        var session = lockSession(sessionId);
        if (session.redeemedPoints() > 0) throw new ApiException(HttpStatus.CONFLICT,
                "Primero cancela el canje de puntos para cambiar de cliente");
        if (customerId != null) find(customerId);
        jdbc.sql("UPDATE table_sessions SET customer_id=:customer,updated_at=NOW() WHERE id=:id")
                .param("customer", customerId).param("id", sessionId).update();
        realtime.waiters(scope.branchId(), "SESSION_CUSTOMER_UPDATED", new SessionRef(sessionId));
        return state(sessionId);
    }

    /** Canjea puntos como un descuento en monto fijo, antes de registrar pagos. */
    @Transactional
    public LoyaltyState redeem(UUID sessionId, int points) {
        var session = lockSession(sessionId);
        var program = program();
        if (!program.enabled()) throw new ApiException(HttpStatus.CONFLICT, "El programa de puntos está desactivado");
        if (session.customerId() == null) throw new ApiException(HttpStatus.CONFLICT, "Asocia un cliente a la cuenta para canjear puntos");
        if (session.discountKind() != null) throw new ApiException(HttpStatus.CONFLICT, "La cuenta ya tiene un descuento aplicado");
        if (paymentsCount(sessionId) > 0) throw new ApiException(HttpStatus.CONFLICT, "Los puntos se canjean antes de registrar pagos");
        var customer = jdbc.sql("SELECT full_name,points FROM customers WHERE id=:id FOR UPDATE").param("id", session.customerId())
                .query((rs, row) -> new Balance(rs.getString("full_name"), rs.getInt("points"))).single();
        if (points < program.minRedeem()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "El canje mínimo es de " + program.minRedeem() + " puntos");
        if (points > customer.points()) throw new ApiException(HttpStatus.CONFLICT,
                customer.name() + " tiene " + customer.points() + " puntos");
        var subtotal = subtotal(sessionId);
        // Nunca se canjean más puntos de los necesarios para cubrir el consumo.
        var maxPoints = subtotal.divide(program.pointValue(), 0, RoundingMode.FLOOR).intValue();
        var used = Math.min(points, maxPoints);
        if (used < program.minRedeem()) throw new ApiException(HttpStatus.CONFLICT, "El consumo es muy bajo para canjear puntos");
        var amount = program.pointValue().multiply(BigDecimal.valueOf(used)).setScale(2, RoundingMode.HALF_UP);
        jdbc.sql("""
                UPDATE table_sessions SET discount_kind='AMOUNT',discount_value=:amount,discount_reason=:reason,
                  discount_by=:user,discount_at=NOW(),redeemed_points=:points,updated_at=NOW() WHERE id=:id
                """).param("amount", amount).param("reason", "Canje de " + used + " puntos")
                .param("user", currentUser.require().userId()).param("points", used).param("id", sessionId).update();
        addPoints(session.customerId(), sessionId, -used, "Canje en " + session.label());
        audit.record("POINTS_REDEEMED", customer.name() + " canjeó " + used + " puntos · " + session.label(), amount, sessionId);
        realtime.waiters(currentUser.require().branchId(), "DISCOUNT_UPDATED", new SessionRef(sessionId));
        return state(sessionId);
    }

    @Transactional
    public LoyaltyState cancelRedeem(UUID sessionId) {
        var session = lockSession(sessionId);
        if (session.redeemedPoints() == 0) throw new ApiException(HttpStatus.CONFLICT, "La cuenta no tiene puntos canjeados");
        if (paymentsCount(sessionId) > 0) throw new ApiException(HttpStatus.CONFLICT, "La cuenta ya tiene pagos registrados");
        jdbc.sql("""
                UPDATE table_sessions SET discount_kind=NULL,discount_value=NULL,discount_reason=NULL,redeemed_points=0,
                  updated_at=NOW() WHERE id=:id
                """).param("id", sessionId).update();
        addPoints(session.customerId(), sessionId, session.redeemedPoints(), "Canje cancelado en " + session.label());
        realtime.waiters(currentUser.require().branchId(), "DISCOUNT_UPDATED", new SessionRef(sessionId));
        return state(sessionId);
    }

    /** Al cerrar la cuenta: suma visita, gasto y puntos según lo efectivamente pagado. */
    public void onSessionClosed(UUID sessionId, BigDecimal paid) {
        var customerId = jdbc.sql("SELECT customer_id FROM table_sessions WHERE id=:id").param("id", sessionId)
                .query((rs, row) -> new CustomerRef(rs.getObject("customer_id", UUID.class))).single().id();
        if (customerId == null) return;
        jdbc.sql("""
                UPDATE customers SET visits=visits+1,total_spent=total_spent+:paid,last_visit_at=NOW() WHERE id=:id
                """).param("paid", paid).param("id", customerId).update();
        var program = program();
        if (!program.enabled()) return;
        var earned = paid.divide(program.spendPerPoint(), 0, RoundingMode.FLOOR).intValue();
        if (earned > 0) addPoints(customerId, sessionId, earned, "Consumo de S/ " + paid.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Ajuste manual de puntos (bonificación de cumpleaños, corrección), siempre con motivo. */
    @Transactional
    public CustomerView adjustPoints(UUID customerId, int points, String reason) {
        var customer = find(customerId);
        if (customer.points() + points < 0) throw new ApiException(HttpStatus.CONFLICT, "El cliente no tiene puntos suficientes");
        addPoints(customerId, null, points, reason.trim());
        audit.record("POINTS_ADJUSTED", customer.fullName() + ": " + (points > 0 ? "+" : "") + points + " puntos · " + reason.trim(),
                null, customerId);
        return find(customerId);
    }

    /** Estado de cliente y puntos de una atención, para mostrarlo en el cobro. */
    public LoyaltyState state(UUID sessionId) {
        var program = program();
        var session = jdbc.sql("SELECT customer_id,redeemed_points FROM table_sessions WHERE id=:id").param("id", sessionId)
                .query((rs, row) -> new SessionLoyalty(rs.getObject("customer_id", UUID.class), rs.getInt("redeemed_points")))
                .single();
        var customer = session.customerId() == null ? null : find(session.customerId());
        return new LoyaltyState(customer, session.redeemedPoints(), program);
    }

    public Program program() {
        var org = currentUser.require().organizationId();
        return jdbc.sql("""
                        SELECT loyalty_enabled,loyalty_spend_per_point,loyalty_point_value,loyalty_min_redeem
                        FROM organizations WHERE id=:org
                        """).param("org", org)
                .query((rs, row) -> new Program(rs.getBoolean("loyalty_enabled"), rs.getBigDecimal("loyalty_spend_per_point"),
                        rs.getBigDecimal("loyalty_point_value"), rs.getInt("loyalty_min_redeem"))).single();
    }

    @Transactional
    public Program saveProgram(Program program) {
        jdbc.sql("""
                UPDATE organizations SET loyalty_enabled=:enabled,loyalty_spend_per_point=:spend,loyalty_point_value=:value,
                  loyalty_min_redeem=:min,updated_at=NOW() WHERE id=:org
                """).param("enabled", program.enabled()).param("spend", program.spendPerPoint())
                .param("value", program.pointValue()).param("min", program.minRedeem())
                .param("org", currentUser.require().organizationId()).update();
        return program();
    }

    private void addPoints(UUID customerId, UUID sessionId, int points, String reason) {
        jdbc.sql("UPDATE customers SET points=points+:points WHERE id=:id").param("points", points).param("id", customerId).update();
        jdbc.sql("""
                INSERT INTO loyalty_movements(customer_id,session_id,points,reason,created_by)
                VALUES (:customer,:session,:points,:reason,:user)
                """).param("customer", customerId).param("session", sessionId).param("points", points)
                .param("reason", reason).param("user", currentUser.find().map(CurrentUser.Scope::userId).orElse(null)).update();
    }

    private SessionLock lockSession(UUID sessionId) {
        var branch = currentUser.require().branchId();
        return jdbc.sql("""
                        SELECT ts.customer_id,ts.redeemed_points,ts.discount_kind,COALESCE(t.label,ts.label) label
                        FROM table_sessions ts LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE ts.id=:id AND ts.branch_id=:branch AND ts.status='OPEN' FOR UPDATE OF ts
                        """).param("id", sessionId).param("branch", branch)
                .query((rs, row) -> new SessionLock(rs.getObject("customer_id", UUID.class), rs.getInt("redeemed_points"),
                        rs.getString("discount_kind"), rs.getString("label"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "La atención no está abierta"));
    }

    private BigDecimal subtotal(UUID sessionId) {
        return jdbc.sql("""
                SELECT COALESCE(sum(oi.unit_price*oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.session_id=:session AND o.status<>'DRAFT' AND oi.status<>'CANCELLED'
                """).param("session", sessionId).query(BigDecimal.class).single();
    }

    private int paymentsCount(UUID sessionId) {
        return jdbc.sql("SELECT count(*) FROM payments WHERE session_id=:id").param("id", sessionId).query(Integer.class).single();
    }

    private CustomerView find(UUID id) {
        return jdbc.sql("SELECT * FROM customers WHERE id=:id AND organization_id=:org")
                .param("id", id).param("org", currentUser.require().organizationId())
                .query((rs, row) -> map(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Cliente no encontrado"));
    }

    private CustomerView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerView(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("phone"),
                rs.getString("email"), rs.getObject("birthday", LocalDate.class), rs.getString("notes"), rs.getInt("points"),
                rs.getInt("visits"), rs.getBigDecimal("total_spent"),
                rs.getTimestamp("last_visit_at") == null ? null : rs.getTimestamp("last_visit_at").toInstant());
    }

    /** Se guarda solo con dígitos para que «987 654 321» y «987654321» sean el mismo cliente. */
    static String normalizePhone(String phone) {
        var digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.startsWith("51") && digits.length() == 11) digits = digits.substring(2);
        if (digits.length() < 6 || digits.length() > 15) throw new ApiException(HttpStatus.BAD_REQUEST, "Ingresa un celular válido");
        return digits;
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record Balance(String name, int points) {}
    private record CustomerRef(UUID id) {}
    private record SessionLoyalty(UUID customerId, int redeemedPoints) {}
    private record SessionLock(UUID customerId, int redeemedPoints, String discountKind, String label) {}
    public record SessionRef(UUID sessionId) {}
    public record CustomerData(String fullName, String phone, String email, LocalDate birthday, String notes) {}
    public record CustomerView(UUID id, String fullName, String phone, String email, LocalDate birthday, String notes,
                               int points, int visits, BigDecimal totalSpent, Instant lastVisitAt) {}
    public record Visit(UUID sessionId, Instant closedAt, String label, String branch, BigDecimal paid) {}
    public record PointsMovement(UUID id, int points, String reason, Instant createdAt) {}
    public record Favorite(String name, int quantity) {}
    public record CustomerDetail(CustomerView customer, List<Visit> visits, List<PointsMovement> points, List<Favorite> favorites) {}
    public record Summary(long total, long frequent, long atRisk, long birthdays, long newCustomers, long active,
                          long points, BigDecimal avgSpent) {}
    public record Program(boolean enabled, BigDecimal spendPerPoint, BigDecimal pointValue, int minRedeem) {}
    public record LoyaltyState(CustomerView customer, int redeemedPoints, Program program) {}
}
