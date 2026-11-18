-- Zonas del local (salón, terraza, barra, VIP…). Una zona cerrada no recibe comensales por QR.
CREATE TABLE dining_zones (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    name VARCHAR(60) NOT NULL,
    color VARCHAR(7) NOT NULL DEFAULT '#55758A' CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    display_order INTEGER NOT NULL DEFAULT 0,
    open BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (branch_id, name)
);

ALTER TABLE dining_tables ADD COLUMN zone_id UUID REFERENCES dining_zones(id) ON DELETE SET NULL;
CREATE INDEX idx_tables_zone ON dining_tables(zone_id);

-- Meseros asignados a cada zona: ven primero sus mesas y reciben solo sus avisos.
CREATE TABLE user_zones (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    zone_id UUID NOT NULL REFERENCES dining_zones(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, zone_id)
);

-- Preferencia de zona al reservar («quiero terraza»).
ALTER TABLE reservations ADD COLUMN zone_id UUID REFERENCES dining_zones(id) ON DELETE SET NULL;
