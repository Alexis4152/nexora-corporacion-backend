package com.nexora.corp.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consultas base de indicadores. El MRR histórico se reconstruye del libro de eventos (cada evento guarda el MRR
 * posterior de su suscripción): MRR(fecha) = Σ por suscripción del {@code mrr_after} de su último evento con
 * {@code effective_on <= fecha}. Así el MRR de un mes pasado no cambia aunque hoy cambie una suscripción.
 */
@Component
@RequiredArgsConstructor
public class MetricsSql {

    private final NamedParameterJdbcTemplate jdbc;

    static final String LAST_EVENT = """
            SELECT DISTINCT ON (e.subscription_id) e.subscription_id, e.customer_id, e.product_id, e.mrr_after, e.to_status
            FROM subscription_events e WHERE e.effective_on <= :d %s
            ORDER BY e.subscription_id, e.effective_on DESC, e.id DESC""";

    private static String scope(Long productId, Long planId) {
        String s = "";
        if (productId != null) {
            s += " AND e.product_id = :pid";
        }
        if (planId != null) {
            s += " AND e.subscription_id IN (SELECT id FROM subscriptions WHERE plan_id = :plan)";
        }
        return s;
    }

    public BigDecimal mrrAt(LocalDate date, Long productId, Long planId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(mrr_after), 0) FROM (" + LAST_EVENT.formatted(scope(productId, planId)) + ") x",
                new MapSqlParameterSource("d", date).addValue("pid", productId).addValue("plan", planId), BigDecimal.class);
    }

    /** MRR por cliente a una fecha (solo clientes con MRR &gt; 0). */
    public Map<Long, BigDecimal> customerMrrAt(LocalDate date, Long productId) {
        Map<Long, BigDecimal> out = new HashMap<>();
        jdbc.query("SELECT customer_id, SUM(mrr_after) FROM (" + LAST_EVENT.formatted(scope(productId, null)) + ") x GROUP BY customer_id HAVING SUM(mrr_after) > 0",
                new MapSqlParameterSource("d", date).addValue("pid", productId), rs -> {
                    out.put(rs.getLong(1), rs.getBigDecimal(2));
                });
        return out;
    }

    /** Suscripciones en estado recurrente a una fecha. */
    public long activeSubscriptionsAt(LocalDate date, Long productId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM (" + LAST_EVENT.formatted(scope(productId, null))
                + ") x WHERE to_status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')", new MapSqlParameterSource("d", date).addValue("pid", productId), Long.class);
        return n == null ? 0 : n;
    }

    /** Suma de movimientos de MRR (después − antes) por tipo, entre dos fechas inclusive. */
    public Map<String, BigDecimal> movements(LocalDate from, LocalDate to, Long productId) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (String m : List.of("NEW", "EXPANSION", "CONTRACTION", "CHURN", "REACTIVATION")) {
            out.put(m, BigDecimal.ZERO);
        }
        jdbc.query("SELECT movement, SUM(mrr_after - mrr_before) FROM subscription_events e WHERE effective_on BETWEEN :f AND :t AND movement <> 'NONE'"
                        + (productId != null ? " AND e.product_id = :pid" : "") + " GROUP BY movement",
                new MapSqlParameterSource("f", from).addValue("t", to).addValue("pid", productId), rs -> {
                    out.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return out;
    }

    /** Cobrado neto: pagos confirmados/conciliados por fecha de recepción − devoluciones por fecha de devolución. */
    public BigDecimal collected(LocalDate from, LocalDate to, Long productId) {
        MapSqlParameterSource p = new MapSqlParameterSource("f", from).addValue("t", to).addValue("pid", productId);
        if (productId != null) {
            return jdbc.queryForObject("""
                    SELECT COALESCE(SUM(a.amount), 0) FROM payment_allocations a JOIN payments py ON py.id = a.payment_id
                    JOIN charges ch ON ch.id = a.charge_id WHERE a.reversed_at IS NULL AND py.status IN ('CONFIRMED', 'RECONCILED')
                      AND ch.product_id = :pid AND py.received_on BETWEEN :f AND :t""", p, BigDecimal.class);
        }
        return jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') AND received_on BETWEEN :f AND :t)
                     - (SELECT COALESCE(SUM(r.amount), 0) FROM payment_refunds r WHERE r.refunded_on BETWEEN :f AND :t)""", p, BigDecimal.class);
    }

    public BigDecimal billed(LocalDate from, LocalDate to, Long productId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM charges WHERE status <> 'VOID' AND issue_date BETWEEN :f AND :t"
                        + (productId != null ? " AND product_id = :pid" : ""),
                new MapSqlParameterSource("f", from).addValue("t", to).addValue("pid", productId), BigDecimal.class);
    }

    public static BigDecimal variation(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous.abs(), 1, java.math.RoundingMode.HALF_UP);
    }

    public static Map<String, Object> point(String label, Object... values) {
        return Map.of("label", label, "values", List.of(values));
    }
}

