-- Plano visual del salón: posición, forma y giro de cada mesa dentro del plano de su zona.
ALTER TABLE dining_tables
    ADD COLUMN plan_x INTEGER CHECK (plan_x BETWEEN 0 AND 2000),
    ADD COLUMN plan_y INTEGER CHECK (plan_y BETWEEN 0 AND 2000),
    ADD COLUMN plan_shape VARCHAR(10) NOT NULL DEFAULT 'SQUARE' CHECK (plan_shape IN ('SQUARE','ROUND','RECT')),
    ADD COLUMN plan_rotation SMALLINT NOT NULL DEFAULT 0 CHECK (plan_rotation BETWEEN 0 AND 359);

-- Elementos decorativos (barra, puerta, cocina, baños…) que hacen reconocible el plano del local.
CREATE TABLE floor_elements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    zone_id UUID REFERENCES dining_zones(id) ON DELETE CASCADE,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('BAR','WALL','DOOR','KITCHEN','WC','CASHIER','PLANT','WINDOW','STAIRS')),
    label VARCHAR(40),
    x INTEGER NOT NULL CHECK (x BETWEEN 0 AND 2000),
    y INTEGER NOT NULL CHECK (y BETWEEN 0 AND 2000),
    width INTEGER NOT NULL CHECK (width BETWEEN 10 AND 2000),
    height INTEGER NOT NULL CHECK (height BETWEEN 10 AND 2000),
    rotation SMALLINT NOT NULL DEFAULT 0 CHECK (rotation BETWEEN 0 AND 359),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_floor_elements_branch ON floor_elements(branch_id, zone_id);
