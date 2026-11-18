-- Los cobros anteriores conservan su historial; no se asignan retroactivamente a una caja.
CREATE TABLE cash_shifts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    opened_by UUID NOT NULL REFERENCES app_users(id),
    closed_by UUID REFERENCES app_users(id),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ,
    opening_amount NUMERIC(12,2) NOT NULL CHECK (opening_amount >= 0),
    counted_amount NUMERIC(12,2) CHECK (counted_amount >= 0),
    expected_amount NUMERIC(12,2),
    opening_note VARCHAR(300) NOT NULL DEFAULT '',
    closing_note VARCHAR(300),
    CHECK ((closed_at IS NULL AND closed_by IS NULL AND counted_amount IS NULL AND expected_amount IS NULL)
        OR (closed_at IS NOT NULL AND closed_by IS NOT NULL AND counted_amount IS NOT NULL AND expected_amount IS NOT NULL))
);
CREATE UNIQUE INDEX uq_cash_shift_open ON cash_shifts(branch_id) WHERE closed_at IS NULL;
CREATE INDEX idx_cash_shift_history ON cash_shifts(branch_id,opened_at DESC);

CREATE TABLE cash_movements (
    id UUID PRIMARY KEY,
    shift_id UUID NOT NULL REFERENCES cash_shifts(id),
    kind VARCHAR(3) NOT NULL CHECK (kind IN ('IN','OUT')),
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    reason VARCHAR(300) NOT NULL,
    registered_by UUID NOT NULL REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cash_movements_shift ON cash_movements(shift_id,created_at);
ALTER TABLE payments ADD COLUMN cash_shift_id UUID REFERENCES cash_shifts(id);
CREATE INDEX idx_payments_cash_shift ON payments(cash_shift_id);
CREATE INDEX idx_payments_branch_date ON payments(branch_id,paid_at DESC);
