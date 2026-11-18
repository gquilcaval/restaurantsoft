-- Horarios del personal y tolerancia de tardanza (módulo de asistencia).
ALTER TABLE organizations
    ADD COLUMN attendance_tolerance_minutes INT NOT NULL DEFAULT 10
        CHECK (attendance_tolerance_minutes BETWEEN 0 AND 60);

-- Cada cambio de horario crea una versión que rige desde valid_from: los días pasados se evalúan
-- con el horario que tenían. Una versión son 7 filas (1 = lunes … 7 = domingo); sin horas = descanso.
-- Una versión con los 7 días sin horas significa «sin horario fijo».
CREATE TABLE staff_schedule_days (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    valid_from DATE NOT NULL,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    starts_at TIME,
    ends_at TIME,
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, valid_from, weekday),
    CHECK ((starts_at IS NULL) = (ends_at IS NULL)),
    CHECK (starts_at IS NULL OR starts_at <> ends_at)
);
