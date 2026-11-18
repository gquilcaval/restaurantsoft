package com.restos.reports;

import com.restos.inventory.InventoryService;
import com.restos.payments.PaymentService;
import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reportes de cobros (no facturación fiscal) y consumo, siempre limitados a la sucursal del JWT. */
@Service
public class ReportService {
    private final JdbcClient jdbc;
    private final CurrentUser user;
    private final ZoneId zone;
    private final InventoryService inventory;
    private static final String PERIOD="p.branch_id=:branch AND p.paid_at>=:start AND p.paid_at<:end";
    private static final String FILTER=PERIOD+" AND (:method='ALL' OR p.method=:method) AND (NOT :unassigned OR p.cash_shift_id IS NULL)";
    public ReportService(JdbcClient jdbc,CurrentUser user,@Value("${restos.business-zone:America/Lima}") String zone,InventoryService inventory){
        this.jdbc=jdbc;this.user=user;this.zone=ZoneId.of(zone);this.inventory=inventory;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report summary(LocalDate from,LocalDate to,String method,boolean unassigned,int page){
        validate(from,to,method,page);
        var totals=period("""
                SELECT COALESCE(sum(p.amount),0) collected,count(*) payments,count(DISTINCT p.session_id) sessions,
                    COALESCE(sum(p.amount) FILTER (WHERE p.cash_shift_id IS NULL),0) unassigned,
                    COALESCE(sum(p.tip),0) tips
                FROM payments p WHERE
                """+PERIOD,from,to).query((rs,n)->new Totals(rs.getBigDecimal("collected"),rs.getLong("payments"),
                    rs.getLong("sessions"),rs.getBigDecimal("unassigned"),rs.getBigDecimal("tips"))).single();
        // Controles para el dueño: cuánto se dejó de cobrar por anulaciones y descuentos.
        var voids=period("""
                SELECT count(*) count,COALESCE(sum(oi.unit_price*oi.quantity),0) amount
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.branch_id=:branch AND oi.voided_at>=:start AND oi.voided_at<:end
                """,from,to).query((rs,n)->new ControlTotal(rs.getLong("count"),rs.getBigDecimal("amount"))).single();
        var discountRows=period("""
                SELECT ts.discount_kind,ts.discount_value,
                    (SELECT COALESCE(sum(oi.unit_price*oi.quantity),0) FROM order_items oi JOIN orders o ON o.id=oi.order_id
                     WHERE o.session_id=ts.id AND o.status<>'DRAFT' AND oi.status<>'CANCELLED') subtotal
                FROM table_sessions ts WHERE ts.branch_id=:branch AND ts.status='CLOSED'
                    AND ts.discount_kind IS NOT NULL AND ts.closed_at>=:start AND ts.closed_at<:end
                """,from,to).query((rs,n)->PaymentService.discountAmount(rs.getString("discount_kind"),
                    rs.getBigDecimal("discount_value"),rs.getBigDecimal("subtotal"))).list();
        var discounts=new ControlTotal(discountRows.size(),discountRows.stream().reduce(BigDecimal.ZERO,BigDecimal::add));
        var methods=period("SELECT p.method,count(*) count,COALESCE(sum(p.amount),0) amount FROM payments p WHERE "+PERIOD+" GROUP BY p.method ORDER BY amount DESC",from,to)
                .query((rs,n)->new MethodTotal(rs.getString("method"),rs.getLong("count"),rs.getBigDecimal("amount"))).list();
        var recorded=period("SELECT (p.paid_at AT TIME ZONE :zone)::date AS sales_day,sum(p.amount) amount FROM payments p WHERE "+PERIOD+" GROUP BY sales_day ORDER BY sales_day",from,to)
                .param("zone",zone.getId()).query((rs,n)->new DayTotal(rs.getObject("sales_day",LocalDate.class),rs.getBigDecimal("amount"))).list();
        var days=new LinkedHashMap<LocalDate,BigDecimal>();
        for(var date=from;!date.isAfter(to);date=date.plusDays(1))days.put(date,BigDecimal.ZERO);
        recorded.forEach(d->days.put(d.day(),d.amount()));
        // Horas pico: cobros agrupados por hora local, sumando todos los días del período.
        var hours=period("SELECT extract(hour FROM p.paid_at AT TIME ZONE :zone)::int AS sales_hour,sum(p.amount) amount,count(*) payments FROM payments p WHERE "+PERIOD+" GROUP BY sales_hour ORDER BY sales_hour",from,to)
                .param("zone",zone.getId()).query((rs,n)->new HourTotal(rs.getInt("sales_hour"),rs.getBigDecimal("amount"),rs.getLong("payments"))).list();
        // Consumo enviado y cobros usan fechas distintas; no se presenta el consumo como dinero recibido.
        var products=period("""
                SELECT oi.menu_item_id,oi.item_name,sum(oi.quantity) quantity,sum(oi.unit_price*oi.quantity) amount
                FROM order_items oi JOIN orders o ON o.id=oi.order_id
                WHERE o.branch_id=:branch AND o.submitted_at>=:start AND o.submitted_at<:end
                    AND o.status NOT IN ('DRAFT','CANCELLED') AND oi.status<>'CANCELLED'
                GROUP BY oi.menu_item_id,oi.item_name ORDER BY quantity DESC,oi.item_name LIMIT 10
                """,from,to).query((rs,n)->new ProductTotal(rs.getString("item_name"),rs.getLong("quantity"),rs.getBigDecimal("amount"))).list();
        long count=filtered("SELECT count(*) FROM payments p WHERE "+FILTER,from,to,method,unassigned).query(Long.class).single();
        // Costo estimado de insumos de lo vendido; cero si el restaurante aún no carga recetas.
        var inventoryCost=inventory.costOfSales(user.require().branchId(),from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
        // Ventas por zona según la zona actual de cada mesa; los pedidos sin mesa se agrupan como «Mostrador».
        var zones=period("""
                SELECT COALESCE(z.name,CASE WHEN ts.table_id IS NULL THEN 'Mostrador' ELSE 'Sin zona' END) zone_name,
                    COALESCE(z.color,'#888888') color,COALESCE(sum(p.amount),0) amount,count(DISTINCT p.session_id) sessions
                FROM payments p JOIN table_sessions ts ON ts.id=p.session_id
                LEFT JOIN dining_tables t ON t.id=ts.table_id LEFT JOIN dining_zones z ON z.id=t.zone_id
                WHERE """+" "+PERIOD+" GROUP BY 1,2 ORDER BY amount DESC",from,to)
                .query((rs,n)->new ZoneTotal(rs.getString("zone_name"),rs.getString("color"),rs.getBigDecimal("amount"),rs.getLong("sessions"))).list();
        return new Report(from,to,zone.getId(),totals,voids,discounts,inventoryCost,zones,methods,days.entrySet().stream().map(e->new DayTotal(e.getKey(),e.getValue())).toList(),
                products,ledger(from,to,method,unassigned,page*50,50),count,page,50,hours);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String export(LocalDate from,LocalDate to,String method,boolean unassigned){
        validate(from,to,method,0);
        var count=filtered("SELECT count(*) FROM payments p WHERE "+FILTER,from,to,method,unassigned).query(Long.class).single();
        if(count>10000)throw new ApiException(HttpStatus.BAD_REQUEST,"El rango supera 10 000 pagos; reduce las fechas para exportar");
        var csv=new StringBuilder("\uFEFFPago,Fecha local,Mesa,Método,Monto PEN,Propina PEN,Referencia,Registrado por,Caja\r\n");
        for(var p:ledger(from,to,method,unassigned,0,10000)){
            csv.append(cell(p.id().toString())).append(',').append(cell(p.paidAt().atZone(zone).toLocalDateTime().toString())).append(',')
                .append(cell(p.tableLabel())).append(',').append(cell(p.method())).append(',').append(p.amount().toPlainString()).append(',')
                .append(p.tip().toPlainString()).append(',')
                .append(cell(p.reference())).append(',').append(cell(p.registeredBy())).append(',')
                .append(cell(p.shiftId()==null?"Sin caja":p.shiftId().toString())).append("\r\n");
        }
        return csv.toString();
    }
    /** Todos los cobros del período (hasta 10 000) para exportar a Excel. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<PaymentRow> ledgerForExport(LocalDate from,LocalDate to,String method,boolean unassigned){
        validate(from,to,method,0);
        var count=filtered("SELECT count(*) FROM payments p WHERE "+FILTER,from,to,method,unassigned).query(Long.class).single();
        if(count>10000)throw new ApiException(HttpStatus.BAD_REQUEST,"El rango supera 10 000 pagos; reduce las fechas para exportar");
        return ledger(from,to,method,unassigned,0,10000);
    }
    public ZoneId zone(){return zone;}
    /** Neutraliza fórmulas de hojas de cálculo y escapa los textos ingresados por usuarios. */
    private String cell(String value){
        String text=value==null?"":value;
        var trimmed=text.stripLeading();
        if(!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0))>=0)text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    private List<PaymentRow> ledger(LocalDate from,LocalDate to,String method,boolean unassigned,int offset,int limit){
        return filtered("""
                SELECT p.id,p.paid_at,COALESCE(t.label,ts.label) label,p.method,p.amount,p.tip,p.reference,u.full_name,p.cash_shift_id
                FROM payments p JOIN table_sessions ts ON ts.id=p.session_id
                LEFT JOIN dining_tables t ON t.id=ts.table_id JOIN app_users u ON u.id=p.registered_by WHERE
                """+FILTER+" ORDER BY p.paid_at DESC,p.id DESC LIMIT :limit OFFSET :offset",from,to,method,unassigned)
                .param("limit",limit).param("offset",offset).query((rs,n)->new PaymentRow(rs.getObject("id",UUID.class),
                    rs.getTimestamp("paid_at").toInstant(),rs.getString("label"),rs.getString("method"),rs.getBigDecimal("amount"),rs.getBigDecimal("tip"),
                    rs.getString("reference"),rs.getString("full_name"),rs.getObject("cash_shift_id",UUID.class))).list();
    }
    private JdbcClient.StatementSpec period(String sql,LocalDate from,LocalDate to){
        return jdbc.sql(sql).param("branch",user.require().branchId())
                .param("start",from.atStartOfDay(zone).toOffsetDateTime()).param("end",to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }
    private JdbcClient.StatementSpec filtered(String sql,LocalDate from,LocalDate to,String method,boolean unassigned){
        return period(sql,from,to).param("method",method).param("unassigned",unassigned);
    }
    private void validate(LocalDate from,LocalDate to,String method,int page){
        if(from==null||to==null||to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>365)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Selecciona un período válido de hasta 366 días");
        if(!Set.of("ALL","CASH","CARD","YAPE","PLIN").contains(method)||page<0||page>20000)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Filtro o página inválidos");
    }
    public record Totals(BigDecimal collected,long payments,long sessions,BigDecimal unassigned,BigDecimal tips){}
    public record ControlTotal(long count,BigDecimal amount){}
    public record ZoneTotal(String name,String color,BigDecimal amount,long sessions){}
    public record MethodTotal(String method,long count,BigDecimal amount){}
    public record DayTotal(LocalDate day,BigDecimal amount){}
    public record HourTotal(int hour,BigDecimal amount,long payments){}
    public record ProductTotal(String name,long quantity,BigDecimal amount){}
    public record PaymentRow(UUID id,Instant paidAt,String tableLabel,String method,BigDecimal amount,BigDecimal tip,String reference,String registeredBy,UUID shiftId){}
    public record Report(LocalDate from,LocalDate to,String timezone,Totals totals,ControlTotal voids,
        ControlTotal discounts,BigDecimal inventoryCost,List<ZoneTotal> zones,List<MethodTotal> methods,List<DayTotal> days,
        List<ProductTotal> products,List<PaymentRow> payments,long paymentCount,int page,int pageSize,List<HourTotal> hours){}
}
