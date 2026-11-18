-- Comandas impresas: ticketeras en red del local, qué estación imprime en cuál, el programa puente
-- que las conecta con RestOS y la cola de impresión.

CREATE TABLE printers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    name VARCHAR(60) NOT NULL,
    host VARCHAR(120) NOT NULL,
    port INT NOT NULL DEFAULT 9100 CHECK (port BETWEEN 1 AND 65535),
    paper_width INT NOT NULL DEFAULT 80 CHECK (paper_width IN (58, 80)),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_ok_at TIMESTAMPTZ,
    last_error VARCHAR(250),
    last_error_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (branch_id, name)
);

-- Una fila = esa estación imprime comandas en esta sucursal (sin fila, usa la pantalla de cocina).
-- station_id NULL: productos cuya categoría no tiene estación.
CREATE TABLE print_routes (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL REFERENCES branches(id),
    station_id UUID REFERENCES kitchen_stations(id) ON DELETE CASCADE,
    printer_id UUID NOT NULL REFERENCES printers(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX print_routes_branch_station
    ON print_routes(branch_id, COALESCE(station_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Programa puente instalado en una PC del local: se vincula con un código y luego usa su token.
CREATE TABLE print_agents (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    name VARCHAR(60) NOT NULL,
    token_hash VARCHAR(64) UNIQUE,
    pairing_code VARCHAR(6),
    pairing_expires_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    version VARCHAR(20),
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ
);

-- Cola de impresión. content: texto del ticket con marcas simples (#BIG, #B, #C, #HR) que sirve
-- para la vista previa y para generar los bytes ESC/POS.
CREATE TABLE print_jobs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    branch_id UUID NOT NULL REFERENCES branches(id),
    printer_id UUID REFERENCES printers(id) ON DELETE SET NULL,
    session_id UUID REFERENCES table_sessions(id),
    order_id UUID REFERENCES orders(id),
    station_name VARCHAR(60),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('COMANDA', 'ANULACION', 'PRUEBA')),
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'PRINTED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    error VARCHAR(250),
    alerted_at TIMESTAMPTZ,
    created_by UUID REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    printed_at TIMESTAMPTZ
);
CREATE INDEX idx_print_jobs_queue ON print_jobs(branch_id, status, created_at);
CREATE INDEX idx_print_jobs_order ON print_jobs(order_id);
