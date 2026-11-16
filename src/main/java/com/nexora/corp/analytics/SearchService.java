package com.nexora.corp.analytics;

import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Búsqueda global (Ctrl + K): solo devuelve tipos de registro que el usuario puede ver. */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String q) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("q", Sql.like(q)).addValue("exact", q.trim().toUpperCase()).addValue("me", SecurityUtils.currentUserId());
        List<Map<String, Object>> out = new ArrayList<>();
        if (SecurityUtils.has("customers.read")) {
            String rfc = SecurityUtils.has("customers.fiscal_read") ? " OR upper(COALESCE(rfc, '')) = :exact" : "";
            out.addAll(jdbc.queryForList("""
                    SELECT 'CUSTOMER' AS type, id, trade_name AS title, code || ' · ' || email AS subtitle, '/clientes/' || id AS link FROM customers
                    WHERE lower(trade_name) LIKE :q OR lower(code) LIKE :q OR lower(email) LIKE :q OR lower(contact_name) LIKE :q""" + rfc + " LIMIT 6", p));
        }
        if (SecurityUtils.has("prospects.read")) {
            String scope = SecurityUtils.has("prospects.read_all") ? "" : " AND owner_user_id = :me";
            out.addAll(jdbc.queryForList("""
                    SELECT 'PROSPECT' AS type, id, business_name AS title, contact_name || ' · ' || stage AS subtitle, '/crm?p=' || id AS link FROM prospects
                    WHERE (lower(business_name) LIKE :q OR lower(contact_name) LIKE :q OR lower(COALESCE(email, '')) LIKE :q)""" + scope + " LIMIT 5", p));
        }
        if (SecurityUtils.has("subscriptions.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'SUBSCRIPTION' AS type, s.id, s.code AS title, c.trade_name || ' · ' || p.name AS subtitle, '/suscripciones/' || s.id AS link
                    FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id
                    WHERE lower(s.code) LIKE :q LIMIT 5""", p));
        }
        if (SecurityUtils.has("payments.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'PAYMENT' AS type, py.id, py.folio AS title, c.trade_name || ' · $' || py.amount AS subtitle, '/cobranza/pagos/' || py.id AS link
                    FROM payments py JOIN customers c ON c.id = py.customer_id
                    WHERE lower(py.folio) LIKE :q OR lower(COALESCE(py.reference, '')) LIKE :q LIMIT 5""", p));
        }
        if (SecurityUtils.has("charges.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'CHARGE' AS type, ch.id, ch.folio AS title, c.trade_name || ' · $' || ch.total AS subtitle, '/clientes/' || c.id || '?tab=cobranza' AS link
                    FROM charges ch JOIN customers c ON c.id = ch.customer_id WHERE lower(ch.folio) LIKE :q LIMIT 5""", p));
        }
        if (SecurityUtils.has("support.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'TICKET' AS type, t.id, t.folio || ' · ' || t.subject AS title, c.trade_name AS subtitle, '/soporte/' || t.id AS link
                    FROM support_tickets t JOIN customers c ON c.id = t.customer_id WHERE lower(t.folio) LIKE :q OR lower(t.subject) LIKE :q LIMIT 5""", p));
        }
        if (SecurityUtils.has("quotes.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'QUOTE' AS type, q.id, q.folio || ' v' || q.version_no AS title, q.title AS subtitle, '/cotizaciones/' || q.id AS link
                    FROM quotes q WHERE (lower(q.folio) LIKE :q OR lower(q.title) LIKE :q) AND q.status <> 'SUPERSEDED' LIMIT 5""", p));
        }
        if (SecurityUtils.has("products.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'PRODUCT' AS type, id, name AS title, COALESCE(tagline, '') AS subtitle, '/productos/' || code AS link FROM products
                    WHERE lower(name) LIKE :q OR lower(code) LIKE :q LIMIT 4""", p));
        }
        if (SecurityUtils.has("users.read")) {
            out.addAll(jdbc.queryForList("""
                    SELECT 'USER' AS type, id, first_name || ' ' || last_name AS title, email AS subtitle, '/usuarios' AS link FROM users
                    WHERE lower(first_name || ' ' || last_name) LIKE :q OR lower(email) LIKE :q LIMIT 4""", p));
        }
        return out;
    }
}

