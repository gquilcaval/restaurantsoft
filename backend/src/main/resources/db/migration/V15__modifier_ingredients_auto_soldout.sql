-- Un extra puede consumir un insumo (por ejemplo «Queso extra» usa 0.03 kg de queso).
ALTER TABLE menu_modifier_options
    ADD COLUMN ingredient_id UUID REFERENCES ingredients(id) ON DELETE SET NULL,
    ADD COLUMN ingredient_quantity NUMERIC(14,3) CHECK (ingredient_quantity > 0),
    ADD CONSTRAINT ck_modifier_ingredient CHECK ((ingredient_id IS NULL) = (ingredient_quantity IS NULL));

-- Copia de lo que consumió cada opción elegida, para descontar y devolver stock aunque cambie la carta.
ALTER TABLE order_item_modifiers
    ADD COLUMN ingredient_id UUID REFERENCES ingredients(id) ON DELETE SET NULL,
    ADD COLUMN ingredient_quantity NUMERIC(14,3);

-- Agotados puestos por el sistema al quedarse sin insumos; se levantan solos al reponer stock.
ALTER TABLE menu_item_unavailability ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT FALSE;
