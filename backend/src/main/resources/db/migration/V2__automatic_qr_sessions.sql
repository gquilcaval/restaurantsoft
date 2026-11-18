-- Una sesión iniciada por el QR no necesita un usuario del personal como responsable de apertura.
ALTER TABLE table_sessions ALTER COLUMN opened_by DROP NOT NULL;
ALTER TABLE table_sessions ADD COLUMN opened_source VARCHAR(20) NOT NULL DEFAULT 'STAFF';

