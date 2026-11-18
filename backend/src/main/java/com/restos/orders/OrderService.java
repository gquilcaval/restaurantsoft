package com.restos.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.restos.audit.AuditService;
import com.restos.inventory.InventoryService;
import com.restos.menu.MenuCatalog;
import com.restos.realtime.RealtimePublisher;
import com.restos.security.CurrentUser;
import com.restos.security.UserRole;
import com.restos.shared.ApiException;
import com.restos.tables.TableService;

/** Reglas del pedido colaborativo: borradores compartidos, rondas inmutables, anulaciones y estados de cocina. */
@Service
public class OrderService {
    private static final List<UserRole> MANAGERS = List.of(UserRole.BRANCH_MANAGER, UserRole.ORGANIZATION_ADMIN);
    private final JdbcClient jdbc;
    private final TableService tables;
    private final CurrentUser currentUser;
    private final RealtimePublisher realtime;
    private final MenuCatalog catalog;
    private final OrderStatusRefresher orderStatus;
    private final AuditService audit;
    private final InventoryService inventory;

    private final com.restos.service.AttendedCallCloser calls;

    private final com.restos.waiters.WaiterAssignmentService assignments;
    private final com.restos.printing.PrintingService printing;

    public OrderService(JdbcClient jdbc, TableService tables, CurrentUser currentUser, RealtimePublisher realtime,
                        MenuCatalog catalog, OrderStatusRefresher orderStatus, AuditService audit,
                        InventoryService inventory, com.restos.service.AttendedCallCloser calls, com.restos.waiters.WaiterAssignmentService assignments,
                        com.restos.printing.PrintingService printing) {
        this.printing = printing;
        this.assignments = assignments;
        this.calls = calls;
        this.audit = audit;
        this.inventory = inventory;
        this.jdbc = jdbc;
        this.tables = tables;
        this.currentUser = currentUser;
        this.realtime = realtime;
        this.catalog = catalog;
        this.orderStatus = orderStatus;
    }

    @Transactional
    public OrderSnapshot addItem(UUID sessionId, UUID tableToken, UUID guestToken, AddItem request) {
        tables.lockOpenSession(sessionId, tableToken);
        var guest = tables.requireGuest(sessionId, tableToken, guestToken);
        ensureOrderingAvailable(sessionId);
        var scope = sessionScope(sessionId);
        var priced = catalog.price(scope.organizationId(), scope.branchId(), request.menuItemId(), request.optionIds());

        var orderId = findOrCreateDraft(sessionId);
        var notes = normalizeNotes(request.notes());
        // Lo mismo (plato, persona, opciones y nota) que aún no se envía se suma a su línea en vez de repetirse.
        var wanted = modifierSignature(priced.modifiers());
        var same = jdbc.sql("""
                SELECT oi.id, COALESCE((SELECT string_agg(m.group_name || '|' || m.option_name, chr(1))
                                        FROM order_item_modifiers m WHERE m.order_item_id=oi.id), '') signature
                FROM order_items oi
                WHERE oi.order_id=:order AND oi.guest_id=:guest AND oi.menu_item_id=:menuItem AND oi.status='DRAFT'
                  AND oi.notes IS NOT DISTINCT FROM :notes AND oi.quantity + :quantity <= 20
                """).param("order", orderId).param("guest", guest.id()).param("menuItem", priced.menuItemId())
                .param("notes", notes).param("quantity", request.quantity())
                .query((rs, row) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("signature")}).list().stream()
                .filter(row -> sortedSignature(java.util.Arrays.asList(((String) row[1]).split("\u0001", -1))).equals(wanted))
                .map(row -> (UUID) row[0]).findFirst();
        if (same.isPresent()) {
            jdbc.sql("UPDATE order_items SET quantity=quantity+:quantity,updated_at=NOW() WHERE id=:id")
                    .param("quantity", request.quantity()).param("id", same.get()).update();
        } else {
            var itemId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO order_items(id,order_id,guest_id,menu_item_id,item_name,unit_price,quantity,notes,status)
                    VALUES (:id,:order,:guest,:menuItem,:name,:price,:quantity,:notes,'DRAFT')
                    """).param("id", itemId).param("order", orderId).param("guest", guest.id())
                    .param("menuItem", priced.menuItemId()).param("name", priced.name())
                    .param("price", priced.unitPrice()).param("quantity", request.quantity())
                    .param("notes", notes).update();
            catalog.saveSelection(itemId, priced.modifiers());
        }
        touchSession(sessionId);
        var snapshot = snapshot(sessionId);
        realtime.table(sessionId, "TABLE_CART_UPDATED", snapshot);
        return snapshot;
    }

    @Transactional
    public OrderSnapshot removeItem(UUID sessionId, UUID tableToken, UUID guestToken, UUID itemId) {
        tables.lockOpenSession(sessionId, tableToken);
        var guest = tables.requireGuest(sessionId, tableToken, guestToken);
        var removed = jdbc.sql("""
                DELETE FROM order_items oi USING orders o
                WHERE oi.id=:item AND oi.guest_id=:guest AND oi.order_id=o.id
                  AND o.session_id=:session AND o.status='DRAFT'
                """).param("item", itemId).param("guest", guest.id()).param("session", sessionId).update();
        if (removed == 0) throw new ApiException(HttpStatus.CONFLICT, "El producto ya no puede modificarse");
        touchSession(sessionId);
        var snapshot = snapshot(sessionId);
        realtime.table(sessionId, "TABLE_CART_UPDATED", snapshot);
        return snapshot;
    }

    @Transactional
    public OrderSnapshot updateDraftItem(UUID sessionId, UUID tableToken, UUID guestToken, UUID itemId,
                                         int quantity, String notes) {
        tables.lockOpenSession(sessionId, tableToken);
        var guest = tables.requireGuest(sessionId, tableToken, guestToken);
        ensureOrderingAvailable(sessionId);
        var updated = jdbc.sql("""
                UPDATE order_items oi SET quantity=:quantity,notes=:notes,updated_at=NOW()
                FROM orders o WHERE oi.id=:item AND oi.guest_id=:guest AND oi.order_id=o.id
                  AND o.session_id=:session AND o.status='DRAFT'
                """).param("quantity", quantity).param("notes", normalizeNotes(notes)).param("item", itemId)
                .param("guest", guest.id()).param("session", sessionId).update();
        if (updated == 0) throw new ApiException(HttpStatus.CONFLICT,
                "Solo puedes modificar tus productos antes de enviar la ronda");
        touchSession(sessionId);
        var snapshot = snapshot(sessionId);
        realtime.table(sessionId, "TABLE_CART_UPDATED", snapshot);
        return snapshot;
    }

    @Transactional
    public OrderSnapshot submit(UUID sessionId, UUID tableToken, UUID guestToken) {
        tables.lockOpenSession(sessionId, tableToken);
        var guest = tables.requireGuest(sessionId, tableToken, guestToken);
        ensureOrderingAvailable(sessionId);
        if (!guest.host()) throw new ApiException(HttpStatus.FORBIDDEN, "Solo el anfitrión puede enviar la ronda");
        var context = jdbc.sql("""
                        SELECT o.id,o.branch_id,o.round_number FROM orders o
                        WHERE o.session_id=:session AND o.status='DRAFT'
                        FOR UPDATE
                        """).param("session", sessionId)
                .query((rs, row) -> new OrderContext(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getInt("round_number")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No existe una ronda por enviar"));
        var items = jdbc.sql("SELECT count(*) FROM order_items WHERE order_id=:order")
                .param("order", context.id()).query(Integer.class).single();
        if (items == 0) throw new ApiException(HttpStatus.CONFLICT, "La ronda está vacía");
        // Un producto pudo agotarse mientras la mesa seguía eligiendo.
        var soldOut = jdbc.sql("""
                SELECT COALESCE(string_agg(DISTINCT oi.item_name, ', '), '') FROM order_items oi
                JOIN menu_item_unavailability u ON u.menu_item_id=oi.menu_item_id AND u.branch_id=:branch
                WHERE oi.order_id=:order
                """).param("order", context.id()).param("branch", context.branchId()).query(String.class).single();
        if (!soldOut.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,
                "Se agotó: " + soldOut + ". Quítalo del pedido para poder enviarlo");
        jdbc.sql("UPDATE orders SET status='SUBMITTED',submitted_at=NOW(),updated_at=NOW() WHERE id=:id")
                .param("id", context.id()).update();
        jdbc.sql("UPDATE order_items SET status='SUBMITTED',updated_at=NOW() WHERE order_id=:id")
                .param("id", context.id()).update();
        inventory.consumeOrder(context.id());
        printing.comandas(context.id(), false);
        touchSession(sessionId);
        return publishSubmitted(sessionId, context.branchId());
    }

    /** El mesero envía una ronda completa a cocina, sin pasar por el carrito del QR. */
    @Transactional
    public OrderSnapshot submitStaffRound(UUID sessionId, List<AddItem> lines) {
        var scope = currentUser.require();
        var session = lockStaffSession(sessionId, scope.branchId());
        if (session.requiresReview()) throw new ApiException(HttpStatus.CONFLICT,
                "La atención está pendiente de revisión; reactívala antes de agregar pedidos");
        ensureOrderingAvailable(sessionId);
        if (lines == null || lines.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "La ronda está vacía");
        // Líneas idénticas (plato, opciones y nota) se envían como una sola con la cantidad sumada.
        var merged = new java.util.LinkedHashMap<String, AddItem>();
        for (var line : lines) {
            var options = line.optionIds() == null ? List.<UUID>of() : line.optionIds().stream().sorted().toList();
            var key = line.menuItemId() + "|" + options + "|" + normalizeNotes(line.notes());
            merged.merge(key, line, (a, b) -> new AddItem(a.menuItemId(), a.quantity() + b.quantity(), a.notes(), a.optionIds()));
        }
        var priced = merged.values().stream().map(line -> new PricedLine(line,
                catalog.price(session.organizationId(), scope.branchId(), line.menuItemId(), line.optionIds())))
                .toList();
        var round = jdbc.sql("SELECT COALESCE(max(round_number)+1,1) FROM orders WHERE session_id=:session")
                .param("session", sessionId).query(Integer.class).single();
        var orderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status,submitted_at)
                VALUES (:id,:org,:branch,:session,:round,'SUBMITTED',NOW())
                """).param("id", orderId).param("org", session.organizationId()).param("branch", scope.branchId())
                .param("session", sessionId).param("round", round).update();
        for (var line : priced) {
            var itemId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO order_items(id,order_id,menu_item_id,item_name,unit_price,quantity,notes,status,created_by)
                    VALUES (:id,:order,:menuItem,:name,:price,:quantity,:notes,'SUBMITTED',:user)
                    """).param("id", itemId).param("order", orderId).param("menuItem", line.priced().menuItemId())
                    .param("name", line.priced().name()).param("price", line.priced().unitPrice())
                    .param("quantity", line.request().quantity()).param("notes", normalizeNotes(line.request().notes()))
                    .param("user", scope.userId()).update();
            catalog.saveSelection(itemId, line.priced().modifiers());
        }
        inventory.consumeOrder(orderId);
        // Primero queda a cargo quien envía (si la mesa no tenía mesero), así su nombre sale en la comanda.
        assignments.claimIfUnassigned(sessionId);
        printing.comandas(orderId, false);
        touchSession(sessionId);
        calls.closeForSession(sessionId);
        return publishSubmitted(sessionId, scope.branchId());
    }

    /**
     * Anula unidades ya enviadas conservando el registro. El personal de salón y cocina solo puede anular
     * lo que todavía no se empezó a preparar; un administrador puede anular en cualquier estado.
     */
    @Transactional
    public OrderSnapshot voidItem(UUID itemId, int quantity, String reason) {
        var scope = currentUser.require();
        var item = jdbc.sql("""
                        SELECT oi.status,oi.quantity,o.session_id,o.branch_id,o.id order_id,o.status order_status
                        FROM order_items oi JOIN orders o ON o.id=oi.order_id
                        WHERE oi.id=:item AND o.branch_id=:branch
                        """).param("item", itemId).param("branch", scope.branchId())
                .query((rs, row) -> new VoidContext(rs.getString("status"), rs.getInt("quantity"),
                        rs.getObject("session_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getString("order_status")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        lockStaffSession(item.sessionId(), scope.branchId());
        if (item.orderStatus().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
                "El producto aún no fue enviado; el comensal puede quitarlo de su pedido");
        if (item.status().equals("CANCELLED")) throw new ApiException(HttpStatus.CONFLICT, "El producto ya fue anulado");
        if (!item.status().equals("SUBMITTED") && !MANAGERS.contains(scope.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Solo un administrador puede anular un plato que ya se está preparando o fue servido");
        }
        ensureOrderingAvailable(item.sessionId(),
                "La cuenta está en preparación; cancela la preparación de la cuenta antes de anular productos");
        if (quantity < 1 || quantity > item.quantity()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Indica entre 1 y " + item.quantity() + " unidades a anular");
        }
        var cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Indica el motivo de la anulación");

        var voidedRowId = itemId;
        if (quantity == item.quantity()) {
            jdbc.sql("""
                    UPDATE order_items SET status='CANCELLED',void_reason=:reason,voided_by=:user,voided_at=NOW(),
                      updated_at=NOW() WHERE id=:id
                    """).param("reason", cleanReason).param("user", scope.userId()).param("id", itemId).update();
        } else {
            // Se separan las unidades anuladas en una línea propia para que el resto siga su curso.
            var voidedId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO order_items(id,order_id,guest_id,menu_item_id,item_name,unit_price,quantity,notes,status,
                      created_by,created_at,void_reason,voided_by,voided_at)
                    SELECT :newId,order_id,guest_id,menu_item_id,item_name,unit_price,:quantity,notes,'CANCELLED',
                      created_by,created_at,:reason,:user,NOW()
                    FROM order_items WHERE id=:id
                    """).param("newId", voidedId).param("quantity", quantity).param("reason", cleanReason)
                    .param("user", scope.userId()).param("id", itemId).update();
            catalog.copySelection(itemId, voidedId);
            voidedRowId = voidedId;
            jdbc.sql("UPDATE order_items SET quantity=quantity-:quantity,updated_at=NOW() WHERE id=:id")
                    .param("quantity", quantity).param("id", itemId).update();
        }
        // Lo que cocina todavía no empezó vuelve al inventario; lo ya preparado se considera consumido.
        if (item.status().equals("SUBMITTED")) inventory.returnVoided(itemId, voidedRowId, quantity);
        // Si su estación trabaja con comandas impresas, sale un ticket de ANULACIÓN para que no lo preparen.
        if (!item.status().equals("DELIVERED")) {
            var by = jdbc.sql("SELECT full_name FROM app_users WHERE id=:id").param("id", scope.userId()).query(String.class)
                    .optional().map(name -> name.trim().split("\\s+")[0]).orElse(null);
            printing.voided(voidedRowId, quantity, cleanReason, by);
        }
        orderStatus.refresh(item.orderId());
        touchSession(item.sessionId());
        var voided = jdbc.sql("""
                SELECT oi.item_name,oi.unit_price,COALESCE(t.label,ts.label) label FROM order_items oi
                JOIN orders o ON o.id=oi.order_id JOIN table_sessions ts ON ts.id=o.session_id
                LEFT JOIN dining_tables t ON t.id=ts.table_id WHERE oi.id=:item
                """).param("item", itemId).query((rs, row) -> new VoidedItem(rs.getString("item_name"),
                        rs.getBigDecimal("unit_price"), rs.getString("label"))).single();
        audit.record("ITEM_VOIDED", quantity + "× " + voided.name() + " · " + voided.label() + " · " + cleanReason
                + " (estado: " + item.status().toLowerCase() + ")",
                voided.unitPrice().multiply(BigDecimal.valueOf(quantity)), item.sessionId());
        var snapshot = snapshot(item.sessionId());
        realtime.table(item.sessionId(), "ORDER_ITEM_CANCELLED", snapshot);
        realtime.kitchen(item.branchId(), "ORDER_ITEM_CANCELLED", snapshot);
        realtime.waiters(item.branchId(), "ORDER_ITEM_CANCELLED", snapshot);
        return snapshot;
    }

    public OrderSnapshot snapshot(UUID sessionId) {
        var guests = jdbc.sql("""
                        SELECT id,display_name,is_host,active,left_at FROM guests
                        WHERE session_id=:session ORDER BY active DESC,created_at
                        """)
                .param("session", sessionId)
                .query((rs, row) -> new GuestView(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getBoolean("is_host"), rs.getBoolean("active"),
                        rs.getTimestamp("left_at") == null ? null : rs.getTimestamp("left_at").toInstant())).list();
        var items = jdbc.sql("""
                        SELECT oi.id,o.id order_id,o.round_number,o.status order_status,o.submitted_at,oi.guest_id,
                               oi.item_name,oi.unit_price,oi.quantity,oi.notes,oi.status,oi.void_reason,
                               u.full_name created_by_name,ks.id station_id,ks.name station_name,
                               (SELECT string_agg(m.option_name, ' · ' ORDER BY m.display_order)
                                  FROM order_item_modifiers m WHERE m.order_item_id=oi.id) modifiers,
                               EXISTS (SELECT 1 FROM print_routes pr WHERE pr.branch_id=o.branch_id
                                         AND pr.station_id IS NOT DISTINCT FROM mc.station_id) printed
                        FROM orders o JOIN order_items oi ON oi.order_id=o.id
                        LEFT JOIN app_users u ON u.id=oi.created_by
                        LEFT JOIN menu_items mi ON mi.id=oi.menu_item_id
                        LEFT JOIN menu_categories mc ON mc.id=mi.category_id
                        LEFT JOIN kitchen_stations ks ON ks.id=mc.station_id
                        WHERE o.session_id=:session ORDER BY o.round_number,oi.created_at,oi.status
                        """).param("session", sessionId)
                .query((rs, row) -> new ItemView(rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getInt("round_number"),
                        rs.getString("order_status"), rs.getObject("guest_id", UUID.class),
                        rs.getString("item_name"), rs.getBigDecimal("unit_price"), rs.getInt("quantity"),
                        rs.getString("notes"), rs.getString("status"), rs.getString("modifiers"),
                        rs.getString("void_reason"), rs.getString("created_by_name"),
                        rs.getObject("station_id", UUID.class), rs.getString("station_name"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                        rs.getBoolean("printed"))).list();
        var total = items.stream()
                .filter(item -> !item.orderStatus().equals("DRAFT") && !item.status().equals("CANCELLED"))
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Nombre del mesero a cargo: el comensal ve «Te atiende Lucía».
        var waiter = jdbc.sql("SELECT u.full_name FROM table_sessions ts JOIN app_users u ON u.id=ts.waiter_id WHERE ts.id=:session")
                .param("session", sessionId).query(String.class).optional()
                .map(name -> name.trim().split("\\s+")[0]).orElse(null);
        return new OrderSnapshot(sessionId, guests, items, total, Instant.now(), waiter, printing.jobsOfSession(sessionId));
    }

    /** Permite al personal consultar solamente sesiones pertenecientes a su sucursal. */
    public OrderSnapshot staffSnapshot(UUID sessionId) {
        var scope = currentUser.require();
        var belongs = jdbc.sql("SELECT count(*) FROM table_sessions WHERE id=:session AND branch_id=:branch")
                .param("session", sessionId).param("branch", scope.branchId()).query(Integer.class).single();
        if (belongs == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Sesión no encontrada");
        return snapshot(sessionId);
    }

    public List<KitchenOrder> activeKitchenOrders() {
        var branchId = currentUser.require().branchId();
        // Lo que se imprime en comanda no aparece en la pantalla de cocina.
        var printed = printing.printingStations(branchId);
        return jdbc.sql("""
                        SELECT o.id,o.session_id,o.round_number,o.status,o.submitted_at,COALESCE(t.label,ts.label) label,ts.channel
                        FROM orders o JOIN table_sessions ts ON ts.id=o.session_id
                        LEFT JOIN dining_tables t ON t.id=ts.table_id
                        WHERE o.branch_id=:branch AND o.status IN ('SUBMITTED','PREPARING','READY')
                        ORDER BY o.submitted_at
                        """).param("branch", branchId)
                .query((rs, row) -> new KitchenOrder(rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getString("label"), rs.getString("channel"),
                        rs.getInt("round_number"), rs.getString("status"),
                        rs.getTimestamp("submitted_at").toInstant(), List.of())).list().stream()
                .map(order -> new KitchenOrder(order.id(), order.sessionId(), order.tableLabel(), order.channel(),
                        order.roundNumber(), order.status(), order.submittedAt(),
                        snapshot(order.sessionId()).items().stream()
                                .filter(item -> item.orderId().equals(order.id()))
                                .filter(item -> !printed.contains(item.stationId() == null
                                        ? com.restos.printing.PrintingService.NO_STATION_KEY : item.stationId().toString()))
                                .toList()))
                .filter(order -> !order.items().isEmpty())
                .toList();
    }

    @Transactional
    public OrderSnapshot updateItemStatus(UUID itemId, String nextStatus) {
        var scope = currentUser.require();
        var item = jdbc.sql("""
                        SELECT oi.status,o.session_id,o.branch_id,o.id order_id FROM order_items oi
                        JOIN orders o ON o.id=oi.order_id WHERE oi.id=:item AND o.branch_id=:branch
                        """).param("item", itemId).param("branch", scope.branchId())
                .query((rs, row) -> new StatusContext(rs.getString("status"),
                        rs.getObject("session_id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getObject("order_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Producto no encontrado"));
        if (nextStatus.equals("DELIVERED") && scope.role().name().equals("KITCHEN")) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Solo un mesero puede confirmar que el plato fue entregado en la mesa");
        }
        // Con comanda impresa el sistema no sabe cuándo está listo (avisa el timbre): el mesero sirve directo.
        var printed = nextStatus.equals("DELIVERED") && printing.itemPrints(itemId);
        if (!validTransition(item.status(), nextStatus, scope.role().name(), printed)) {
            throw new ApiException(HttpStatus.CONFLICT, "Transición de estado no permitida");
        }
        jdbc.sql("UPDATE order_items SET status=:status,updated_at=NOW(),ready_at=CASE WHEN :status='READY' THEN NOW() ELSE ready_at END WHERE id=:id")
                .param("status", nextStatus).param("id", itemId).update();
        orderStatus.refresh(item.orderId());
        if (nextStatus.equals("DELIVERED")) assignments.claimIfUnassigned(item.sessionId());
        var snapshot = snapshot(item.sessionId());
        var event = "READY".equals(nextStatus) ? "ORDER_ITEM_READY" : "ORDER_ITEM_" + nextStatus;
        realtime.table(item.sessionId(), event, snapshot);
        // Todas las pantallas de cocina se refrescan, incluso cuando otro dispositivo realizó el cambio.
        realtime.kitchen(item.branchId(), event, snapshot);
        // El mesero sigue el plato durante todo su recorrido, no únicamente cuando queda listo.
        realtime.waiters(item.branchId(), event, snapshot);
        return snapshot;
    }

    /**
     * «Servir todo» de una ronda: los platos listos y los de estaciones con comanda impresa
     * (que el sistema no ve listos porque avisa el timbre) pasan a servidos.
     */
    @Transactional
    public OrderSnapshot deliverRound(UUID orderId) {
        var scope = currentUser.require();
        if (scope.role().name().equals("KITCHEN")) throw new ApiException(HttpStatus.FORBIDDEN, "Solo un mesero puede servir en la mesa");
        var order = jdbc.sql("SELECT session_id FROM orders WHERE id=:id AND branch_id=:branch AND status<>'DRAFT'")
                .param("id", orderId).param("branch", scope.branchId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ronda no encontrada"));
        var served = jdbc.sql("""
                UPDATE order_items oi SET status='DELIVERED',updated_at=NOW()
                FROM orders o
                WHERE o.id=oi.order_id AND oi.order_id=:order
                  AND (oi.status='READY' OR (oi.status IN ('SUBMITTED','PREPARING') AND EXISTS (
                        SELECT 1 FROM print_routes pr WHERE pr.branch_id=o.branch_id
                          AND pr.station_id IS NOT DISTINCT FROM (SELECT mc.station_id FROM menu_items mi JOIN menu_categories mc ON mc.id=mi.category_id WHERE mi.id=oi.menu_item_id))))
                """).param("order", orderId).update();
        if (served == 0) throw new ApiException(HttpStatus.CONFLICT, "No hay platos por servir en esta ronda");
        orderStatus.refresh(orderId);
        assignments.claimIfUnassigned(order);
        var snapshot = snapshot(order);
        realtime.table(order, "ORDER_ITEM_DELIVERED", snapshot);
        realtime.kitchen(scope.branchId(), "ORDER_ITEM_DELIVERED", snapshot);
        realtime.waiters(scope.branchId(), "ORDER_ITEM_DELIVERED", snapshot);
        return snapshot;
    }

    private OrderSnapshot publishSubmitted(UUID sessionId, UUID branchId) {
        var snapshot = snapshot(sessionId);
        realtime.table(sessionId, "ORDER_SUBMITTED", snapshot);
        realtime.kitchen(branchId, "ORDER_SUBMITTED", snapshot);
        // El salón también necesita conocer una nueva ronda para mostrar qué pidió cada mesa.
        realtime.waiters(branchId, "ORDER_SUBMITTED", snapshot);
        return snapshot;
    }

    private UUID findOrCreateDraft(UUID sessionId) {
        return jdbc.sql("SELECT id FROM orders WHERE session_id=:session AND status='DRAFT'")
                .param("session", sessionId).query(UUID.class).optional().orElseGet(() -> {
                    var context = jdbc.sql("""
                                    SELECT organization_id,branch_id,
                                      COALESCE((SELECT max(round_number)+1 FROM orders WHERE session_id=:session),1) round_number
                                    FROM table_sessions WHERE id=:session AND status='OPEN'
                                    """).param("session", sessionId)
                            .query((rs, row) -> new NewOrderContext(rs.getObject("organization_id", UUID.class),
                                    rs.getObject("branch_id", UUID.class), rs.getInt("round_number"))).single();
                    var id = UUID.randomUUID();
                    jdbc.sql("""
                            INSERT INTO orders(id,organization_id,branch_id,session_id,round_number,status)
                            VALUES (:id,:org,:branch,:session,:round,'DRAFT')
                            """).param("id", id).param("org", context.organizationId())
                            .param("branch", context.branchId()).param("session", sessionId)
                            .param("round", context.roundNumber()).update();
                    return id;
                });
    }

    private SessionScope sessionScope(UUID sessionId) {
        return jdbc.sql("SELECT organization_id,branch_id FROM table_sessions WHERE id=:session")
                .param("session", sessionId)
                .query((rs, row) -> new SessionScope(rs.getObject("organization_id", UUID.class),
                        rs.getObject("branch_id", UUID.class))).single();
    }

    private StaffSession lockStaffSession(UUID sessionId, UUID branchId) {
        return jdbc.sql("""
                        SELECT organization_id,requires_review FROM table_sessions
                        WHERE id=:session AND branch_id=:branch AND status='OPEN' FOR UPDATE
                        """).param("session", sessionId).param("branch", branchId)
                .query((rs, row) -> new StaffSession(rs.getObject("organization_id", UUID.class),
                        rs.getBoolean("requires_review")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "La atención no está abierta en esta sucursal"));
    }

    private void touchSession(UUID sessionId) {
        jdbc.sql("UPDATE table_sessions SET updated_at=NOW() WHERE id=:session")
                .param("session", sessionId).update();
    }

    /** Al comenzar el cobro se congela el consumo para que el saldo no cambie. */
    private void ensureOrderingAvailable(UUID sessionId) {
        ensureOrderingAvailable(sessionId,
                "La cuenta está en preparación; llama al mesero si deseas agregar otro pedido");
    }

    private void ensureOrderingAvailable(UUID sessionId, String message) {
        var checkout = jdbc.sql("""
                SELECT count(*) FROM service_requests
                WHERE session_id=:session AND type='BILL' AND status='IN_PROGRESS'
                """).param("session", sessionId).query(Integer.class).single();
        if (checkout > 0) throw new ApiException(HttpStatus.CONFLICT, message);
    }

    /** Opciones elegidas como texto ordenado, para comparar si dos líneas son iguales. */
    private String modifierSignature(List<com.restos.menu.MenuCatalog.SelectedModifier> modifiers) {
        return sortedSignature(modifiers.stream().map(m -> m.groupName() + "|" + m.optionName()).toList());
    }

    private String sortedSignature(List<String> parts) {
        return parts.stream().filter(part -> !part.isEmpty()).sorted().collect(java.util.stream.Collectors.joining("\u0001"));
    }

    private String normalizeNotes(String notes) {
        return notes == null || notes.isBlank() ? null : notes.trim();
    }

    private boolean validTransition(String current, String next, String role, boolean printed) {
        if (printed && next.equals("DELIVERED") && !role.equals("KITCHEN")
                && (current.equals("SUBMITTED") || current.equals("PREPARING") || current.equals("READY"))) return true;
        if (role.equals("KITCHEN") || role.equals("BRANCH_MANAGER") || role.equals("ORGANIZATION_ADMIN")) {
            if (current.equals("SUBMITTED") && next.equals("PREPARING")) return true;
            if (current.equals("PREPARING") && next.equals("READY")) return true;
        }
        return (role.equals("WAITER") || role.equals("BRANCH_MANAGER") || role.equals("ORGANIZATION_ADMIN"))
                && current.equals("READY") && next.equals("DELIVERED");
    }

    private record NewOrderContext(UUID organizationId, UUID branchId, int roundNumber) {}
    private record OrderContext(UUID id, UUID branchId, int roundNumber) {}
    private record StatusContext(String status, UUID sessionId, UUID branchId, UUID orderId) {}
    private record VoidContext(String status, int quantity, UUID sessionId, UUID branchId, UUID orderId,
                               String orderStatus) {}
    private record SessionScope(UUID organizationId, UUID branchId) {}
    private record VoidedItem(String name, BigDecimal unitPrice, String label) {}
    private record StaffSession(UUID organizationId, boolean requiresReview) {}
    private record PricedLine(AddItem request, MenuCatalog.PricedItem priced) {}
    public record AddItem(UUID menuItemId, int quantity, String notes, List<UUID> optionIds) {}
    public record GuestView(UUID id, String displayName, boolean host, boolean active, Instant leftAt) {}
    public record ItemView(UUID id, UUID orderId, int roundNumber, String orderStatus, UUID guestId,
                           String name, BigDecimal unitPrice, int quantity, String notes, String status,
                           String modifiers, String voidReason, String createdByName, UUID stationId,
                           String stationName, Instant submittedAt, boolean printed) {}
    /** waiterName: nombre (de pila) del mesero a cargo de la mesa, o null si aún no tiene. */
    public record OrderSnapshot(UUID sessionId, List<GuestView> guests, List<ItemView> items,
                                BigDecimal submittedTotal, Instant generatedAt, String waiterName,
                                List<com.restos.printing.PrintingService.JobView> printJobs) {}
    public record KitchenOrder(UUID id, UUID sessionId, String tableLabel, String channel, int roundNumber,
                               String status, Instant submittedAt, List<ItemView> items) {}
}
