-- Conserva inmutable el historial anterior y establece la nueva identidad predeterminada.
ALTER TABLE organizations
    ALTER COLUMN primary_color SET DEFAULT '#4F6F52';

-- Solo migra la organización de demostración; las marcas configuradas por clientes no se modifican.
UPDATE organizations
SET primary_color = '#4F6F52', updated_at = NOW()
WHERE name = 'RestOS Demo'
  AND primary_color = '#E4511E';
