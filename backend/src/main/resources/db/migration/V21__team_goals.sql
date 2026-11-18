-- Meta de ventas por día de la semana (1 = lunes … 7 = domingo) para cada sucursal.
CREATE TABLE sales_goals (
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (branch_id, weekday)
);

-- Momento en que el personal tomó una llamada o un pedido de cuenta: mide el tiempo de atención.
ALTER TABLE service_requests ADD COLUMN attended_at TIMESTAMPTZ;
CREATE INDEX idx_service_requests_handler ON service_requests(handled_by, requested_at);
