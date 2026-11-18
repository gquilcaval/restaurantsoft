-- Estilo visual de la carta QR que ve el comensal.
ALTER TABLE organizations ADD COLUMN menu_theme VARCHAR(12) NOT NULL DEFAULT 'CLASSIC'
    CHECK (menu_theme IN ('CLASSIC','MODERN','FRESH','WARM'));
UPDATE organizations SET menu_theme='FRESH' WHERE slug='restos-showcase';
