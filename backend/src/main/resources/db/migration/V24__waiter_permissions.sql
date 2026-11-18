-- Permisos opcionales del mesero: módulo Mostrador y gestión completa de reservas (apagados por defecto).
ALTER TABLE app_users
  ADD COLUMN can_counter BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN can_reservations BOOLEAN NOT NULL DEFAULT FALSE;
