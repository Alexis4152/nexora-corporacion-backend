package com.nexora.corp.analytics;

import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard ejecutivo y por producto. Reglas: un cliente con POS y HOTEL cuenta UNA vez en los indicadores
 * corporativos y dos veces en los de suscripciones por producto; cobrado, facturado, por cobrar y MRR nunca se
 * suman entre sí. La información financiera solo se entrega con {@code reports.financial_read}.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final NamedParameterJdbcTemplate jdbc;
    private final MetricsSql metrics;
    private final SettingsService settings;

    @Transactional(readOnly = true)
    public Map<String, Object> executive(LocalDate from, LocalDate to, Long productId, Long planId) {
        LocalDate today = settings.today();
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        long days = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1;
        LocalDate pf = f.minusDays(days);
        LocalDate pt = f.minusDays(1);
        boolean money = SecurityUtils.has("reports.financial_read");
        MapSqlParameterSource p = params(f, t, productId, planId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", Map.of("from", f, "to", t, "previousFrom", pf, "previousTo", pt, "today", today));
        out.put("currency", settings.currency());
        out.put("financial", money);

        String subScope = (productId != null ? " AND s.product_id = :pid" : "") + (planId != null ? " AND s.plan_id = :plan" : "");
        Map<String, Object> customers = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE o.relationship <> 'ARCHIVED') AS total,
                       COUNT(*) FILTER (WHERE o.relationship = 'ACTIVE') AS active,
                       COUNT(*) FILTER (WHERE o.relationship = 'TRIAL') AS trial,
                       COUNT(*) FILTER (WHERE o.relationship = 'SUSPENDED') AS suspended,
                       COUNT(*) FILTER (WHERE o.relationship = 'CANCELLED') AS cancelled,
                       COUNT(*) FILTER (WHERE o.overdue > 0) AS with_overdue
                FROM customer_overview o WHERE (:pid::bigint IS NULL AND :plan::bigint IS NULL)
                   OR EXISTS (SELECT 1 FROM subscriptions s WHERE s.customer_id = o.customer_id""" + subScope + ")", p);
        out.put("customers", customers);

        String interest = productId != null ? " AND EXISTS (SELECT 1 FROM prospect_interests i WHERE i.prospect_id = p.id AND i.product_id = :pid)" : "";
        Map<String, Object> prospects = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE stage NOT IN ('WON', 'LOST', 'DISCARDED')) AS active,
                       COUNT(*) FILTER (WHERE created_at::date BETWEEN :f AND :t) AS new_in_period,
                       COUNT(*) FILTER (WHERE created_at::date BETWEEN :pf AND :pt) AS new_prev,
                       COUNT(*) FILTER (WHERE converted_at::date BETWEEN :f AND :t) AS converted_in_period,
                       COUNT(*) FILTER (WHERE stage = 'LOST' AND stage_changed_at::date BETWEEN :f AND :t) AS lost_in_period,
                       COALESCE(SUM(estimated_value * probability / 100.0) FILTER (WHERE stage NOT IN ('WON', 'LOST', 'DISCARDED')), 0) AS weighted_pipeline
                FROM prospects p WHERE 1 = 1""" + interest, p);
        long won = ((Number) prospects.get("converted_in_period")).longValue();
        long lost = ((Number) prospects.get("lost_in_period")).longValue();
        prospects.put("conversion_rate", won + lost == 0 ? null : BigDecimal.valueOf(won * 100.0 / (won + lost)).setScale(1, RoundingMode.HALF_UP));
        if (!money) {
            prospects.remove("weighted_pipeline");
        }
        out.put("prospects", prospects);

        String evScope = (productId != null ? " AND e.product_id = :pid" : "")
                + (planId != null ? " AND e.subscription_id IN (SELECT id FROM subscriptions WHERE plan_id = :plan)" : "");
        out.put("subscriptions", jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE type IN ('ACTIVATED', 'TRIAL_CONVERTED') AND effective_on BETWEEN :f AND :t) AS new_in_period,
                       COUNT(*) FILTER (WHERE type IN ('ACTIVATED', 'TRIAL_CONVERTED') AND effective_on BETWEEN :pf AND :pt) AS new_prev,
                       COUNT(*) FILTER (WHERE type = 'CANCELLED' AND effective_on BETWEEN :f AND :t) AS cancelled_in_period,
                       COUNT(*) FILTER (WHERE type = 'CANCELLED' AND effective_on BETWEEN :pf AND :pt) AS cancelled_prev,
                       COUNT(*) FILTER (WHERE type = 'CREATED' AND to_status = 'TRIAL' AND effective_on BETWEEN :f AND :t) AS trials_started
                FROM subscription_events e WHERE 1 = 1""" + evScope, p));

        if (money) {
            out.put("money", money(today, f, t, pf, pt, productId, planId));
        }
        out.put("charts", charts(today, f, t, productId, planId, money));
        return out;
    }

    private Map<String, Object> money(LocalDate today, LocalDate f, LocalDate t, LocalDate pf, LocalDate pt, Long productId, Long planId) {
        Map<String, Object> m = new LinkedHashMap<>();
        BigDecimal mrr = metrics.mrrAt(today, productId, planId);
        BigDecimal mrrPrevEnd = metrics.mrrAt(f.minusDays(1), productId, planId);
        BigDecimal collectedPeriod = metrics.collected(f, t, productId);
        BigDecimal collectedPrev = metrics.collected(pf, pt, productId);
        m.put("collectedToday", metrics.collected(today, today, productId));
        m.put("collectedMonth", metrics.collected(today.withDayOfMonth(1), today, productId));
        m.put("collectedYear", metrics.collected(today.withDayOfYear(1), today, productId));
        m.put("collectedPeriod", collectedPeriod);
        m.put("collectedPrevious", collectedPrev);
        m.put("collectedVariation", MetricsSql.variation(collectedPeriod, collectedPrev));
        BigDecimal billed = metrics.billed(f, t, productId);
        m.put("billedPeriod", billed);
        m.put("billedVariation", MetricsSql.variation(billed, metrics.billed(pf, pt, productId)));
        m.put("mrr", mrr.setScale(2, RoundingMode.HALF_UP));
        m.put("arr", mrr.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP));
        m.put("mrrGrowth", MetricsSql.variation(mrr, mrrPrevEnd));
        String chScope = productId != null ? " AND product_id = :pid" : "";
        MapSqlParameterSource p = params(f, t, productId, planId).addValue("soon", settings.integer(Setting.DUE_SOON_DAYS));
        Map<String, Object> ar = jdbc.queryForMap("""
                SELECT COALESCE(SUM(balance), 0) AS receivable,
                       COALESCE(SUM(balance) FILTER (WHERE due_date < nx_today()), 0) AS overdue,
                       COALESCE(SUM(balance) FILTER (WHERE due_date BETWEEN nx_today() AND nx_today() + :soon), 0) AS due_soon,
                       COUNT(*) FILTER (WHERE due_date BETWEEN nx_today() AND nx_today() + :soon) AS due_soon_count
                FROM charge_balances WHERE status IN ('OPEN', 'PARTIAL')""" + chScope, p);
        m.putAll(ar);
        m.put("capturedPending", jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'CAPTURED'", Map.of(), BigDecimal.class));
        Map<Long, BigDecimal> start = metrics.customerMrrAt(f.minusDays(1), productId);
        Map<Long, BigDecimal> end = metrics.customerMrrAt(t.isAfter(today) ? today : t, productId);
        long churned = start.keySet().stream().filter(id -> !end.containsKey(id)).count();
        m.put("customerChurnRate", start.isEmpty() ? null : BigDecimal.valueOf(churned * 100.0 / start.size()).setScale(1, RoundingMode.HALF_UP));
        m.put("payingCustomers", end.size());
        m.put("arpa", end.isEmpty() ? null : mrr.divide(BigDecimal.valueOf(end.size()), 2, RoundingMode.HALF_UP));
        return m;
    }

    private Map<String, Object> charts(LocalDate today, LocalDate f, LocalDate t, Long productId, Long planId, boolean money) {
        Map<String, Object> c = new LinkedHashMap<>();
        MapSqlParameterSource p = params(f, t, productId, planId);
        YearMonth last = YearMonth.from(today);
        List<YearMonth> months = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            months.add(last.minusMonths(i));
        }
        if (money) {
            List<Map<String, Object>> revenue = new ArrayList<>();
            List<Map<String, Object>> mrr = new ArrayList<>();
            List<Map<String, Object>> vsPending = new ArrayList<>();
            for (YearMonth ym : months) {
                LocalDate a = ym.atDay(1);
                LocalDate b = ym.equals(last) ? today : ym.atEndOfMonth();
                revenue.add(MetricsSql.point(ym.toString(), metrics.collected(a, b, productId), metrics.billed(a, b, productId)));
                mrr.add(MetricsSql.point(ym.toString(), metrics.mrrAt(b, productId, planId).setScale(2, RoundingMode.HALF_UP)));
            }
            for (YearMonth ym : months.subList(6, 12)) {
                Map<String, Object> r = jdbc.queryForMap("""
                        SELECT COALESCE(SUM(paid_amount), 0) AS paid, COALESCE(SUM(balance) FILTER (WHERE status IN ('OPEN', 'PARTIAL')), 0) AS pending
                        FROM charge_balances WHERE status <> 'VOID' AND due_date BETWEEN :a AND :b""" + (productId != null ? " AND product_id = :pid" : ""),
                        new MapSqlParameterSource("a", ym.atDay(1)).addValue("b", ym.atEndOfMonth()).addValue("pid", productId));
                vsPending.add(MetricsSql.point(ym.toString(), r.get("paid"), r.get("pending")));
            }
            c.put("revenue12m", Map.of("series", List.of("Cobrado", "Facturado"), "points", revenue));
            c.put("mrrEvolution", Map.of("series", List.of("MRR"), "points", mrr));
            c.put("collectedVsPending", Map.of("series", List.of("Cobrado", "Pendiente"), "points", vsPending));
            c.put("revenueByProduct", jdbc.queryForList("""
                    SELECT pr.name AS label, pr.color, COALESCE(SUM(a.amount), 0) AS value FROM products pr
                    LEFT JOIN charges ch ON ch.product_id = pr.id LEFT JOIN payment_allocations a ON a.charge_id = ch.id AND a.reversed_at IS NULL
                      AND a.payment_id IN (SELECT id FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') AND received_on BETWEEN :f AND :t)
                    WHERE pr.active GROUP BY pr.id, pr.name, pr.color, pr.sort_order HAVING COALESCE(SUM(a.amount), 0) > 0 ORDER BY 3 DESC""", p));
            c.put("topCustomers", jdbc.queryForList("""
                    SELECT c.id, c.trade_name AS label, SUM(py.amount) AS value FROM payments py JOIN customers c ON c.id = py.customer_id
                    WHERE py.status IN ('CONFIRMED', 'RECONCILED') GROUP BY c.id, c.trade_name ORDER BY 3 DESC LIMIT 8""", p));
            c.put("profitableProducts", jdbc.queryForList("""
                    SELECT pr.name AS label, pr.color,
                           COALESCE((SELECT SUM(a.amount) FROM payment_allocations a JOIN payments py ON py.id = a.payment_id JOIN charges ch ON ch.id = a.charge_id
                                     WHERE a.reversed_at IS NULL AND py.status IN ('CONFIRMED', 'RECONCILED') AND ch.product_id = pr.id
                                       AND py.received_on BETWEEN :f AND :t), 0) AS income,
                           COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.product_id = pr.id AND e.voided_at IS NULL
                                     AND e.expense_date BETWEEN :f AND :t), 0) AS expenses
                    FROM products pr WHERE pr.active ORDER BY pr.sort_order""", p));
        }
        String scope = productId != null ? " AND s.product_id = :pid" : "";
        c.put("activeByProduct", jdbc.queryForList("""
                SELECT pr.name AS label, pr.color, COUNT(s.id) AS value FROM products pr LEFT JOIN subscriptions s ON s.product_id = pr.id
                  AND s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED') WHERE pr.active GROUP BY pr.id, pr.name, pr.color, pr.sort_order ORDER BY pr.sort_order""", p));
        c.put("prospectsByProduct", jdbc.queryForList("""
                SELECT pr.name AS label, pr.color, COUNT(pp.id) AS value FROM products pr LEFT JOIN prospect_interests i ON i.product_id = pr.id
                LEFT JOIN prospects pp ON pp.id = i.prospect_id AND pp.stage NOT IN ('WON', 'LOST', 'DISCARDED')
                WHERE pr.active GROUP BY pr.id, pr.name, pr.color, pr.sort_order ORDER BY pr.sort_order""", p));
        c.put("statusDistribution", jdbc.queryForList("SELECT s.status AS label, COUNT(*) AS value FROM subscriptions s WHERE 1 = 1" + scope
                + (planId != null ? " AND s.plan_id = :plan" : "") + " GROUP BY s.status ORDER BY 2 DESC", p));
        c.put("funnel", jdbc.queryForList("""
                SELECT stage AS label, COUNT(*) AS value FROM prospects p WHERE stage NOT IN ('WON', 'LOST', 'DISCARDED')
                   OR stage_changed_at::date BETWEEN :f AND :t""" + (productId != null
                ? " AND EXISTS (SELECT 1 FROM prospect_interests i WHERE i.prospect_id = p.id AND i.product_id = :pid)" : "") + " GROUP BY stage", p));
        List<Map<String, Object>> flow = new ArrayList<>();
        List<Map<String, Object>> history = new ArrayList<>();
        for (YearMonth ym : months) {
            LocalDate b = ym.equals(last) ? today : ym.atEndOfMonth();
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE type IN ('ACTIVATED', 'TRIAL_CONVERTED', 'REACTIVATED')) AS gained,
                           COUNT(*) FILTER (WHERE type IN ('CANCELLED', 'EXPIRED')) AS lost
                    FROM subscription_events e WHERE effective_on BETWEEN :a AND :b""" + (productId != null ? " AND e.product_id = :pid" : ""),
                    new MapSqlParameterSource("a", ym.atDay(1)).addValue("b", b).addValue("pid", productId));
            flow.add(MetricsSql.point(ym.toString(), r.get("gained"), r.get("lost")));
            history.add(MetricsSql.point(ym.toString(), metrics.activeSubscriptionsAt(b, productId)));
        }
        c.put("newVsCancelled", Map.of("series", List.of("Altas", "Bajas"), "points", flow));
        c.put("subscriptionsHistory", Map.of("series", List.of("Suscripciones activas"), "points", history));
        return c;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> product(Long productId, LocalDate from, LocalDate to) {
        Map<String, Object> out = new LinkedHashMap<>(executive(from, to, productId, null));
        LocalDate today = settings.today();
        MapSqlParameterSource p = new MapSqlParameterSource("pid", productId).addValue("today", today).addValue("lim", today.plusDays(30));
        out.put("product", jdbc.queryForMap("SELECT id, code, name, tagline, color, icon, category, system_url, marketing_url FROM products WHERE id = :pid", p));
        out.put("subscriptionCounts", jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')) AS active,
                       COUNT(*) FILTER (WHERE status = 'TRIAL') AS trial, COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled, COUNT(*) FILTER (WHERE status = 'GRACE') AS grace,
                       COUNT(*) FILTER (WHERE status = 'PENDING_ACTIVATION') AS pending
                FROM subscriptions WHERE product_id = :pid""", p));
        out.put("topPlans", jdbc.queryForList("""
                SELECT pl.name AS label, COUNT(s.id) AS value FROM plans pl LEFT JOIN subscriptions s ON s.plan_id = pl.id
                  AND s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED', 'TRIAL') WHERE pl.product_id = :pid GROUP BY pl.id, pl.name ORDER BY 2 DESC""", p));
        out.put("upcoming", jdbc.queryForList("""
                SELECT s.id, s.code, c.trade_name AS customer, s.status, s.current_period_end AS date, 'RENEWAL' AS kind, s.auto_renew
                FROM subscriptions s JOIN customers c ON c.id = s.customer_id
                WHERE s.product_id = :pid AND s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED') AND s.current_period_end BETWEEN :today AND :lim
                UNION ALL
                SELECT s.id, s.code, c.trade_name, s.status, s.trial_ends_on, 'TRIAL_END', s.auto_renew FROM subscriptions s
                JOIN customers c ON c.id = s.customer_id WHERE s.product_id = :pid AND s.status = 'TRIAL' AND s.trial_ends_on BETWEEN :today AND :lim
                ORDER BY 5 LIMIT 15""", p));
        if (SecurityUtils.has("collections.read") || SecurityUtils.has("reports.financial_read")) {
            out.put("arrears", jdbc.queryForList("""
                    SELECT s.id, s.code, c.id AS customer_id, c.trade_name AS customer, sc.overdue_amount, sc.days_overdue, sc.collection_status
                    FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN subscription_collection sc ON sc.subscription_id = s.id
                    WHERE s.product_id = :pid AND sc.overdue_amount > 0 ORDER BY sc.days_overdue DESC LIMIT 10""", p));
        }
        out.put("commercial", jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE p.stage NOT IN ('WON', 'LOST', 'DISCARDED')) AS open,
                       COUNT(*) FILTER (WHERE p.stage = 'WON' AND p.stage_changed_at > now() - interval '90 days') AS won90,
                       COUNT(*) FILTER (WHERE p.stage = 'LOST' AND p.stage_changed_at > now() - interval '90 days') AS lost90
                FROM prospects p JOIN prospect_interests i ON i.prospect_id = p.id WHERE i.product_id = :pid""", p));
        return out;
    }

    private static MapSqlParameterSource params(LocalDate f, LocalDate t, Long productId, Long planId) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1;
        return new MapSqlParameterSource("f", f).addValue("t", t).addValue("pf", f.minusDays(days)).addValue("pt", f.minusDays(1))
                .addValue("pid", productId).addValue("plan", planId);
    }
}

