-- Quien usa el sistema solo desde la tablet del local entra con su PIN: el correo y la contraseña pasan a ser
-- opcionales (sirven para entrar desde su propio celular). Con acceso al sistema debe tener al menos una forma de entrar.
ALTER TABLE app_users DROP CONSTRAINT app_users_access_credentials_check;
ALTER TABLE app_users ADD CONSTRAINT app_users_access_credentials_check CHECK (
    role = 'STAFF'
    OR (email IS NOT NULL AND password_hash IS NOT NULL)
    OR (email IS NULL AND password_hash IS NULL AND pin_hash IS NOT NULL)
);
