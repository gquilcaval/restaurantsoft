-- Llamado al mesero: motivo (opcional), confirmación del comensal e insistencia si no lo atienden.
ALTER TABLE service_requests
  ADD COLUMN reason VARCHAR(20),
  ADD COLUMN escalation_level SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN escalated_at TIMESTAMPTZ;
ALTER TABLE service_requests ADD CONSTRAINT service_requests_reason_check
  CHECK (reason IS NULL OR reason IN ('CUTLERY','DRINK','QUESTION','PROBLEM'));
