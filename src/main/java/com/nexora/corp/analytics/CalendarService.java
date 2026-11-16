package com.nexora.corp.analytics;

import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Calendario corporativo: cobros, renovaciones, fin de pruebas, seguimientos, demostraciones, gestiones y cotizaciones. */
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource("f", from).addValue("t", to).addValue("me", SecurityUtils.currentUserId());
        List<Map<String, Object>> out = new ArrayList<>();
        if (SecurityUtils.has("charges.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT ch.due_date AS date, 'CHARGE_DUE' AS type, c.trade_name AS title, ch.folio || ' · $' || ch.balance AS subtitle,
                           '/clientes/' || c.id || '?tab=cobranza' AS link, ch.due_date < nx_today() AS overdue
                    FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id
                    WHERE ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date BETWEEN :f AND :t""", p));
        }
        if (SecurityUtils.has("subscriptions.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT s.current_period_end AS date, CASE WHEN s.cancel_at_period_end THEN 'CANCELLATION' ELSE 'RENEWAL' END AS type,
                           c.trade_name AS title, p.name || ' · ' || s.code || CASE WHEN s.auto_renew THEN '' ELSE ' · renovación manual' END AS subtitle,
                           '/suscripciones/' || s.id AS link, FALSE AS overdue
                    FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id
                    WHERE s.status IN ('ACTIVE', 'GRACE', 'CANCEL_SCHEDULED') AND s.current_period_end BETWEEN :f AND :t
                    UNION ALL
                    SELECT s.trial_ends_on, 'TRIAL_END', c.trade_name, p.name || ' · fin de prueba', '/suscripciones/' || s.id, FALSE
                    FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id
                    WHERE s.status = 'TRIAL' AND s.trial_ends_on BETWEEN :f AND :t""", p));
        }
        if (SecurityUtils.has("prospects.read")) {
            String scope = SecurityUtils.has("prospects.read_all") ? "" : " AND (p.owner_user_id = :me OR a.user_id = :me)";
            out.addAll(jdbc.queryForList("""
                    SELECT p.next_contact_at::date AS date, 'FOLLOW_UP' AS type, p.business_name AS title, 'Próximo contacto · ' || p.contact_name AS subtitle,
                           '/crm?p=' || p.id AS link, p.next_contact_at < now() AS overdue
                    FROM prospects p LEFT JOIN sales_activities a ON FALSE
                    WHERE p.stage NOT IN ('WON', 'LOST', 'DISCARDED') AND p.next_contact_at::date BETWEEN :f AND :t""" + scope + " " + """
                    UNION ALL
                    SELECT a.scheduled_at::date, CASE WHEN a.type = 'DEMO' THEN 'DEMO' ELSE 'ACTIVITY' END, COALESCE(p.business_name, c.trade_name),
                           a.subject || ' · ' || to_char(a.scheduled_at AT TIME ZONE 'America/Mexico_City', 'HH24:MI'), CASE WHEN p.id IS NOT NULL THEN '/crm?p=' || p.id ELSE '/clientes/' || c.id END,
                           a.scheduled_at < now()
                    FROM sales_activities a LEFT JOIN prospects p ON p.id = a.prospect_id LEFT JOIN customers c ON c.id = a.customer_id
                    WHERE a.completed_at IS NULL AND a.scheduled_at::date BETWEEN :f AND :t""" + scope, p));
        }
        if (SecurityUtils.has("collections.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT COALESCE(ca.promise_date, ca.next_action_on) AS date, CASE WHEN ca.type = 'PROMISE' THEN 'PROMISE' ELSE 'COLLECTION' END AS type,
                           c.trade_name AS title, CASE WHEN ca.type = 'PROMISE' THEN 'Promesa de pago · $' || ca.promise_amount ELSE 'Gestión de cobranza' END AS subtitle,
                           '/clientes/' || c.id || '?tab=cobranza' AS link, FALSE AS overdue
                    FROM collection_actions ca JOIN customers c ON c.id = ca.customer_id
                    WHERE COALESCE(ca.promise_date, ca.next_action_on) BETWEEN :f AND :t""", p));
        }
        if (SecurityUtils.has("quotes.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT q.valid_until AS date, 'QUOTE_EXPIRES' AS type, COALESCE(c.trade_name, pr.business_name) AS title,
                           'Vence cotización ' || q.folio AS subtitle, '/cotizaciones/' || q.id AS link, FALSE AS overdue
                    FROM quotes q LEFT JOIN customers c ON c.id = q.customer_id LEFT JOIN prospects pr ON pr.id = q.prospect_id
                    WHERE q.status IN ('DRAFT', 'SENT') AND q.valid_until BETWEEN :f AND :t""", p));
        }
        out.sort((a, b) -> String.valueOf(a.get("date")).compareTo(String.valueOf(b.get("date"))));
        return out;
    }
}

