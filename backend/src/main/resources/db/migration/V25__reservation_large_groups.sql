-- Reservas de grupos grandes (eventos, empresas): hasta 200 personas.
ALTER TABLE reservations DROP CONSTRAINT IF EXISTS reservations_party_size_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_party_size_check CHECK (party_size BETWEEN 1 AND 200);
