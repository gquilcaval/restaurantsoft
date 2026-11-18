-- Actualiza el color predeterminado sin alterar marcas personalizadas por otros restaurantes.
ALTER TABLE organizations
    ALTER COLUMN primary_color SET DEFAULT '#44D62C';

UPDATE organizations
SET primary_color = '#44D62C', updated_at = NOW()
WHERE name = 'RestOS Demo'
  AND primary_color = '#4F6F52';
