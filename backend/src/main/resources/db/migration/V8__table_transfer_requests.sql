-- Solicitudes auditables para mover un comensal y sus consumos entre mesas.
CREATE TABLE table_transfer_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    guest_id UUID NOT NULL REFERENCES guests(id),
    source_session_id UUID NOT NULL REFERENCES table_sessions(id),
    destination_table_id UUID NOT NULL REFERENCES dining_tables(id),
    destination_session_id UUID REFERENCES table_sessions(id),
    source_table_label VARCHAR(80) NOT NULL,
    destination_table_label VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(250),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    handled_at TIMESTAMPTZ,
    handled_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_pending_transfer_per_guest
    ON table_transfer_requests(guest_id) WHERE status = 'PENDING';
CREATE INDEX idx_transfer_branch_status
    ON table_transfer_requests(branch_id,status,requested_at);
