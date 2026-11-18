-- Módulo de asistencia (adicional por restaurante): marcaciones del personal desde la tablet del local.
ALTER TABLE organizations ADD COLUMN attendance_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE attendance_marks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    user_id UUID NOT NULL REFERENCES app_users(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('IN','BREAK_START','BREAK_END','OUT')),
    -- La hora la pone el servidor; work_date es el día de trabajo (hora del local) al que pertenece la jornada,
    -- así un turno de noche que termina de madrugada sigue contando para el día en que empezó.
    marked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    work_date DATE NOT NULL,
    source VARCHAR(10) NOT NULL DEFAULT 'DEVICE' CHECK (source IN ('DEVICE','MANUAL')),
    device_id UUID REFERENCES staff_devices(id),
    -- Correcciones del encargado: quién la registró o anuló y por qué.
    created_by UUID REFERENCES app_users(id),
    note VARCHAR(200),
    voided_at TIMESTAMPTZ,
    voided_by UUID REFERENCES app_users(id),
    void_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_attendance_branch_date ON attendance_marks (branch_id, work_date);
CREATE INDEX idx_attendance_user_time ON attendance_marks (user_id, marked_at DESC);
