-- Equipo pasa a ser el registro de todo el personal: datos del trabajador y personas sin acceso al sistema
-- (rol STAFF: no inician sesión; más adelante solo marcan asistencia con su PIN).
ALTER TABLE app_users
  ALTER COLUMN email DROP NOT NULL,
  ALTER COLUMN password_hash DROP NOT NULL,
  ADD COLUMN document_number VARCHAR(20),
  ADD COLUMN phone VARCHAR(30),
  ADD COLUMN job_title VARCHAR(60),
  ADD COLUMN hired_on DATE;

-- Quien tiene acceso al sistema siempre necesita correo y contraseña.
ALTER TABLE app_users ADD CONSTRAINT app_users_access_credentials_check
  CHECK (role = 'STAFF' OR (email IS NOT NULL AND password_hash IS NOT NULL));

-- Un mismo documento (DNI o carné de extranjería) no se repite dentro del restaurante.
CREATE UNIQUE INDEX app_users_org_document_unique
  ON app_users (organization_id, upper(document_number)) WHERE document_number IS NOT NULL;
