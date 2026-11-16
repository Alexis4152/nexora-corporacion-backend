package com.nexora.corp.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salud comercial del cliente: regla EXPLICABLE (no un modelo predictivo; no hay datos suficientes para uno). Parte de
 * 100 y resta o suma puntos por factores observables; cada factor se muestra con su peso para que cualquiera entienda
 * por qué el cliente está en riesgo. Ver docs/06-INDICADORES-SAAS.md §Salud.
 */
@Service
@RequiredArgsConstructor
public class HealthService {

    public record Factor(String key, String label, int points, String detail) {
    }

    public record Health(Long customerId, int score, String level, List<Factor> factors) {
    }

    private static final String FACTS = """
            SELECT c.id, c.customer_since, o.days_overdue, o.relationship,
                   (SELECT COUNT(*) FROM charges ch WHERE ch.customer_id = c.id AND ch.status <> 'VOID' AND ch.due_date > nx_today() - 365
                      AND ((ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date < nx_today())
                        OR (ch.status = 'PAID' AND (SELECT MAX(a.created_at)::date FROM payment_allocations a WHERE a.charge_id = ch.id AND a.reversed_at IS NULL) > ch.due_date))) AS late_charges,
                   (SELECT COUNT(*) FROM support_tickets t WHERE t.customer_id = c.id AND t.status NOT IN ('RESOLVED', 'CLOSED')) AS open_tickets,
                   (SELECT COUNT(*) FROM support_tickets t WHERE t.customer_id = c.id AND t.status NOT IN ('RESOLVED', 'CLOSED') AND t.priority IN ('HIGH', 'URGENT')) AS urgent_tickets,
                   (SELECT COUNT(*) FROM support_tickets t WHERE t.customer_id = c.id AND t.created_at > now() - interval '90 days') AS tickets90,
                   (SELECT COUNT(*) FROM subscriptions s WHERE s.customer_id = c.id AND s.status = 'CANCELLED') AS cancelled_subs,
                   (SELECT COUNT(*) FROM subscriptions s WHERE s.customer_id = c.id AND s.status = 'SUSPENDED') AS suspended_subs,
                   (SELECT COUNT(*) FROM subscriptions s WHERE s.customer_id = c.id AND s.status = 'CANCEL_SCHEDULED') AS scheduled_cancel
            FROM customers c JOIN customer_overview o ON o.customer_id = c.id""";

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Health of(Long customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(FACTS + " WHERE c.id = :id", Map.of("id", customerId));
        return rows.isEmpty() ? new Health(customerId, 0, "UNKNOWN", List.of()) : compute(rows.get(0));
    }

    /** Clientes con relación vigente ordenados por riesgo. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> atRisk(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(FACTS + " WHERE o.relationship IN ('ACTIVE', 'SUSPENDED', 'TRIAL')", Map.of())) {
            Health h = compute(r);
            if (h.score() < 75) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("customerId", h.customerId());
                m.put("score", h.score());
                m.put("level", h.level());
                m.put("factors", h.factors().stream().filter(f -> f.points() < 0).toList());
                out.add(m);
            }
        }
        out.sort((a, b) -> Integer.compare((Integer) a.get("score"), (Integer) b.get("score")));
        List<Map<String, Object>> top = out.subList(0, Math.min(limit, out.size()));
        if (!top.isEmpty()) {
            Map<Long, Map<String, Object>> names = new LinkedHashMap<>();
            jdbc.query("SELECT id, code, trade_name FROM customers WHERE id IN (:ids)", Map.of("ids", top.stream().map(m -> m.get("customerId")).toList()),
                    rs -> {
                        names.put(rs.getLong(1), Map.of("code", rs.getString(2), "name", rs.getString(3)));
                    });
            top.forEach(m -> m.putAll(names.getOrDefault((Long) m.get("customerId"), Map.of())));
        }
        return top;
    }

    static Health compute(Map<String, Object> r) {
        List<Factor> f = new ArrayList<>();
        int days = n(r.get("days_overdue"));
        if (days > 30) {
            f.add(new Factor("overdue", "Adeudo vencido", -35, days + " días de atraso"));
        } else if (days > 15) {
            f.add(new Factor("overdue", "Adeudo vencido", -25, days + " días de atraso"));
        } else if (days > 0) {
            f.add(new Factor("overdue", "Adeudo vencido", -15, days + " días de atraso"));
        }
        int late = n(r.get("late_charges"));
        if (late >= 3) {
            f.add(new Factor("late", "Atrasos recurrentes", -15, late + " cargos pagados tarde o vencidos en 12 meses"));
        } else if (late > 0) {
            f.add(new Factor("late", "Atrasos ocasionales", -5, late + " cargo(s) con atraso en 12 meses"));
        }
        int open = n(r.get("open_tickets"));
        if (open >= 3) {
            f.add(new Factor("tickets", "Tickets abiertos", -15, open + " tickets sin resolver"));
        } else if (open > 0) {
            f.add(new Factor("tickets", "Tickets abiertos", -5, open + " ticket(s) sin resolver"));
        }
        if (n(r.get("urgent_tickets")) > 0) {
            f.add(new Factor("urgent", "Incidencias urgentes", -10, n(r.get("urgent_tickets")) + " de prioridad alta o urgente"));
        }
        if (n(r.get("tickets90")) >= 5) {
            f.add(new Factor("frequent", "Incidencias frecuentes", -10, n(r.get("tickets90")) + " tickets en 90 días"));
        }
        if (n(r.get("cancelled_subs")) > 0) {
            f.add(new Factor("cancelled", "Cancelaciones anteriores", -10, n(r.get("cancelled_subs")) + " suscripción(es) cancelada(s)"));
        }
        if (n(r.get("suspended_subs")) > 0) {
            f.add(new Factor("suspended", "Servicio suspendido", -20, n(r.get("suspended_subs")) + " suscripción(es) suspendida(s)"));
        }
        if (n(r.get("scheduled_cancel")) > 0) {
            f.add(new Factor("scheduled", "Cancelación programada", -20, "Avisó que cancelará al final del periodo"));
        }
        java.time.LocalDate since = ((java.sql.Date) r.get("customer_since")).toLocalDate();
        long months = java.time.temporal.ChronoUnit.MONTHS.between(since, java.time.LocalDate.now());
        if (months < 3) {
            f.add(new Factor("tenure", "Cliente reciente", -10, "Menos de 3 meses como cliente"));
        } else if (months >= 12) {
            f.add(new Factor("tenure", "Antigüedad", 5, months + " meses como cliente"));
        }
        int score = Math.max(0, Math.min(100, 100 + f.stream().mapToInt(Factor::points).sum()));
        String level = score >= 75 ? "HEALTHY" : score >= 50 ? "ATTENTION" : "RISK";
        return new Health(((Number) r.get("id")).longValue(), score, level, f);
    }

    private static int n(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}

