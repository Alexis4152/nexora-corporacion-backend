-- =====================================================================================
-- V7: vendedor de cada contratación. Permite ver y medir lo que vende cada ejecutivo (socio).
-- El vendedor es quien cerró la venta; puede diferir del ejecutivo asignado hoy al cliente.
-- =====================================================================================

ALTER TABLE subscriptions ADD COLUMN seller_user_id BIGINT REFERENCES users(id);

-- Suscripciones existentes: el ejecutivo del cliente o, si no tiene, quien capturó la suscripción.
UPDATE subscriptions s
SET seller_user_id = COALESCE(c.owner_user_id, (SELECT u.id FROM users u WHERE u.id = s.created_by))
FROM customers c
WHERE c.id = s.customer_id;

CREATE INDEX idx_subscriptions_seller ON subscriptions (seller_user_id);
CREATE INDEX idx_quotes_owner ON quotes (owner_user_id);
