package com.restos.cash;

import com.restos.audit.AuditService;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Libro de caja por sucursal. Movimientos inmutables y arqueo separado de los cobros digitales. */
@Service
public class CashService {
    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final ZoneId zone;
    private final AuditService audit;

    public CashService(JdbcClient jdbc, CurrentUser user,
            @Value("${restos.business-zone:America/Lima}") String zone, AuditService audit) {
        this.jdbc = jdbc; this.user = user; this.zone = ZoneId.of(zone); this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview() {
        var branch = user.require().branchId();
        var active = jdbc.sql("SELECT id FROM cash_shifts WHERE branch_id=:branch AND closed_at IS NULL")
                .param("branch", branch).query(UUID.class).optional();
        var history = jdbc.sql("""
                SELECT s.id,s.opened_at,s.closed_at,u.full_name,
                    s.opening_amount,s.expected_amount,s.counted_amount
                FROM cash_shifts s JOIN app_users u ON u.id=s.opened_by
                WHERE s.branch_id=:branch ORDER BY s.opened_at DESC LIMIT 30
                """).param("branch", branch).query((rs,n) -> new ShiftRow(rs.getObject("id",UUID.class),
                    rs.getTimestamp("opened_at").toInstant(),
                    rs.getTimestamp("closed_at")==null?null:rs.getTimestamp("closed_at").toInstant(),
                    rs.getString("full_name"),rs.getBigDecimal("opening_amount"),
                    rs.getBigDecimal("expected_amount"),rs.getBigDecimal("counted_amount"))).list();
        var start = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        var unassigned = jdbc.sql("""
                SELECT count(*) count,COALESCE(sum(amount),0) amount FROM payments
                WHERE branch_id=:branch AND cash_shift_id IS NULL AND paid_at>=:start
                """).param("branch",branch).param("start",start)
                .query((rs,n)->new Unassigned(rs.getLong("count"),rs.getBigDecimal("amount"))).single();
        return new Overview(active.map(this::detail).orElse(null), history, unassigned, zone.getId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShiftDetail detail(UUID id) {
        var shift = shift(id);
        var methods = jdbc.sql("""
                SELECT method,count(*) count,COALESCE(sum(amount+tip),0) amount
                FROM payments WHERE cash_shift_id=:id GROUP BY method ORDER BY method
                """).param("id",id).query((rs,n)->new MethodTotal(rs.getString("method"),
                    rs.getLong("count"),rs.getBigDecimal("amount"))).list();
        var totals = jdbc.sql("""
                SELECT COALESCE(sum(amount) FILTER (WHERE kind='IN'),0) incoming,
                    COALESCE(sum(amount) FILTER (WHERE kind='OUT'),0) outgoing
                FROM cash_movements WHERE shift_id=:id
                """).param("id",id).query((rs,n)->new MovementTotals(rs.getBigDecimal("incoming"),rs.getBigDecimal("outgoing"))).single();
        var movements = jdbc.sql("""
                SELECT m.id,m.kind,m.amount,m.reason,m.created_at,u.full_name
                FROM cash_movements m JOIN app_users u ON u.id=m.registered_by
                WHERE m.shift_id=:id ORDER BY m.created_at DESC,m.id LIMIT 200
                """).param("id",id).query((rs,n)->new Movement(rs.getObject("id",UUID.class),
                    rs.getString("kind"),rs.getBigDecimal("amount"),rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant(),rs.getString("full_name"))).list();
        // Las propinas entran a la caja o al terminal junto con el cobro, por eso forman parte del arqueo.
        var tips = jdbc.sql("SELECT COALESCE(sum(tip),0) FROM payments WHERE cash_shift_id=:id")
                .param("id",id).query(BigDecimal.class).single();
        var cash = methods.stream().filter(m->m.method().equals("CASH")).map(MethodTotal::amount)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        var total = methods.stream().map(MethodTotal::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        var expected = shift.expectedAmount()!=null ? shift.expectedAmount()
                : shift.openingAmount().add(cash).add(totals.incoming()).subtract(totals.outgoing());
        return new ShiftDetail(shift,cash,total.subtract(cash),totals.incoming(),totals.outgoing(),expected,
                shift.countedAmount()==null?null:shift.countedAmount().subtract(expected),methods,movements,tips);
    }

    @Transactional
    public ShiftDetail open(UUID id, BigDecimal opening, String note) {
        var scope = user.require(); lockBranch(scope.branchId());
        // El identificador del cliente permite reintentar una petición sin duplicar un turno.
        if (exists(id)) {
            var previous=shift(id);
            if(previous.openingAmount().compareTo(opening)!=0 || !previous.openingNote().equals(note))
                throw conflict("Este identificador ya fue usado para otra apertura");
            return detail(id);
        }
        if (openShiftId(scope.branchId())!=null) throw conflict("Ya existe una caja abierta en esta sucursal");
        jdbc.sql("""
                INSERT INTO cash_shifts(id,organization_id,branch_id,opened_by,opening_amount,opening_note)
                VALUES (:id,:org,:branch,:user,:amount,:note)
                """).param("id",id).param("org",scope.organizationId()).param("branch",scope.branchId())
                .param("user",scope.userId()).param("amount",opening).param("note",note).update();
        audit.record("CASH_OPENED","Apertura de caja"+(note.isBlank()?"":" · "+note),opening,id);
        return detail(id);
    }

    @Transactional
    public ShiftDetail movement(UUID shiftId, UUID id, String kind, BigDecimal amount, String reason) {
        var scope=user.require(); lockBranch(scope.branchId()); var shift=shift(shiftId);
        var prior=jdbc.sql("SELECT shift_id,kind,amount,reason FROM cash_movements WHERE id=:id")
                .param("id",id).query((rs,n)->new PriorMovement(rs.getObject("shift_id",UUID.class),
                    rs.getString("kind"),rs.getBigDecimal("amount"),rs.getString("reason"))).optional();
        if(prior.isPresent()) {
            var p=prior.get();
            if(!p.shiftId().equals(shiftId)||!p.kind().equals(kind)||p.amount().compareTo(amount)!=0||!p.reason().equals(reason))
                throw conflict("El identificador del movimiento ya fue utilizado");
            return detail(shiftId);
        }
        if(shift.closedAt()!=null)throw conflict("La caja está cerrada y no admite movimientos");
        if(kind.equals("OUT") && amount.compareTo(detail(shiftId).expectedCash())>0)
            throw conflict("La salida supera el efectivo disponible en caja");
        jdbc.sql("""
                INSERT INTO cash_movements(id,shift_id,kind,amount,reason,registered_by)
                VALUES (:id,:shift,:kind,:amount,:reason,:user)
                """).param("id",id).param("shift",shiftId).param("kind",kind).param("amount",amount)
                .param("reason",reason).param("user",scope.userId()).update();
        audit.record("CASH_MOVEMENT",(kind.equals("IN")?"Ingreso":"Salida")+" de efectivo · "+reason,amount,shiftId);
        return detail(shiftId);
    }

    @Transactional
    public ShiftDetail close(UUID id, BigDecimal counted, String note) {
        var scope=user.require(); lockBranch(scope.branchId()); var current=detail(id);
        if(current.shift().closedAt()!=null) {
            if(current.shift().countedAmount().compareTo(counted)!=0 || !current.shift().closingNote().equals(note))
                throw conflict("El cierre ya fue registrado con otros valores");
            return current;
        }
        if(counted.compareTo(current.expectedCash())!=0 && note.isBlank())
            throw conflict("Explica la diferencia de efectivo antes de cerrar la caja");
        jdbc.sql("""
                UPDATE cash_shifts SET closed_at=clock_timestamp(),closed_by=:user,counted_amount=:counted,
                    expected_amount=:expected,closing_note=:note WHERE id=:id
                """).param("user",scope.userId()).param("counted",counted).param("expected",current.expectedCash())
                .param("note",note).param("id",id).update();
        var difference=counted.subtract(current.expectedCash());
        audit.record("CASH_CLOSED","Cierre de caja · esperado S/ "+current.expectedCash().toPlainString()
                +" · contado S/ "+counted.toPlainString()+(difference.signum()==0?"":" · diferencia S/ "+difference.toPlainString())
                +(note.isBlank()?"":" · "+note),counted,id);
        return detail(id);
    }

    /** Compartido con cobros: serializa apertura/cierre y evita pagos agregados a una caja ya arqueada. */
    public void lockBranch(UUID branch) {
        jdbc.sql("SELECT id FROM branches WHERE id=:branch FOR UPDATE").param("branch",branch)
                .query(UUID.class).optional().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Sucursal no encontrada"));
    }
    public UUID openShiftId(UUID branch) {
        return jdbc.sql("SELECT id FROM cash_shifts WHERE branch_id=:branch AND closed_at IS NULL")
                .param("branch",branch).query(UUID.class).optional().orElse(null);
    }
    private boolean exists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM cash_shifts WHERE id=:id AND branch_id=:branch")
                .param("id",id).param("branch",user.require().branchId()).query(Integer.class).single()>0;
    }
    private Shift shift(UUID id) {
        return jdbc.sql("""
                SELECT s.*,u.full_name opened_name,c.full_name closed_name FROM cash_shifts s
                JOIN app_users u ON u.id=s.opened_by LEFT JOIN app_users c ON c.id=s.closed_by
                WHERE s.id=:id AND s.branch_id=:branch
                """).param("id",id).param("branch",user.require().branchId()).query((rs,n)->new Shift(
                    id,rs.getTimestamp("opened_at").toInstant(),rs.getTimestamp("closed_at")==null?null:rs.getTimestamp("closed_at").toInstant(),
                    rs.getString("opened_name"),rs.getString("closed_name"),rs.getBigDecimal("opening_amount"),
                    rs.getBigDecimal("counted_amount"),rs.getBigDecimal("expected_amount"),rs.getString("opening_note"),rs.getString("closing_note")))
                .optional().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Caja no encontrada"));
    }
    private ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,message);}
    public record Shift(UUID id, Instant openedAt, Instant closedAt, String openedBy, String closedBy,
        BigDecimal openingAmount, BigDecimal countedAmount, BigDecimal expectedAmount, String openingNote, String closingNote){}
    public record ShiftRow(UUID id,Instant openedAt,Instant closedAt,String openedBy,BigDecimal openingAmount,BigDecimal expectedAmount,BigDecimal countedAmount){}
    public record ShiftDetail(Shift shift,BigDecimal cashPayments,BigDecimal digitalPayments,BigDecimal cashIn,
        BigDecimal cashOut,BigDecimal expectedCash,BigDecimal difference,List<MethodTotal> methods,List<Movement> movements,
        BigDecimal tips){}
    public record MethodTotal(String method,long count,BigDecimal amount){}
    public record Movement(UUID id,String kind,BigDecimal amount,String reason,Instant createdAt,String registeredBy){}
    public record Unassigned(long count,BigDecimal amount){}
    public record Overview(ShiftDetail active,List<ShiftRow> history,Unassigned unassignedToday,String timezone){}
    private record MovementTotals(BigDecimal incoming,BigDecimal outgoing){}
    private record PriorMovement(UUID shiftId,String kind,BigDecimal amount,String reason){}
}
