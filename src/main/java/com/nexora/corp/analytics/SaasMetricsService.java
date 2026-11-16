package com.nexora.corp.analytics;

import com.nexora.corp.settings.SettingsService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indicadores SaaS con fórmulas documentadas (docs/06-INDICADORES-SAAS.md). Todos se calculan sobre MRR sin IVA,
 * normalizado a mes, con descuentos vigentes; las pruebas gratuitas y las suscripciones suspendidas no aportan MRR.
 */
@Service
@RequiredArgsConstructor
public class SaasMetricsService {

    private final MetricsSql metrics;
    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    public static final List<Map<String, String>> DEFINITIONS = List.of(
            def("MRR", "Ingreso recurrente mensual", "Σ precio pactado sin IVA − descuento vigente ÷ meses del periodo, de suscripciones Activas, En gracia y con Cancelación programada."),
            def("ARR", "Ingreso recurrente anualizado", "MRR × 12. Es una proyección del contrato vigente, no dinero cobrado."),
            def("NEW", "New MRR", "MRR de clientes que pasan de 0 a >0 por primera vez."),
            def("EXPANSION", "Expansion MRR", "Aumento de MRR de clientes que ya pagaban (otro producto, plan mayor, fin de descuento)."),
            def("CONTRACTION", "Contraction MRR", "Disminución de MRR de clientes que siguen pagando (plan menor, baja de un producto de varios)."),
            def("CHURN", "Churn MRR", "MRR perdido de clientes que llegan a 0 (cancelación, expiración o suspensión de su última suscripción)."),
            def("REACTIVATION", "Reactivation MRR", "MRR de clientes que habían llegado a 0 y vuelven a pagar."),
            def("CUSTOMER_CHURN", "Churn de clientes", "Clientes con MRR al inicio del mes que terminan el mes en 0 ÷ clientes con MRR al inicio."),
            def("REVENUE_CHURN", "Churn de ingresos (bruto)", "(|Churn MRR| + |Contraction MRR|) ÷ MRR al inicio del mes."),
            def("NRR", "Net Revenue Retention", "(MRR inicial + Expansion − |Contraction| − |Churn|) ÷ MRR inicial. Excluye New y Reactivation."),
            def("GRR", "Gross Revenue Retention", "(MRR inicial − |Contraction| − |Churn|) ÷ MRR inicial. Nunca supera 100 %."),
            def("ARPA", "Ingreso promedio por cliente", "MRR al cierre ÷ clientes con MRR > 0 al cierre."),
            def("LTV", "Customer Lifetime Value", "ARPA ÷ churn mensual promedio de clientes (últimos 6 meses). Supuestos: ingreso (sin margen bruto), churn constante. No se calcula si el churn es 0."),
            def("CAC", "Customer Acquisition Cost", "Gastos de categorías marcadas como costo de adquisición (últimos 3 meses) ÷ clientes nuevos en ese lapso. Solo si existen esos gastos."),
            def("LTV_CAC", "Relación LTV/CAC", "Solo se muestra si LTV y CAC son calculables."));

    private static Map<String, String> def(String key, String name, String formula) {
        return Map.of("key", key, "name", name, "formula", formula);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> monthly(int months, Long productId) {
        LocalDate today = settings.today();
        YearMonth current = YearMonth.from(today);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            rows.add(month(current.minusMonths(i), today, productId));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", rows);
        out.put("definitions", DEFINITIONS);
        out.put("currency", settings.currency());

        // LTV con churn promedio de los últimos 6 meses cerrados (o los disponibles).
        List<Map<String, Object>> closed = rows.subList(Math.max(0, rows.size() - 7), Math.max(0, rows.size() - 1));
        double avgChurn = closed.stream().map(r -> (BigDecimal) r.get("customerChurnRate")).filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        Map<String, Object> last = rows.get(rows.size() - 1);
        BigDecimal arpa = (BigDecimal) last.get("arpa");
        BigDecimal ltv = arpa != null && avgChurn > 0 ? arpa.divide(BigDecimal.valueOf(avgChurn / 100.0), 2, RoundingMode.HALF_UP) : null;
        LocalDate cacFrom = current.minusMonths(2).atDay(1);
        BigDecimal acquisition = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount), 0) FROM expenses e JOIN expense_categories c ON c.id = e.category_id
                WHERE c.acquisition_cost AND e.voided_at IS NULL AND e.expense_date BETWEEN :f AND :t"""
                + (productId != null ? " AND (e.product_id = :pid OR e.product_id IS NULL)" : ""),
                new MapSqlParameterSource("f", cacFrom).addValue("t", today).addValue("pid", productId), BigDecimal.class);
        Long newCustomers = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT customer_id) FROM subscription_events e WHERE movement = 'NEW' AND effective_on BETWEEN :f AND :t"""
                + (productId != null ? " AND e.product_id = :pid" : ""), new MapSqlParameterSource("f", cacFrom).addValue("t", today).addValue("pid", productId), Long.class);
        BigDecimal cac = acquisition.signum() > 0 && newCustomers != null && newCustomers > 0
                ? acquisition.divide(BigDecimal.valueOf(newCustomers), 2, RoundingMode.HALF_UP) : null;
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("avgMonthlyCustomerChurn", avgChurn == 0 ? null : BigDecimal.valueOf(avgChurn).setScale(2, RoundingMode.HALF_UP));
        unit.put("ltv", ltv);
        unit.put("ltvReason", ltv == null ? (arpa == null ? "Sin clientes con MRR" : "Churn de 0 % en el periodo: el LTV no es calculable con esta fórmula") : null);
        unit.put("cac", cac);
        unit.put("cacReason", cac == null ? (acquisition.signum() == 0 ? "No hay gastos registrados en categorías de adquisición" : "Sin clientes nuevos en los últimos 3 meses") : null);
        unit.put("acquisitionSpend", acquisition);
        unit.put("newCustomers3m", newCustomers);
        unit.put("ltvToCac", ltv != null && cac != null && cac.signum() > 0 ? ltv.divide(cac, 2, RoundingMode.HALF_UP) : null);
        out.put("unitEconomics", unit);
        return out;
    }

    Map<String, Object> month(YearMonth ym, LocalDate today, Long productId) {
        LocalDate start = ym.atDay(1).minusDays(1);
        LocalDate end = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
        BigDecimal mrrStart = metrics.mrrAt(start, productId, null);
        BigDecimal mrrEnd = metrics.mrrAt(end, productId, null);
        Map<String, BigDecimal> mv = metrics.movements(ym.atDay(1), end, productId);
        Map<Long, BigDecimal> cs = metrics.customerMrrAt(start, productId);
        Map<Long, BigDecimal> ce = metrics.customerMrrAt(end, productId);
        Set<Long> startIds = cs.keySet();
        long churned = startIds.stream().filter(id -> !ce.containsKey(id)).count();
        BigDecimal churn = mv.get("CHURN").abs();
        BigDecimal contraction = mv.get("CONTRACTION").abs();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", ym.toString());
        r.put("mrrStart", scale(mrrStart));
        r.put("mrrEnd", scale(mrrEnd));
        r.put("arr", scale(mrrEnd.multiply(BigDecimal.valueOf(12))));
        r.put("newMrr", scale(mv.get("NEW")));
        r.put("expansionMrr", scale(mv.get("EXPANSION")));
        r.put("contractionMrr", scale(contraction.negate()));
        r.put("churnMrr", scale(churn.negate()));
        r.put("reactivationMrr", scale(mv.get("REACTIVATION")));
        r.put("netNewMrr", scale(mrrEnd.subtract(mrrStart)));
        r.put("customersStart", cs.size());
        r.put("customersEnd", ce.size());
        r.put("churnedCustomers", churned);
        r.put("customerChurnRate", cs.isEmpty() ? null : pct(BigDecimal.valueOf(churned), BigDecimal.valueOf(cs.size())));
        r.put("revenueChurnRate", mrrStart.signum() == 0 ? null : pct(churn.add(contraction), mrrStart));
        r.put("nrr", mrrStart.signum() == 0 ? null : pct(mrrStart.add(mv.get("EXPANSION")).subtract(contraction).subtract(churn), mrrStart));
        r.put("grr", mrrStart.signum() == 0 ? null : pct(mrrStart.subtract(contraction).subtract(churn), mrrStart));
        r.put("arpa", ce.isEmpty() ? null : mrrEnd.divide(BigDecimal.valueOf(ce.size()), 2, RoundingMode.HALF_UP));
        return r;
    }

    /** Cohortes por mes de alta (primer movimiento NEW del cliente): clientes retenidos e MRR por mes transcurrido. */
    @Transactional(readOnly = true)
    public Map<String, Object> cohorts(int months, Long productId) {
        LocalDate today = settings.today();
        YearMonth current = YearMonth.from(today);
        YearMonth first = current.minusMonths(months - 1L);
        Map<Long, YearMonth> cohortOf = new HashMap<>();
        jdbc.query("SELECT customer_id, MIN(effective_on) FROM subscription_events e WHERE movement = 'NEW'"
                + (productId != null ? " AND e.product_id = :pid" : "") + " GROUP BY customer_id", new MapSqlParameterSource("pid", productId), rs -> {
            YearMonth ym = YearMonth.from(rs.getDate(2).toLocalDate());
            if (!ym.isBefore(first)) {
                cohortOf.put(rs.getLong(1), ym);
            }
        });
        Map<YearMonth, Map<Long, BigDecimal>> snapshot = new LinkedHashMap<>();
        for (YearMonth ym = first; !ym.isAfter(current); ym = ym.plusMonths(1)) {
            snapshot.put(ym, metrics.customerMrrAt(ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth(), productId));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (YearMonth cohort = first; !cohort.isAfter(current); cohort = cohort.plusMonths(1)) {
            YearMonth c = cohort;
            List<Long> members = cohortOf.entrySet().stream().filter(e -> e.getValue().equals(c)).map(Map.Entry::getKey).toList();
            List<Map<String, Object>> cells = new ArrayList<>();
            BigDecimal initialMrr = null;
            for (YearMonth ym = cohort; !ym.isAfter(current); ym = ym.plusMonths(1)) {
                Map<Long, BigDecimal> snap = snapshot.get(ym);
                long retained = members.stream().filter(snap::containsKey).count();
                BigDecimal mrr = members.stream().map(id -> snap.getOrDefault(id, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (initialMrr == null) {
                    initialMrr = mrr;
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("offset", (int) java.time.temporal.ChronoUnit.MONTHS.between(cohort, ym));
                cell.put("retained", retained);
                cell.put("retention", members.isEmpty() ? null : pct(BigDecimal.valueOf(retained), BigDecimal.valueOf(members.size())));
                cell.put("mrr", scale(mrr));
                cell.put("revenueRetention", initialMrr.signum() == 0 ? null : pct(mrr, initialMrr));
                cells.add(cell);
            }
            rows.add(Map.of("cohort", cohort.toString(), "customers", members.size(), "cells", cells));
        }
        return Map.of("cohorts", rows, "definition",
                "Cohorte = mes del primer ingreso recurrente del cliente. Retención = clientes de la cohorte con MRR > 0 al cierre de cada mes. "
                        + "Retención de ingresos = MRR de la cohorte ÷ su MRR del primer mes (puede superar 100 % por expansión).");
    }

    private static BigDecimal pct(BigDecimal a, BigDecimal b) {
        return a.multiply(BigDecimal.valueOf(100)).divide(b, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

