package com.nexora.corp.analytics;

import com.nexora.corp.customers.CustomerService;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.subscriptions.SubscriptionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ficha integral del cliente: datos, suscripciones con balances independientes, dinero, soporte y línea de tiempo. */
@Service
@RequiredArgsConstructor
public class Customer360Service {

    private final CustomerService customers;
    private final SubscriptionQueryService subscriptions;
    private final HealthService health;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", customers.get(id));
        Map<String, Object> ov = jdbc.queryForMap("SELECT relationship, active_subs, trial_subs, total_subs FROM customer_overview WHERE customer_id = :id", Map.of("id", id));
        out.put("relationship", ov);
        if (SecurityUtils.has("subscriptions.read")) {
            out.put("subscriptions", subscriptions.forCustomer(id));
        }
        if (SecurityUtils.has("charges.read") || SecurityUtils.has("collections.read")) {
            out.put("money", jdbc.queryForMap("""
                    SELECT ROUND(o.mrr, 2) AS mrr, o.balance, o.overdue, o.days_overdue, o.oldest_overdue,
                      (SELECT COALESCE(SUM(total), 0) FROM charges WHERE customer_id = :id AND status <> 'VOID') AS total_billed,
                      (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE customer_id = :id AND status IN ('CONFIRMED', 'RECONCILED'))
                        - (SELECT COALESCE(SUM(r.amount), 0) FROM payment_refunds r JOIN payments p ON p.id = r.payment_id WHERE p.customer_id = :id) AS total_collected,
                      (SELECT COALESCE(SUM(unapplied), 0) FROM payment_balances WHERE customer_id = :id) AS credit,
                      (SELECT MAX(received_on) FROM payments WHERE customer_id = :id AND status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED')) AS last_payment_on,
                      (SELECT MIN(due_date) FROM charges WHERE customer_id = :id AND status IN ('OPEN', 'PARTIAL')) AS next_due
                    FROM customer_overview o WHERE o.customer_id = :id""", Map.of("id", id)));
        }
        if (SecurityUtils.has("support.read")) {
            out.put("support", jdbc.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CLOSED')) AS open, COUNT(*) AS total FROM support_tickets WHERE customer_id = :id""",
                    Map.of("id", id)));
        }
        out.put("health", health.of(id));
        out.put("contacts", customers.contacts(id));
        return out;
    }

    /** Línea de tiempo unificada (lo que el usuario puede ver según sus permisos). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(Long id) {
        customers.require(id);
        StringBuilder sql = new StringBuilder("""
                SELECT 'SUBSCRIPTION' AS kind, e.type AS type, e.created_at AS at, s.code || ' · ' || p.name AS title,
                       e.from_status || ' → ' || e.to_status AS detail, '/suscripciones/' || s.id AS link, e.movement AS extra
                FROM subscription_events e JOIN subscriptions s ON s.id = e.subscription_id JOIN products p ON p.id = e.product_id WHERE e.customer_id = :id
                UNION ALL
                SELECT 'ACTIVITY', a.type, COALESCE(a.completed_at, a.scheduled_at, a.created_at), a.subject, a.body, NULL, NULL
                FROM sales_activities a WHERE a.customer_id = :id OR a.prospect_id = (SELECT source_prospect_id FROM customers WHERE id = :id)
                UNION ALL
                SELECT 'NOTE', 'NOTE', n.created_at, 'Nota interna', n.body, NULL, NULL FROM customer_notes n WHERE n.customer_id = :id
                """);
        if (SecurityUtils.has("payments.read")) {
            sql.append("""
                    UNION ALL
                    SELECT 'PAYMENT', py.status, py.created_at, 'Pago ' || py.folio || ' · $' || py.amount, py.method || COALESCE(' · ' || py.reference, ''),
                           '/cobranza/pagos/' || py.id, NULL FROM payments py WHERE py.customer_id = :id
                    """);
        }
        if (SecurityUtils.has("charges.read")) {
            sql.append("""
                    UNION ALL
                    SELECT 'CHARGE', ch.type, ch.created_at, 'Cargo ' || ch.folio || ' · $' || ch.total, ch.description, NULL, ch.status
                    FROM charges ch WHERE ch.customer_id = :id
                    """);
        }
        if (SecurityUtils.has("collections.read")) {
            sql.append("""
                    UNION ALL
                    SELECT 'COLLECTION', ca.type, ca.created_at, 'Gestión de cobranza', ca.notes, NULL, NULL FROM collection_actions ca WHERE ca.customer_id = :id
                    """);
        }
        if (SecurityUtils.has("support.read")) {
            sql.append("""
                    UNION ALL
                    SELECT 'TICKET', t.status, t.created_at, 'Ticket ' || t.folio || ' · ' || t.subject, t.priority, '/soporte/' || t.id, NULL
                    FROM support_tickets t WHERE t.customer_id = :id
                    """);
        }
        sql.append(" ORDER BY at DESC LIMIT 250");
        return jdbc.queryForList(sql.toString(), Map.of("id", id));
    }

    /** Estado de cuenta: cargos y pagos con saldo corrido. */
    @Transactional(readOnly = true)
    public Map<String, Object> statement(Long id) {
        customers.require(id);
        List<Map<String, Object>> movements = jdbc.queryForList("""
                SELECT * FROM (
                  SELECT ch.issue_date AS date, 'CHARGE' AS kind, ch.folio, ch.description, ch.total AS debit, 0::numeric AS credit, ch.status,
                         ch.id AS ref_id, ch.created_at FROM charges ch WHERE ch.customer_id = :id AND ch.status <> 'VOID'
                  UNION ALL
                  SELECT a.created_at::date, 'ADJUSTMENT', ch.folio, 'Ajuste: ' || a.reason, 0, a.amount, NULL, a.id, a.created_at
                  FROM charge_adjustments a JOIN charges ch ON ch.id = a.charge_id WHERE ch.customer_id = :id
                  UNION ALL
                  SELECT py.received_on, 'PAYMENT', py.folio, 'Pago ' || py.method || COALESCE(' · ' || py.reference, ''), 0, py.amount, py.status,
                         py.id, py.created_at FROM payments py WHERE py.customer_id = :id AND py.status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED')
                  UNION ALL
                  SELECT r.refunded_on, 'REFUND', py.folio, 'Devolución: ' || r.reason, r.amount, 0, NULL, r.id, r.created_at
                  FROM payment_refunds r JOIN payments py ON py.id = r.payment_id WHERE py.customer_id = :id
                ) m ORDER BY date, created_at""", Map.of("id", id));
        java.math.BigDecimal running = java.math.BigDecimal.ZERO;
        for (Map<String, Object> m : movements) {
            running = running.add(new java.math.BigDecimal(m.get("debit").toString())).subtract(new java.math.BigDecimal(m.get("credit").toString()));
            m.put("balance", running);
        }
        return Map.of("movements", movements, "balance", running,
                "note", "Saldo positivo = el cliente debe; negativo = saldo a favor del cliente.");
    }
}

