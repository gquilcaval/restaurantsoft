-- Insumos de la organización; el stock se lleva por sucursal.
CREATE TABLE ingredients (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(100) NOT NULL,
    unit VARCHAR(10) NOT NULL CHECK (unit IN ('KG','G','L','ML','UNIDAD')),
    cost_per_unit NUMERIC(12,4) NOT NULL DEFAULT 0 CHECK (cost_per_unit >= 0),
    min_stock NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, name)
);

CREATE TABLE ingredient_stock (
    branch_id UUID NOT NULL REFERENCES branches(id),
    ingredient_id UUID NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,
    quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
    PRIMARY KEY (branch_id, ingredient_id)
);

-- Receta: cantidad de cada insumo que consume una unidad del plato.
CREATE TABLE recipe_lines (
    menu_item_id UUID NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES ingredients(id),
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (menu_item_id, ingredient_id)
);

-- Kardex: todo cambio de stock queda registrado con su costo del momento.
CREATE TABLE stock_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    ingredient_id UUID NOT NULL REFERENCES ingredients(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('PURCHASE','SALE','VOID_RETURN','WASTE','ADJUSTMENT')),
    quantity NUMERIC(14,3) NOT NULL,
    unit_cost NUMERIC(12,4) NOT NULL DEFAULT 0,
    reference VARCHAR(200),
    order_item_id UUID REFERENCES order_items(id),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stock_movements_branch ON stock_movements(branch_id, created_at DESC);
CREATE INDEX idx_stock_movements_ingredient ON stock_movements(ingredient_id, created_at DESC);
CREATE INDEX idx_stock_movements_item ON stock_movements(order_item_id) WHERE order_item_id IS NOT NULL;

-- Clientes frecuentes y su programa de puntos.
CREATE TABLE customers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    full_name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    email VARCHAR(180),
    birthday DATE,
    notes VARCHAR(500),
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    visits INTEGER NOT NULL DEFAULT 0,
    total_spent NUMERIC(12,2) NOT NULL DEFAULT 0,
    last_visit_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, phone)
);
CREATE INDEX idx_customers_org_name ON customers(organization_id, lower(full_name));

CREATE TABLE loyalty_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id),
    session_id UUID REFERENCES table_sessions(id),
    points INTEGER NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_loyalty_customer ON loyalty_movements(customer_id, created_at DESC);

ALTER TABLE table_sessions
    ADD COLUMN customer_id UUID REFERENCES customers(id),
    ADD COLUMN redeemed_points INTEGER NOT NULL DEFAULT 0 CHECK (redeemed_points >= 0);

-- Reglas del programa: cuántos soles se gastan por punto y cuánto vale un punto al canjearlo.
ALTER TABLE organizations
    ADD COLUMN loyalty_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN loyalty_spend_per_point NUMERIC(10,2) NOT NULL DEFAULT 10 CHECK (loyalty_spend_per_point > 0),
    ADD COLUMN loyalty_point_value NUMERIC(10,4) NOT NULL DEFAULT 0.10 CHECK (loyalty_point_value > 0),
    ADD COLUMN loyalty_min_redeem INTEGER NOT NULL DEFAULT 100 CHECK (loyalty_min_redeem > 0);

-- Reservas: cada una ocupa su mesa durante una ventana de tiempo.
CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    customer_id UUID REFERENCES customers(id),
    customer_name VARCHAR(120) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    party_size INTEGER NOT NULL CHECK (party_size BETWEEN 1 AND 50),
    reserved_at TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 90 CHECK (duration_minutes BETWEEN 15 AND 480),
    table_id UUID REFERENCES dining_tables(id),
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','CONFIRMED','SEATED','CANCELLED','NO_SHOW')),
    notes VARCHAR(300),
    session_id UUID REFERENCES table_sessions(id),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_reservations_branch_time ON reservations(branch_id, reserved_at);
CREATE INDEX idx_reservations_table_time ON reservations(table_id, reserved_at) WHERE status IN ('PENDING','CONFIRMED');
