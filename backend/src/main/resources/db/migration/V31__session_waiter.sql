-- Mesero a cargo de cada visita: recibe los avisos de esa mesa (pedidos, llamadas, cuenta, platos listos).
-- Va en la visita y no en la mesa física: el próximo cliente de esa mesa puede tener otro mesero.
ALTER TABLE table_sessions
    ADD COLUMN waiter_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    ADD COLUMN waiter_assigned_at TIMESTAMPTZ;
CREATE INDEX idx_table_sessions_open_waiter ON table_sessions(branch_id, waiter_id) WHERE status = 'OPEN';

-- Plato listo que nadie recogió: se avisa a todos una sola vez.
ALTER TABLE order_items ADD COLUMN ready_escalated_at TIMESTAMPTZ;

-- Mesas ya abiertas: mesero del plano, o el único mesero de su zona, o el mesero que la abrió.
UPDATE table_sessions ts SET waiter_id = pick.waiter_id, waiter_assigned_at = NOW()
FROM (
    SELECT ts2.id session_id, COALESCE(
        t.waiter_id,
        (SELECT min(uz.user_id::text)::uuid FROM user_zones uz JOIN app_users u ON u.id = uz.user_id
           WHERE uz.zone_id = t.zone_id AND u.active AND u.role IN ('WAITER','BRANCH_MANAGER')
           HAVING count(*) = 1),
        (SELECT u.id FROM app_users u WHERE u.id = ts2.opened_by AND u.role IN ('WAITER','BRANCH_MANAGER'))
    ) waiter_id
    FROM table_sessions ts2 JOIN dining_tables t ON t.id = ts2.table_id
    WHERE ts2.status = 'OPEN'
) pick
WHERE ts.id = pick.session_id AND pick.waiter_id IS NOT NULL;
