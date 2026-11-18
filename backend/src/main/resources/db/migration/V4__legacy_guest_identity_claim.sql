-- Permite que los navegadores abiertos antes de V3 recuperen una sola vez la nueva credencial individual.
ALTER TABLE guests ADD COLUMN identity_claimed BOOLEAN NOT NULL DEFAULT FALSE;

