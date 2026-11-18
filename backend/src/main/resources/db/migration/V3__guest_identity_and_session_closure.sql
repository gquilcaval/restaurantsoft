-- Cada comensal recibe una credencial distinta; el token compartido de mesa queda solo para lectura y STOMP.
ALTER TABLE guests ADD COLUMN guest_token UUID NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uq_guests_guest_token ON guests(guest_token);

-- Conservamos la razón y el responsable de cierres operativos sin eliminar el historial de la atención.
ALTER TABLE table_sessions ADD COLUMN closed_by UUID REFERENCES app_users(id);
ALTER TABLE table_sessions ADD COLUMN close_reason VARCHAR(250);

