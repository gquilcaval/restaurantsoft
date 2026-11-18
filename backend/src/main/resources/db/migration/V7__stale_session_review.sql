-- Una atención con consumo nunca se elimina automáticamente: queda bloqueada hasta revisión del personal.
ALTER TABLE table_sessions
    ADD COLUMN requires_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN reviewed_by UUID REFERENCES app_users(id);

CREATE INDEX idx_sessions_pending_review
    ON table_sessions(branch_id, requires_review)
    WHERE status = 'OPEN' AND requires_review;
