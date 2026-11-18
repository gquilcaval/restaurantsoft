-- Proveedor de cada insumo, para enviarle la lista de compras por WhatsApp.
ALTER TABLE ingredients
    ADD COLUMN supplier_name VARCHAR(100),
    ADD COLUMN supplier_phone VARCHAR(30);

-- Lista de espera: el cliente deja su número y sigue su turno desde el celular.
CREATE TABLE waitlist_entries (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    public_token UUID NOT NULL UNIQUE,
    customer_name VARCHAR(120) NOT NULL,
    phone VARCHAR(30),
    party_size SMALLINT NOT NULL CHECK (party_size BETWEEN 1 AND 50),
    notes VARCHAR(200),
    status VARCHAR(12) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','NOTIFIED','SEATED','CANCELLED','NO_SHOW')),
    table_id UUID REFERENCES dining_tables(id),
    session_id UUID REFERENCES table_sessions(id),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notified_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ
);
CREATE INDEX idx_waitlist_branch_active ON waitlist_entries(branch_id, created_at) WHERE status IN ('WAITING','NOTIFIED');
