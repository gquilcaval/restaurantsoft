-- Los comensales se retiran sin perder el historial de consumos ni la trazabilidad operativa.
ALTER TABLE guests ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE guests ADD COLUMN left_at TIMESTAMPTZ;
ALTER TABLE guests ADD COLUMN left_reason VARCHAR(250);
ALTER TABLE guests ADD COLUMN left_by UUID REFERENCES app_users(id);

CREATE INDEX idx_guests_session_active ON guests(session_id, active);
