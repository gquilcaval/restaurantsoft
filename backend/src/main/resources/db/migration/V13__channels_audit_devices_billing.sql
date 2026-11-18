-- Pedidos sin mesa: para llevar y delivery reutilizan la atención para compartir cocina, cobro y caja.
ALTER TABLE table_sessions
    ALTER COLUMN table_id DROP NOT NULL,
    ADD COLUMN channel VARCHAR(12) NOT NULL DEFAULT 'DINE_IN' CHECK (channel IN ('DINE_IN','TAKEAWAY','DELIVERY')),
    ADD COLUMN label VARCHAR(80),
    ADD COLUMN order_number INTEGER,
    ADD COLUMN customer_name VARCHAR(120),
    ADD COLUMN customer_phone VARCHAR(30),
    ADD COLUMN delivery_address VARCHAR(250),
    ADD COLUMN delivery_reference VARCHAR(250),
    ADD COLUMN delivery_fee NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (delivery_fee >= 0),
    ADD CONSTRAINT ck_session_channel_table CHECK ((channel = 'DINE_IN') = (table_id IS NOT NULL));
CREATE INDEX idx_sessions_counter_open ON table_sessions(branch_id, opened_at DESC)
    WHERE channel <> 'DINE_IN';

-- Cuenta dividida: un pago puede atribuirse a la persona que lo hizo.
ALTER TABLE payments ADD COLUMN guest_id UUID REFERENCES guests(id);

-- Estaciones de preparación (cocina, barra, postres): cada categoría se prepara en una estación.
CREATE TABLE kitchen_stations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(60) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (organization_id, name)
);
ALTER TABLE menu_categories ADD COLUMN station_id UUID REFERENCES kitchen_stations(id);

-- Tablets compartidas del local: el dispositivo se autoriza una vez y el personal entra con PIN.
ALTER TABLE app_users ADD COLUMN pin_hash VARCHAR(255);
CREATE TABLE staff_devices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    name VARCHAR(80) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_staff_devices_branch ON staff_devices(branch_id) WHERE revoked_at IS NULL;

-- Bitácora de acciones sensibles (anulaciones, descuentos, cobros, caja, precios, accesos).
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID REFERENCES branches(id),
    user_id UUID REFERENCES app_users(id),
    action VARCHAR(40) NOT NULL,
    summary VARCHAR(400) NOT NULL,
    amount NUMERIC(12,2),
    entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_branch_date ON audit_events(branch_id, created_at DESC);
CREATE INDEX idx_audit_org_action ON audit_events(organization_id, action, created_at DESC);

-- Suscripción del restaurante a RestOS: plan, vigencia pagada y periodo de prueba.
ALTER TABLE organizations
    ADD COLUMN plan VARCHAR(20) NOT NULL DEFAULT 'ESTANDAR',
    ADD COLUMN monthly_fee NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (monthly_fee >= 0),
    ADD COLUMN max_branches INTEGER NOT NULL DEFAULT 1 CHECK (max_branches >= 1),
    ADD COLUMN trial_ends_at DATE,
    ADD COLUMN paid_until DATE,
    ADD COLUMN billing_note VARCHAR(300);
-- Los restaurantes existentes reciben 30 días para regularizar sin cortar su servicio.
UPDATE organizations SET trial_ends_at = CURRENT_DATE + 30,
    max_branches = GREATEST(1, (SELECT count(*) FROM branches b WHERE b.organization_id = organizations.id));

CREATE TABLE subscription_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    months INTEGER NOT NULL CHECK (months BETWEEN 1 AND 24),
    paid_until DATE NOT NULL,
    reference VARCHAR(120),
    registered_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_subscription_payments_org ON subscription_payments(organization_id, created_at DESC);
