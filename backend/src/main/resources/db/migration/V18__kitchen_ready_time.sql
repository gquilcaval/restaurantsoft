-- Momento en que cocina marcó el plato como listo: permite medir el tiempo real de preparación.
ALTER TABLE order_items ADD COLUMN ready_at TIMESTAMPTZ;
CREATE INDEX idx_order_items_ready_at ON order_items(ready_at) WHERE ready_at IS NOT NULL;
CREATE INDEX idx_payments_branch_paid ON payments(branch_id, paid_at);
CREATE INDEX idx_sessions_branch_opened ON table_sessions(branch_id, opened_at);
