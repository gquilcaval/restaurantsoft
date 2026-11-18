-- Etiquetas de la carta (picante, vegano…), separadas por comas.
ALTER TABLE menu_items ADD COLUMN tags VARCHAR(120) NOT NULL DEFAULT '';

-- Cómo quiere pagar la mesa cuando pide la cuenta desde el celular, y la propina sugerida.
ALTER TABLE table_sessions
    ADD COLUMN bill_split_mode VARCHAR(10) CHECK (bill_split_mode IN ('SINGLE','GUEST','EQUAL')),
    ADD COLUMN bill_split_parts SMALLINT CHECK (bill_split_parts BETWEEN 1 AND 30),
    ADD COLUMN bill_tip_percent SMALLINT CHECK (bill_tip_percent BETWEEN 0 AND 50),
    ADD COLUMN bill_note VARCHAR(160);

-- Calificación del servicio al terminar la visita; alimenta el ranking de meseros.
CREATE TABLE service_ratings (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    session_id UUID NOT NULL REFERENCES table_sessions(id),
    guest_id UUID REFERENCES guests(id),
    waiter_id UUID REFERENCES app_users(id),
    stars SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    highlights VARCHAR(200),
    comment VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (session_id, guest_id)
);
CREATE INDEX idx_service_ratings_branch ON service_ratings(branch_id, created_at);
CREATE INDEX idx_service_ratings_waiter ON service_ratings(waiter_id, created_at);
