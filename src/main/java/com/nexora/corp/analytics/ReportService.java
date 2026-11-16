package com.nexora.corp.analytics;

import com.nexora.corp.billing.CollectionsService;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.export.TableExporter.Column;
import com.nexora.corp.shared.export.TableExporter.Table;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de reportes. Cada reporte declara su BASE (qué dinero o conteo representa), sus columnas y filas; la
 * exportación usa exactamente las mismas filas, así nunca exporta más de lo que el usuario puede ver.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    public record ReportDef(String type, String title, String group, String permission, String basis) {
    }

    public record Report(String type, String title, String basis, LocalDate from, LocalDate to, String currency, List<Column> columns,
                         List<Map<String, Object>> rows, List<Map<String, Object>> totals, Map<String, Object> chart, List<String> notes) {
    }

    private static final String FIN = "reports.financial_read";
    private static final String COM = "reports.commercial_read";
    private static final String COLLECTED_BASIS = "Cobrado = pagos confirmados o conciliados por fecha de recepción, menos devoluciones por fecha de devolución. Los pagos capturados sin confirmar no cuentan.";

    public static final List<ReportDef> CATALOG = List.of(
            new ReportDef("income-daily", "Ingresos diarios", "Ingresos", FIN, COLLECTED_BASIS),
            new ReportDef("income-monthly", "Ingresos mensuales", "Ingresos", FIN, "Por mes: cobrado, facturado (cargos emitidos con IVA) y devengado (subtotal sin IVA prorrateado por los días de servicio de cada mes). No se suman entre sí."),
            new ReportDef("income-yearly", "Ingresos anuales", "Ingresos", FIN, COLLECTED_BASIS),
            new ReportDef("income-by-product", "Ingresos por producto", "Ingresos", FIN, "Importes de pagos confirmados APLICADOS a cargos de cada producto, por fecha de pago. El saldo a favor sin aplicar se muestra aparte."),
            new ReportDef("income-by-customer", "Ingresos por cliente", "Ingresos", FIN, COLLECTED_BASIS + " Incluye facturado y saldo pendiente del cliente."),
            new ReportDef("income-by-plan", "Ingresos por plan", "Ingresos", FIN, "Pagos confirmados aplicados a cargos de cada plan, por fecha de pago."),
            new ReportDef("payments", "Historial de cobros", "Cobranza", FIN, "Todos los pagos del periodo con su estado (capturado, confirmado, conciliado, rechazado, revertido)."),
            new ReportDef("aging", "Cartera vencida", "Cobranza", FIN, "Saldo vivo de cargos abiertos agrupado por días de atraso (intervalos configurables)."),
            new ReportDef("active-subscriptions", "Suscripciones activas", "Suscripciones", COM, "Suscripciones en estado Activa, En gracia o Cancelación programada al día de hoy."),
            new ReportDef("new-subscriptions", "Nuevas contrataciones", "Suscripciones", COM, "Activaciones y conversiones de prueba con fecha efectiva en el periodo."),
            new ReportDef("cancellations", "Cancelaciones", "Suscripciones", COM, "Cancelaciones efectivas en el periodo con su motivo."),
            new ReportDef("prospects-by-stage", "Prospectos por etapa", "Comercial", COM, "Prospectos actuales por etapa del embudo, con valor estimado y ponderado por probabilidad."),
            new ReportDef("conversion", "Conversión comercial", "Comercial", COM, "Por ejecutivo y fuente: prospectos creados, ganados y perdidos en el periodo. Conversión = ganados ÷ (ganados + perdidos)."),
            new ReportDef("lost-reasons", "Motivos de pérdida", "Comercial", COM, "Oportunidades perdidas en el periodo por motivo."),
            new ReportDef("team-performance", "Desempeño por ejecutivo", "Comercial", COM, "Por usuario: cotizaciones que creó y las aceptadas o rechazadas en el periodo (aceptación = aceptadas ÷ decididas), clientes nuevos y cartera asignada, contrataciones que vendió con inicio en el periodo y el MRR vigente de lo vendido."),
            new ReportDef("growth-by-product", "Crecimiento por producto", "Indicadores", FIN, "MRR al cierre de cada mes por producto."),
            new ReportDef("recurring-revenue", "Ingresos recurrentes", "Indicadores", FIN, "Movimientos de MRR por mes: New, Expansion, Contraction, Churn y Reactivation."),
            new ReportDef("period-comparison", "Comparación de periodos", "Indicadores", FIN, "Periodo seleccionado contra el inmediato anterior de la misma duración."),
            new ReportDef("profitability", "Ingresos vs. gastos", "Rentabilidad", FIN, "Cobrado frente a gastos registrados por mes. Es una utilidad ESTIMADA de flujo: no es utilidad contable y solo es tan completa como los gastos capturados."));

    private final NamedParameterJdbcTemplate jdbc;
    private final MetricsSql metrics;
    private final CollectionsService collections;
    private final SettingsService settings;

    public List<ReportDef> available() {
        return CATALOG.stream().filter(d -> SecurityUtils.has(d.permission())).toList();
    }

    public ReportDef def(String type) {
        ReportDef d = CATALOG.stream().filter(r -> r.type().equals(type)).findFirst()
                .orElseThrow(() -> new BusinessException("Reporte desconocido"));
        if (!SecurityUtils.has(d.permission())) {
            throw new AccessDeniedException("Sin permiso para el reporte");
        }
        return d;
    }

    @Transactional(readOnly = true)
    public Report run(String type, LocalDate from, LocalDate to, Long productId) {
        ReportDef d = def(type);
        LocalDate today = settings.today();
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) {
            throw new BusinessException("El periodo termina antes de empezar");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) > 3700) {
            throw new BusinessException("El periodo máximo es de 10 años");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("f", f).addValue("t", t).addValue("pid", productId);
        String prod = productId != null ? " AND ch.product_id = :pid" : "";
        List<Column> cols;
        List<Map<String, Object>> rows;
        List<String> notes = new ArrayList<>();
        Map<String, Object> chart = null;
        switch (type) {
            case "income-daily" -> {
                cols = List.of(c("day", "Día", "date"), c("collected", "Cobrado", "money"), c("refunds", "Devoluciones", "money"), c("net", "Neto", "money"), c("payments", "Pagos", "count"));
                rows = jdbc.queryForList("""
                        SELECT d::date AS day, COALESCE(py.v, 0) AS collected, COALESCE(r.v, 0) AS refunds, COALESCE(py.v, 0) - COALESCE(r.v, 0) AS net,
                               COALESCE(py.n, 0) AS payments
                        FROM generate_series(:f::date, :t::date, interval '1 day') d
                        LEFT JOIN (SELECT received_on, SUM(amount) v, COUNT(*) n FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') GROUP BY 1) py ON py.received_on = d::date
                        LEFT JOIN (SELECT refunded_on, SUM(amount) v FROM payment_refunds GROUP BY 1) r ON r.refunded_on = d::date
                        ORDER BY 1""", p);
                chart = chart(rows, "day", List.of("net"), List.of("Neto"), "money", "bar");
            }
            case "income-monthly" -> {
                cols = List.of(c("month", "Mes", "text"), c("collected", "Cobrado", "money"), c("billed", "Facturado", "money"), c("accrued", "Devengado (sin IVA)", "money"));
                rows = jdbc.queryForList("""
                        WITH months AS (SELECT generate_series(date_trunc('month', :f::date), date_trunc('month', :t::date), interval '1 month')::date AS m)
                        SELECT to_char(mo.m, 'YYYY-MM') AS month,
                          (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') AND date_trunc('month', received_on) = mo.m)
                          - (SELECT COALESCE(SUM(amount), 0) FROM payment_refunds WHERE date_trunc('month', refunded_on) = mo.m) AS collected,
                          (SELECT COALESCE(SUM(total), 0) FROM charges ch WHERE status <> 'VOID' AND date_trunc('month', issue_date) = mo.m""" + prod + """
                          ) AS billed,
                          (SELECT COALESCE(ROUND(SUM(ch.subtotal * (LEAST(ch.period_end, (mo.m + interval '1 month - 1 day')::date) - GREATEST(ch.period_start, mo.m) + 1)::numeric
                                  / (ch.period_end - ch.period_start + 1)), 2), 0)
                           FROM charges ch WHERE ch.status <> 'VOID' AND ch.period_start IS NOT NULL AND ch.period_start <= (mo.m + interval '1 month - 1 day')::date
                             AND ch.period_end >= mo.m""" + prod + """
                          ) AS accrued
                        FROM months mo ORDER BY mo.m""", p);
                chart = chart(rows, "month", List.of("collected", "billed", "accrued"), List.of("Cobrado", "Facturado", "Devengado"), "money", "bar");
                notes.add("Devengado reparte el subtotal (sin IVA) de cada cargo de periodo entre los días de servicio de cada mes.");
            }
            case "income-yearly" -> {
                cols = List.of(c("year", "Año", "text"), c("collected", "Cobrado", "money"), c("billed", "Facturado", "money"));
                rows = jdbc.queryForList("""
                        SELECT y::text AS year,
                          (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') AND EXTRACT(YEAR FROM received_on) = y)
                           - (SELECT COALESCE(SUM(amount), 0) FROM payment_refunds WHERE EXTRACT(YEAR FROM refunded_on) = y) AS collected,
                          (SELECT COALESCE(SUM(total), 0) FROM charges WHERE status <> 'VOID' AND EXTRACT(YEAR FROM issue_date) = y) AS billed
                        FROM generate_series(EXTRACT(YEAR FROM :f::date)::int, EXTRACT(YEAR FROM :t::date)::int) y ORDER BY y""", p);
                chart = chart(rows, "year", List.of("collected", "billed"), List.of("Cobrado", "Facturado"), "money", "bar");
            }
            case "income-by-product" -> {
                cols = List.of(c("product", "Producto", "text"), c("collected", "Cobrado aplicado", "money"), c("billed", "Facturado", "money"),
                        c("mrr", "MRR actual", "money"), c("active", "Suscripciones activas", "count"));
                rows = jdbc.queryForList("""
                        SELECT pr.name AS product,
                          (SELECT COALESCE(SUM(a.amount), 0) FROM payment_allocations a JOIN payments py ON py.id = a.payment_id JOIN charges ch ON ch.id = a.charge_id
                           WHERE a.reversed_at IS NULL AND py.status IN ('CONFIRMED', 'RECONCILED') AND ch.product_id = pr.id AND py.received_on BETWEEN :f AND :t) AS collected,
                          (SELECT COALESCE(SUM(total), 0) FROM charges ch WHERE ch.product_id = pr.id AND ch.status <> 'VOID' AND ch.issue_date BETWEEN :f AND :t) AS billed,
                          (SELECT COALESCE(ROUND(SUM(mrr), 2), 0) FROM subscriptions s WHERE s.product_id = pr.id) AS mrr,
                          (SELECT COUNT(*) FROM subscriptions s WHERE s.product_id = pr.id AND s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')) AS active
                        FROM products pr ORDER BY 2 DESC""", p);
                BigDecimal credit = jdbc.queryForObject("SELECT COALESCE(SUM(unapplied), 0) FROM payment_balances pb JOIN payments py ON py.id = pb.payment_id "
                        + "WHERE py.status IN ('CONFIRMED', 'RECONCILED') AND py.received_on BETWEEN :f AND :t", p, BigDecimal.class);
                notes.add("Saldo a favor sin aplicar de pagos del periodo: $" + credit);
                chart = chart(rows, "product", List.of("collected"), List.of("Cobrado aplicado"), "money", "hbar");
            }
            case "income-by-customer" -> {
                cols = List.of(c("code", "Cliente", "text"), c("customer", "Nombre", "text"), c("collected", "Cobrado", "money"),
                        c("billed", "Facturado", "money"), c("balance", "Saldo pendiente", "money"), c("mrr", "MRR", "money"));
                rows = jdbc.queryForList("""
                        SELECT c.code, c.trade_name AS customer,
                          (SELECT COALESCE(SUM(amount), 0) FROM payments py WHERE py.customer_id = c.id AND py.status IN ('CONFIRMED', 'RECONCILED')
                             AND py.received_on BETWEEN :f AND :t) AS collected,
                          (SELECT COALESCE(SUM(total), 0) FROM charges ch WHERE ch.customer_id = c.id AND ch.status <> 'VOID' AND ch.issue_date BETWEEN :f AND :t) AS billed,
                          o.balance, ROUND(o.mrr, 2) AS mrr
                        FROM customers c JOIN customer_overview o ON o.customer_id = c.id ORDER BY 3 DESC, 4 DESC LIMIT 2000""", p);
            }
            case "income-by-plan" -> {
                cols = List.of(c("product", "Producto", "text"), c("plan", "Plan", "text"), c("collected", "Cobrado aplicado", "money"), c("active", "Activas", "count"));
                rows = jdbc.queryForList("""
                        SELECT pr.name AS product, pl.name AS plan,
                          (SELECT COALESCE(SUM(a.amount), 0) FROM payment_allocations a JOIN payments py ON py.id = a.payment_id JOIN charges ch ON ch.id = a.charge_id
                           WHERE a.reversed_at IS NULL AND py.status IN ('CONFIRMED', 'RECONCILED') AND ch.plan_id = pl.id AND py.received_on BETWEEN :f AND :t) AS collected,
                          (SELECT COUNT(*) FROM subscriptions s WHERE s.plan_id = pl.id AND s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')) AS active
                        FROM plans pl JOIN products pr ON pr.id = pl.product_id WHERE (:pid::bigint IS NULL OR pr.id = :pid) ORDER BY 3 DESC""", p);
            }
            case "payments" -> {
                cols = List.of(c("folio", "Folio", "text"), c("received_on", "Fecha", "date"), c("customer", "Cliente", "text"), c("method", "Método", "text"),
                        c("reference", "Referencia", "text"), c("status", "Estado", "text"), c("amount", "Importe", "money"), c("unapplied", "Saldo a favor", "money"));
                rows = jdbc.queryForList("""
                        SELECT py.folio, py.received_on, c.trade_name AS customer, py.method, py.reference, py.status, py.amount, pb.unapplied
                        FROM payments py JOIN customers c ON c.id = py.customer_id JOIN payment_balances pb ON pb.payment_id = py.id
                        WHERE py.received_on BETWEEN :f AND :t ORDER BY py.received_on DESC, py.id DESC LIMIT 20000""", p);
            }
            case "aging" -> {
                cols = List.of(c("label", "Antigüedad", "text"), c("amount", "Saldo", "money"), c("charges", "Cargos", "count"), c("customers", "Clientes", "count"));
                rows = collections.aging().stream().map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", b.label());
                    m.put("amount", b.amount());
                    m.put("charges", b.charges());
                    m.put("customers", b.customers());
                    return m;
                }).toList();
                chart = chart(rows, "label", List.of("amount"), List.of("Saldo"), "money", "bar");
                notes.add("Corte al día de hoy; el periodo no aplica a este reporte.");
            }
            case "active-subscriptions" -> {
                cols = List.of(c("code", "Suscripción", "text"), c("customer", "Cliente", "text"), c("product", "Producto", "text"), c("plan", "Plan", "text"),
                        c("period", "Periodicidad", "text"), c("price", "Precio", "money"), c("mrr", "MRR", "money"), c("status", "Estado", "text"),
                        c("period_end", "Fin de periodo", "date"));
                rows = jdbc.queryForList("""
                        SELECT s.code, c.trade_name AS customer, pr.name AS product, pl.name AS plan, s.billing_period AS period, s.price,
                               ROUND(s.mrr, 2) AS mrr, s.status, s.current_period_end AS period_end
                        FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products pr ON pr.id = s.product_id JOIN plans pl ON pl.id = s.plan_id
                        WHERE s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED') AND (:pid::bigint IS NULL OR s.product_id = :pid)
                        ORDER BY pr.sort_order, c.trade_name""", p);
                if (!SecurityUtils.has(FIN)) {
                    rows.forEach(r -> r.remove("mrr"));
                    cols = cols.stream().filter(col -> !col.key().equals("mrr")).toList();
                }
            }
            case "new-subscriptions", "cancellations" -> {
                boolean isNew = type.equals("new-subscriptions");
                cols = List.of(c("effective_on", "Fecha", "date"), c("code", "Suscripción", "text"), c("customer", "Cliente", "text"),
                        c("product", "Producto", "text"), c("type", "Evento", "text"), c("reason", "Motivo", "text"), c("mrr", "MRR", "money"));
                rows = jdbc.queryForList("""
                        SELECT e.effective_on, s.code, c.trade_name AS customer, pr.name AS product, e.type, s.cancellation_reason AS reason,
                               ROUND(CASE WHEN e.type = 'CANCELLED' THEN e.mrr_before ELSE e.mrr_after END, 2) AS mrr
                        FROM subscription_events e JOIN subscriptions s ON s.id = e.subscription_id JOIN customers c ON c.id = e.customer_id
                        JOIN products pr ON pr.id = e.product_id
                        WHERE e.effective_on BETWEEN :f AND :t AND e.type IN (""" + (isNew ? "'ACTIVATED', 'TRIAL_CONVERTED'" : "'CANCELLED'") + """
                        ) AND (:pid::bigint IS NULL OR e.product_id = :pid) ORDER BY e.effective_on DESC""", p);
                if (!isNew) {
                    List<Map<String, Object>> reasons = jdbc.queryForList("""
                            SELECT COALESCE(s.cancellation_reason, 'SIN_MOTIVO') AS label, COUNT(*) AS value FROM subscription_events e
                            JOIN subscriptions s ON s.id = e.subscription_id WHERE e.type = 'CANCELLED' AND e.effective_on BETWEEN :f AND :t
                            AND (:pid::bigint IS NULL OR e.product_id = :pid) GROUP BY 1 ORDER BY 2 DESC""", p);
                    chart = Map.of("kind", "hbar", "format", "count", "series", List.of("Cancelaciones"),
                            "points", reasons.stream().map(r -> MetricsSql.point(String.valueOf(r.get("label")), r.get("value"))).toList());
                }
                if (!SecurityUtils.has(FIN)) {
                    rows.forEach(r -> r.remove("mrr"));
                    cols = cols.stream().filter(col -> !col.key().equals("mrr")).toList();
                }
            }
            case "prospects-by-stage" -> {
                cols = List.of(c("stage", "Etapa", "text"), c("count", "Prospectos", "count"), c("value", "Valor estimado", "money"), c("weighted", "Ponderado", "money"));
                rows = jdbc.queryForList("""
                        SELECT stage, COUNT(*) AS count, COALESCE(SUM(estimated_value), 0) AS value,
                               COALESCE(ROUND(SUM(estimated_value * probability / 100.0), 2), 0) AS weighted
                        FROM prospects p WHERE (stage NOT IN ('WON', 'LOST', 'DISCARDED') OR stage_changed_at::date BETWEEN :f AND :t)
                          AND (:pid::bigint IS NULL OR EXISTS (SELECT 1 FROM prospect_interests i WHERE i.prospect_id = p.id AND i.product_id = :pid))
                        GROUP BY stage ORDER BY MIN(probability)""", p);
                chart = chart(rows, "stage", List.of("count"), List.of("Prospectos"), "count", "bar");
            }
            case "conversion" -> {
                cols = List.of(c("owner", "Ejecutivo", "text"), c("source", "Fuente", "text"), c("created", "Creados", "count"), c("won", "Ganados", "count"),
                        c("lost", "Perdidos", "count"), c("rate", "Conversión", "percent"));
                rows = jdbc.queryForList("""
                        SELECT COALESCE(u.first_name || ' ' || u.last_name, 'Sin asignar') AS owner, p.source,
                               COUNT(*) FILTER (WHERE p.created_at::date BETWEEN :f AND :t) AS created,
                               COUNT(*) FILTER (WHERE p.stage = 'WON' AND p.stage_changed_at::date BETWEEN :f AND :t) AS won,
                               COUNT(*) FILTER (WHERE p.stage = 'LOST' AND p.stage_changed_at::date BETWEEN :f AND :t) AS lost,
                               ROUND(100.0 * COUNT(*) FILTER (WHERE p.stage = 'WON' AND p.stage_changed_at::date BETWEEN :f AND :t)
                                 / NULLIF(COUNT(*) FILTER (WHERE p.stage IN ('WON', 'LOST') AND p.stage_changed_at::date BETWEEN :f AND :t), 0), 1) AS rate
                        FROM prospects p LEFT JOIN users u ON u.id = p.owner_user_id GROUP BY 1, 2 ORDER BY 4 DESC, 3 DESC""", p);
            }
            case "team-performance" -> {
                cols = List.of(c("owner", "Ejecutivo", "text"), c("quotes", "Cotizaciones", "count"), c("accepted", "Aceptadas", "count"),
                        c("acceptedAmount", "Monto aceptado", "money"), c("rate", "Aceptación", "percent"), c("newCustomers", "Clientes nuevos", "count"),
                        c("customers", "Cartera", "count"), c("newSubscriptions", "Contrataciones", "count"), c("liveSubscriptions", "Vigentes", "count"),
                        c("mrr", "MRR vigente", "money"));
                rows = jdbc.queryForList("""
                        WITH q AS (SELECT owner_user_id AS uid,
                                     COUNT(*) FILTER (WHERE version_no = 1 AND created_at::date BETWEEN :f AND :t) AS quotes,
                                     COUNT(*) FILTER (WHERE status = 'ACCEPTED' AND decided_at::date BETWEEN :f AND :t) AS accepted,
                                     COUNT(*) FILTER (WHERE status = 'REJECTED' AND decided_at::date BETWEEN :f AND :t) AS rejected,
                                     COALESCE(SUM(total) FILTER (WHERE status = 'ACCEPTED' AND decided_at::date BETWEEN :f AND :t), 0) AS accepted_amount
                                   FROM quotes GROUP BY 1),
                             c AS (SELECT owner_user_id AS uid, COUNT(*) FILTER (WHERE customer_since BETWEEN :f AND :t) AS new_customers,
                                     COUNT(*) FILTER (WHERE NOT archived) AS customers
                                   FROM customers GROUP BY 1),
                             s AS (SELECT seller_user_id AS uid, COUNT(*) FILTER (WHERE start_date BETWEEN :f AND :t) AS new_subs,
                                     COUNT(*) FILTER (WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')) AS live_subs,
                                     COALESCE(SUM(mrr) FILTER (WHERE status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED')), 0) AS mrr
                                   FROM subscriptions GROUP BY 1)
                        SELECT u.first_name || ' ' || u.last_name AS owner, COALESCE(q.quotes, 0) AS quotes, COALESCE(q.accepted, 0) AS accepted,
                               COALESCE(q.accepted_amount, 0) AS "acceptedAmount",
                               ROUND(100.0 * q.accepted / NULLIF(q.accepted + q.rejected, 0), 1) AS rate,
                               COALESCE(c.new_customers, 0) AS "newCustomers", COALESCE(c.customers, 0) AS customers,
                               COALESCE(s.new_subs, 0) AS "newSubscriptions", COALESCE(s.live_subs, 0) AS "liveSubscriptions",
                               ROUND(COALESCE(s.mrr, 0), 2) AS mrr
                        FROM users u LEFT JOIN q ON q.uid = u.id LEFT JOIN c ON c.uid = u.id LEFT JOIN s ON s.uid = u.id
                        WHERE COALESCE(q.quotes, 0) + COALESCE(q.accepted, 0) + COALESCE(q.rejected, 0) + COALESCE(c.customers, 0)
                              + COALESCE(c.new_customers, 0) + COALESCE(s.new_subs, 0) + COALESCE(s.live_subs, 0) > 0
                        ORDER BY mrr DESC, "acceptedAmount" DESC, owner""", p);
                chart = chart(rows, "owner", List.of("mrr"), List.of("MRR vigente"), "money", "hbar");
                notes.add("Cartera, vigentes y MRR son al día de hoy; el resto se cuenta dentro del periodo.");
                notes.add("El vendedor de una contratación se puede corregir desde la ficha de la suscripción.");
            }
            case "lost-reasons" -> {
                cols = List.of(c("reason", "Motivo", "text"), c("count", "Oportunidades", "count"), c("value", "Valor estimado", "money"));
                rows = jdbc.queryForList("""
                        SELECT lost_reason AS reason, COUNT(*) AS count, COALESCE(SUM(estimated_value), 0) AS value FROM prospects
                        WHERE stage = 'LOST' AND stage_changed_at::date BETWEEN :f AND :t GROUP BY 1 ORDER BY 2 DESC""", p);
                chart = chart(rows, "reason", List.of("count"), List.of("Perdidas"), "count", "hbar");
            }
            case "growth-by-product" -> {
                List<Map<String, Object>> products = jdbc.queryForList("SELECT id, code, name FROM products WHERE active ORDER BY sort_order", Map.of());
                List<Column> cl = new ArrayList<>(List.of(c("month", "Mes", "text")));
                products.forEach(pr -> cl.add(c(String.valueOf(pr.get("code")), String.valueOf(pr.get("name")), "money")));
                cl.add(c("total", "Total", "money"));
                cols = cl;
                rows = new ArrayList<>();
                for (YearMonth ym = YearMonth.from(f); !ym.isAfter(YearMonth.from(t)); ym = ym.plusMonths(1)) {
                    LocalDate end = ym.atEndOfMonth().isAfter(today) ? today : ym.atEndOfMonth();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("month", ym.toString());
                    BigDecimal total = BigDecimal.ZERO;
                    for (Map<String, Object> pr : products) {
                        BigDecimal v = metrics.mrrAt(end, ((Number) pr.get("id")).longValue(), null).setScale(2, java.math.RoundingMode.HALF_UP);
                        r.put(String.valueOf(pr.get("code")), v);
                        total = total.add(v);
                    }
                    r.put("total", total);
                    rows.add(r);
                }
                chart = chart(rows, "month", List.of("total"), List.of("MRR total"), "money", "line");
            }
            case "recurring-revenue" -> {
                cols = List.of(c("month", "Mes", "text"), c("mrrStart", "MRR inicial", "money"), c("newMrr", "New", "money"), c("expansionMrr", "Expansion", "money"),
                        c("contractionMrr", "Contraction", "money"), c("churnMrr", "Churn", "money"), c("reactivationMrr", "Reactivation", "money"),
                        c("mrrEnd", "MRR final", "money"), c("nrr", "NRR", "percent"), c("grr", "GRR", "percent"));
                rows = new ArrayList<>();
                for (YearMonth ym = YearMonth.from(f); !ym.isAfter(YearMonth.from(t)); ym = ym.plusMonths(1)) {
                    rows.add(new SaasMetricsService(metrics, jdbc, settings).month(ym, today, productId));
                }
                chart = chart(rows, "month", List.of("mrrEnd"), List.of("MRR"), "money", "line");
            }
            case "period-comparison" -> {
                long days = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1;
                LocalDate pf = f.minusDays(days);
                LocalDate pt = f.minusDays(1);
                cols = List.of(c("metric", "Indicador", "text"), c("current", "Periodo", "number"), c("previous", "Anterior", "number"), c("variation", "Variación", "percent"));
                rows = new ArrayList<>();
                rows.add(cmp("Cobrado", metrics.collected(f, t, productId), metrics.collected(pf, pt, productId)));
                rows.add(cmp("Facturado", metrics.billed(f, t, productId), metrics.billed(pf, pt, productId)));
                rows.add(cmp("MRR al cierre", metrics.mrrAt(t.isAfter(today) ? today : t, productId, null).setScale(2, java.math.RoundingMode.HALF_UP),
                        metrics.mrrAt(pt, productId, null).setScale(2, java.math.RoundingMode.HALF_UP)));
                rows.add(cmp("Nuevas contrataciones", count("ACTIVATED','TRIAL_CONVERTED", f, t, productId), count("ACTIVATED','TRIAL_CONVERTED", pf, pt, productId)));
                rows.add(cmp("Cancelaciones", count("CANCELLED", f, t, productId), count("CANCELLED", pf, pt, productId)));
                notes.add("Periodo anterior: " + pf.format(DateTimeFormatter.ISO_DATE) + " a " + pt.format(DateTimeFormatter.ISO_DATE));
            }
            case "profitability" -> {
                cols = List.of(c("month", "Mes", "text"), c("income", "Cobrado", "money"), c("expenses", "Gastos", "money"), c("result", "Resultado estimado", "money"),
                        c("margin", "Margen", "percent"));
                rows = jdbc.queryForList("""
                        WITH months AS (SELECT generate_series(date_trunc('month', :f::date), date_trunc('month', :t::date), interval '1 month')::date AS m)
                        SELECT to_char(m, 'YYYY-MM') AS month, inc AS income, exp AS expenses, inc - exp AS result,
                               CASE WHEN inc > 0 THEN ROUND(100 * (inc - exp) / inc, 1) END AS margin
                        FROM (SELECT m,
                          (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status IN ('CONFIRMED', 'RECONCILED') AND date_trunc('month', received_on) = m)
                            - (SELECT COALESCE(SUM(amount), 0) FROM payment_refunds WHERE date_trunc('month', refunded_on) = m) AS inc,
                          (SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE voided_at IS NULL AND date_trunc('month', expense_date) = m
                             AND (:pid::bigint IS NULL OR product_id = :pid)) AS exp FROM months) x ORDER BY month""", p);
                chart = chart(rows, "month", List.of("income", "expenses"), List.of("Cobrado", "Gastos"), "money", "bar");
                notes.add("Utilidad estimada de flujo: no considera impuestos, depreciación ni gastos no capturados. No es utilidad contable.");
            }
            default -> throw new BusinessException("Reporte desconocido");
        }
        return new Report(type, d.title(), d.basis(), f, t, settings.currency(), cols, rows, totals(cols, rows), chart, notes);
    }

    public Table table(Report r) {
        return new Table(r.title(), "Periodo " + r.from() + " a " + r.to() + " · " + r.currency() + " · " + r.basis(), r.columns(), r.rows(), r.notes());
    }

    private Map<String, Object> cmp(String label, BigDecimal a, BigDecimal b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", label);
        m.put("current", a);
        m.put("previous", b);
        m.put("variation", MetricsSql.variation(a, b));
        return m;
    }

    private BigDecimal count(String types, LocalDate f, LocalDate t, Long productId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM subscription_events e WHERE e.type IN ('" + types + "') AND e.effective_on BETWEEN :f AND :t"
                + (productId != null ? " AND e.product_id = :pid" : ""), new MapSqlParameterSource("f", f).addValue("t", t).addValue("pid", productId), Long.class);
        return BigDecimal.valueOf(n == null ? 0 : n);
    }

    private static List<Map<String, Object>> totals(List<Column> cols, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Column col : cols) {
            if (("money".equals(col.format()) || "count".equals(col.format())) && !rows.isEmpty()) {
                BigDecimal sum = rows.stream().map(r -> r.get(col.key())).filter(v -> v instanceof Number)
                        .map(v -> new BigDecimal(v.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
                out.add(Map.of("key", col.key(), "label", col.label(), "value", sum, "format", col.format()));
            }
        }
        return out;
    }

    private static Map<String, Object> chart(List<Map<String, Object>> rows, String labelKey, List<String> keys, List<String> series, String format, String kind) {
        List<Map<String, Object>> points = rows.stream().map(r -> Map.<String, Object>of("label", String.valueOf(r.get(labelKey)),
                "values", keys.stream().map(k -> r.get(k) == null ? 0 : r.get(k)).toList())).toList();
        return Map.of("kind", kind, "format", format, "series", series, "points", points);
    }

    private static Column c(String key, String label, String format) {
        return new Column(key, label, format);
    }
}

