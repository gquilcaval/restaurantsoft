-- Anulaciones auditables: el plato anulado se conserva con su motivo y responsable, fuera del total.
ALTER TABLE order_items
    ADD COLUMN void_reason VARCHAR(250),
    ADD COLUMN voided_by UUID REFERENCES app_users(id),
    ADD COLUMN voided_at TIMESTAMPTZ,
    -- Los pedidos tomados por el mesero no pertenecen a un comensal del QR.
    ADD COLUMN created_by UUID REFERENCES app_users(id),
    ALTER COLUMN guest_id DROP NOT NULL;
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_owner CHECK (guest_id IS NOT NULL OR created_by IS NOT NULL);
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_void
    CHECK ((status = 'CANCELLED') = (voided_at IS NOT NULL));
CREATE INDEX idx_order_items_voided ON order_items(voided_at) WHERE voided_at IS NOT NULL;

-- Agotado por sucursal: la presencia de la fila indica que el producto no se puede pedir hoy.
CREATE TABLE menu_item_unavailability (
    branch_id UUID NOT NULL REFERENCES branches(id),
    menu_item_id UUID NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    marked_by UUID REFERENCES app_users(id),
    marked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (branch_id, menu_item_id)
);

-- Opciones y extras configurables por producto (término, tamaño, agregados con precio).
CREATE TABLE menu_modifier_groups (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    menu_item_id UUID NOT NULL REFERENCES menu_items(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    min_select INTEGER NOT NULL DEFAULT 0 CHECK (min_select >= 0),
    max_select INTEGER NOT NULL DEFAULT 1 CHECK (max_select >= 1),
    display_order INTEGER NOT NULL DEFAULT 0,
    CHECK (min_select <= max_select)
);
CREATE INDEX idx_modifier_groups_item ON menu_modifier_groups(menu_item_id, display_order);

CREATE TABLE menu_modifier_options (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES menu_modifier_groups(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    price_delta NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (price_delta >= 0),
    display_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_modifier_options_group ON menu_modifier_options(group_id, display_order);

-- Copia inmutable de lo elegido: editar la carta no altera pedidos ya registrados.
CREATE TABLE order_item_modifiers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_item_id UUID NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    group_name VARCHAR(80) NOT NULL,
    option_name VARCHAR(80) NOT NULL,
    price_delta NUMERIC(12,2) NOT NULL DEFAULT 0,
    display_order INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_order_item_modifiers_item ON order_item_modifiers(order_item_id);

-- Descuento único por atención; el monto se recalcula sobre el consumo vigente.
ALTER TABLE table_sessions
    ADD COLUMN discount_kind VARCHAR(10) CHECK (discount_kind IN ('PERCENT','AMOUNT')),
    ADD COLUMN discount_value NUMERIC(12,2) CHECK (discount_value > 0),
    ADD COLUMN discount_reason VARCHAR(250),
    ADD COLUMN discount_by UUID REFERENCES app_users(id),
    ADD COLUMN discount_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_session_discount CHECK ((discount_kind IS NULL) = (discount_value IS NULL)),
    ADD CONSTRAINT ck_session_discount_percent CHECK (discount_kind <> 'PERCENT' OR discount_value <= 100);

-- Propina separada del consumo y efectivo recibido para calcular el vuelto.
ALTER TABLE payments
    ADD COLUMN tip NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (tip >= 0),
    ADD COLUMN amount_received NUMERIC(12,2) CHECK (amount_received >= 0),
    ADD COLUMN change_given NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (change_given >= 0);
