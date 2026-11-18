-- Estudio de planos: foto de fondo, medida real, aforo, configuraciones guardadas, mesero por mesa y reservas online.
ALTER TABLE dining_zones
  ADD COLUMN plan_background_url varchar(500),
  ADD COLUMN plan_background_opacity smallint NOT NULL DEFAULT 35 CHECK (plan_background_opacity BETWEEN 0 AND 100),
  ADD COLUMN plan_width_meters numeric(5,1) NOT NULL DEFAULT 12 CHECK (plan_width_meters BETWEEN 3 AND 200),
  ADD COLUMN max_capacity integer CHECK (max_capacity IS NULL OR max_capacity BETWEEN 1 AND 5000),
  ADD COLUMN active_layout_id uuid;

ALTER TABLE dining_tables ADD COLUMN waiter_id uuid REFERENCES app_users(id) ON DELETE SET NULL;

CREATE TABLE floor_layouts (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organizations(id),
  branch_id uuid NOT NULL REFERENCES branches(id),
  zone_id uuid NOT NULL REFERENCES dining_zones(id) ON DELETE CASCADE,
  name varchar(60) NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_floor_layouts_zone ON floor_layouts(zone_id);

ALTER TABLE branches ADD COLUMN online_booking boolean NOT NULL DEFAULT false;
