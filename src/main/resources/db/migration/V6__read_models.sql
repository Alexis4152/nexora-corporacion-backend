-- =====================================================================================
-- V6: modelos de lectura. Estado de la relación comercial del cliente y estado de cobranza de cada suscripción
-- se CALCULAN a partir de suscripciones, cargos y gestiones: nunca se guardan en un solo campo que pueda
-- desincronizarse. Ver docs/04-REGLAS-FINANCIERAS.md §Estados.
-- =====================================================================================

-- Fecha de negocio "hoy" en la zona horaria configurada (no la del servidor).
CREATE OR REPLACE FUNCTION nx_today() RETURNS date AS $$
    SELECT (now() AT TIME ZONE COALESCE(
        (SELECT value #>> '{}' FROM system_settings WHERE setting_key = 'business.timezone'), 'America/Mexico_City'))::date;
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION nx_setting_int(p_key text, p_default int) RETURNS int AS $$
    SELECT COALESCE((SELECT (value #>> '{}')::int FROM system_settings WHERE setting_key = p_key), p_default);
$$ LANGUAGE sql STABLE;

-- Saldo vivo de cada cargo.
CREATE VIEW charge_balances AS
SELECT ch.*, (ch.total - ch.paid_amount - ch.adjusted_amount) AS balance,
       GREATEST(0, nx_today() - ch.due_date) AS days_overdue
FROM charges ch;

-- Estado de cobranza por suscripción (independiente del estado del ciclo de vida).
CREATE VIEW subscription_collection AS
WITH open_charges AS (
    SELECT subscription_id,
           SUM(total - paid_amount - adjusted_amount) AS open_amount,
           SUM(CASE WHEN due_date < nx_today() THEN total - paid_amount - adjusted_amount ELSE 0 END) AS overdue_amount,
           MIN(due_date) FILTER (WHERE due_date < nx_today()) AS oldest_overdue,
           MIN(due_date) AS next_due,
           COUNT(*) FILTER (WHERE due_date < nx_today()) AS overdue_count,
           bool_or(status = 'PARTIAL') AS has_partial
    FROM charges WHERE status IN ('OPEN', 'PARTIAL') AND subscription_id IS NOT NULL
    GROUP BY subscription_id
)
SELECT s.id AS subscription_id, s.customer_id,
       COALESCE(o.open_amount, 0) AS open_amount,
       COALESCE(o.overdue_amount, 0) AS overdue_amount,
       COALESCE(o.overdue_count, 0) AS overdue_count,
       o.oldest_overdue,
       o.next_due,
       CASE WHEN o.oldest_overdue IS NULL THEN 0 ELSE nx_today() - o.oldest_overdue END AS days_overdue,
       CASE
         WHEN o.overdue_amount > 0 AND EXISTS (
              SELECT 1 FROM collection_actions ca WHERE ca.customer_id = s.customer_id
                AND (ca.subscription_id IS NULL OR ca.subscription_id = s.id)
                AND ca.created_at >= o.oldest_overdue) THEN 'IN_COLLECTION'
         WHEN o.overdue_amount > 0 THEN 'OVERDUE'
         WHEN o.has_partial THEN 'PARTIAL'
         WHEN o.next_due IS NOT NULL AND o.next_due <= nx_today() + nx_setting_int('billing.dueSoonDays', 5) THEN 'DUE_SOON'
         WHEN o.next_due IS NOT NULL THEN 'PENDING'
         ELSE 'CURRENT'
       END AS collection_status
FROM subscriptions s LEFT JOIN open_charges o ON o.subscription_id = s.id;

-- Resumen por cliente. Precedencia de la relación: ACTIVE > TRIAL > SUSPENDED > PENDING > CANCELLED > NONE.
CREATE VIEW customer_overview AS
WITH subs AS (
    SELECT customer_id,
           COUNT(*) AS total_subs,
           COUNT(*) FILTER (WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')) AS active_subs,
           COUNT(*) FILTER (WHERE status = 'TRIAL') AS trial_subs,
           COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended_subs,
           COUNT(*) FILTER (WHERE status = 'PENDING_ACTIVATION') AS pending_subs,
           SUM(mrr) AS mrr
    FROM subscriptions GROUP BY customer_id
), money AS (
    SELECT customer_id,
           SUM(total - paid_amount - adjusted_amount) AS balance,
           SUM(CASE WHEN due_date < nx_today() THEN total - paid_amount - adjusted_amount ELSE 0 END) AS overdue,
           MIN(due_date) FILTER (WHERE due_date < nx_today()) AS oldest_overdue
    FROM charges WHERE status IN ('OPEN', 'PARTIAL') GROUP BY customer_id
)
SELECT c.id AS customer_id,
       CASE WHEN c.archived THEN 'ARCHIVED'
            WHEN COALESCE(s.active_subs, 0) > 0 THEN 'ACTIVE'
            WHEN COALESCE(s.trial_subs, 0) > 0 THEN 'TRIAL'
            WHEN COALESCE(s.suspended_subs, 0) > 0 THEN 'SUSPENDED'
            WHEN COALESCE(s.pending_subs, 0) > 0 THEN 'PENDING'
            WHEN COALESCE(s.total_subs, 0) > 0 THEN 'CANCELLED'
            ELSE 'NO_SUBSCRIPTION' END AS relationship,
       COALESCE(s.total_subs, 0) AS total_subs,
       COALESCE(s.active_subs, 0) AS active_subs,
       COALESCE(s.trial_subs, 0) AS trial_subs,
       COALESCE(s.mrr, 0) AS mrr,
       COALESCE(m.balance, 0) AS balance,
       COALESCE(m.overdue, 0) AS overdue,
       m.oldest_overdue,
       CASE WHEN m.oldest_overdue IS NULL THEN 0 ELSE nx_today() - m.oldest_overdue END AS days_overdue
FROM customers c LEFT JOIN subs s ON s.customer_id = c.id LEFT JOIN money m ON m.customer_id = c.id;

-- Saldo a favor de cada pago: importe vigente − aplicaciones vigentes − devoluciones.
CREATE VIEW payment_balances AS
SELECT p.id AS payment_id, p.customer_id, p.status, p.amount,
       COALESCE((SELECT SUM(a.amount) FROM payment_allocations a WHERE a.payment_id = p.id AND a.reversed_at IS NULL), 0) AS allocated,
       COALESCE((SELECT SUM(r.amount) FROM payment_refunds r WHERE r.payment_id = p.id), 0) AS refunded,
       CASE WHEN p.status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED')
            THEN p.amount
                 - COALESCE((SELECT SUM(a.amount) FROM payment_allocations a WHERE a.payment_id = p.id AND a.reversed_at IS NULL), 0)
                 - COALESCE((SELECT SUM(r.amount) FROM payment_refunds r WHERE r.payment_id = p.id), 0)
            ELSE 0 END AS unapplied
FROM payments p;

CREATE INDEX idx_collection_actions_sub ON collection_actions (subscription_id);
