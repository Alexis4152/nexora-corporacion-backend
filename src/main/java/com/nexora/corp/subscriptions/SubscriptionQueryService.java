package com.nexora.corp.subscriptions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.subscriptions.SubscriptionDtos.EventRow;
import com.nexora.corp.subscriptions.SubscriptionDtos.SubscriptionDetail;
import com.nexora.corp.subscriptions.SubscriptionDtos.SubscriptionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lecturas de suscripciones con su estado de cobranza (una sola consulta, sin N+1). */
@Service
@RequiredArgsConstructor
public class SubscriptionQueryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SubscriptionService service;
    private final ObjectMapper objectMapper;

    public record Filters(String q, String status, String collection, Long productId, Long planId, Long customerId,
                          String period, Boolean trialEndingSoon, Long sellerId) {
    }

    private static final String SELECT = """
            SELECT s.id, s.code, s.customer_id, c.code AS customer_code, c.trade_name, s.product_id, p.code AS product_code,
                   p.name AS product_name, p.color, s.plan_id, pl.name AS plan_name, s.billing_period, s.price, s.mrr, s.currency,
                   s.status, sc.collection_status, s.start_date, s.trial_ends_on, s.current_period_start, s.current_period_end,
                   s.next_charge_on, sc.overdue_amount, sc.days_overdue, s.auto_renew, s.cancel_at_period_end, s.discount_type,
                   s.discount_value, sc.open_amount, s.seller_user_id, su.first_name || ' ' || su.last_name AS seller_name
            FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id
            JOIN plans pl ON pl.id = s.plan_id LEFT JOIN users su ON su.id = s.seller_user_id
            JOIN subscription_collection sc ON sc.subscription_id = s.id""";

    private static final Map<String, String> SORT = Map.of("code", "s.code", "customer", "lower(c.trade_name)", "mrr", "s.mrr",
            "price", "s.price", "start", "s.start_date", "periodEnd", "s.current_period_end", "overdue", "sc.overdue_amount",
            "trialEnds", "s.trial_ends_on");

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionRow> list(Filters f, PageQuery page) {
        Sql sql = new Sql()
                .and(Sql.has(f.q()), "(lower(c.trade_name) LIKE :q OR lower(s.code) LIKE :q OR lower(c.code) LIKE :q)", "q",
                        Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(Sql.has(f.status()) && !"LIVE".equals(f.status()), "s.status = :st", "st", f.status())
                .and("LIVE".equals(f.status()), "s.status NOT IN ('CANCELLED', 'EXPIRED')", "lv", null)
                .and(Sql.has(f.collection()), "sc.collection_status = :col", "col", f.collection())
                .and(f.productId() != null, "s.product_id = :pid", "pid", f.productId())
                .and(f.planId() != null, "s.plan_id = :plan", "plan", f.planId())
                .and(f.customerId() != null, "s.customer_id = :cid", "cid", f.customerId())
                .and(Sql.has(f.period()), "s.billing_period = :per", "per", f.period())
                .and(f.sellerId() != null, "s.seller_user_id = :seller", "seller", f.sellerId())
                .and(Boolean.TRUE.equals(f.trialEndingSoon()), "s.status = 'TRIAL' AND s.trial_ends_on <= nx_today() + 7", "te", null);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + SELECT + sql.where() + ") x", sql.params(), Long.class);
        List<SubscriptionRow> rows = jdbc.query(SELECT + sql.where() + page.orderBy(SORT, "s.created_at DESC") + page.limit(),
                sql.params(), (rs, i) -> row(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionRow> forCustomer(Long customerId) {
        return jdbc.query(SELECT + " WHERE s.customer_id = :c ORDER BY s.status IN ('CANCELLED', 'EXPIRED'), p.sort_order",
                Map.of("c", customerId), (rs, i) -> row(rs));
    }

    @Transactional(readOnly = true)
    public SubscriptionDetail detail(Long id) {
        List<SubscriptionDetail> found = jdbc.query(SELECT.replace("SELECT s.id,", """
                SELECT s.list_price, s.tax_included, s.discount_cycles_remaining, cp.code AS coupon_code, s.grace_days,
                       s.preferred_payment_method, s.cancellation_reason, s.cancellation_detail, s.cancelled_on, s.suspended_at,
                       s.pending_plan_id, ppl.name AS pending_plan_name, s.pending_price, s.pending_billing_period, s.notes, s.version, s.id,""")
                .replace("JOIN subscription_collection sc ON sc.subscription_id = s.id",
                        "JOIN subscription_collection sc ON sc.subscription_id = s.id LEFT JOIN coupons cp ON cp.id = s.coupon_id "
                                + "LEFT JOIN plans ppl ON ppl.id = s.pending_plan_id")
                + " WHERE s.id = :id", Map.of("id", id), (rs, i) -> {
            SubscriptionRow row = row(rs);
            var suspended = rs.getTimestamp("suspended_at");
            Date cancelled = rs.getDate("cancelled_on");
            String pendingPeriod = rs.getString("pending_billing_period");
            boolean money = SecurityUtils.has("charges.read") || SecurityUtils.has("collections.read");
            return new SubscriptionDetail(row, rs.getBigDecimal("list_price"), rs.getBoolean("tax_included"),
                    (Integer) rs.getObject("discount_cycles_remaining"), rs.getString("coupon_code"), rs.getInt("grace_days"),
                    rs.getString("preferred_payment_method"), rs.getString("cancellation_reason"), rs.getString("cancellation_detail"),
                    cancelled == null ? null : cancelled.toLocalDate(), suspended == null ? null : suspended.toInstant(),
                    (Long) rs.getObject("pending_plan_id"), rs.getString("pending_plan_name"), rs.getBigDecimal("pending_price"),
                    pendingPeriod == null ? null : BillingPeriod.valueOf(pendingPeriod), rs.getString("notes"),
                    money ? rs.getBigDecimal("open_amount") : null, events(id), rs.getInt("version"), actions(row));
        });
        if (found.isEmpty()) {
            throw new NotFoundException("Suscripción no encontrada");
        }
        return found.get(0);
    }

    private List<EventRow> events(Long id) {
        return jdbc.query("""
                SELECT e.id, e.type, e.from_status, e.to_status, e.effective_on, e.mrr_before, e.mrr_after, e.movement, e.data::text,
                       u.first_name || ' ' || u.last_name AS user_name, e.created_at
                FROM subscription_events e LEFT JOIN users u ON u.id = e.user_id WHERE e.subscription_id = :id ORDER BY e.id DESC""",
                Map.of("id", id), (rs, i) -> {
                    Map<String, Object> data = null;
                    String json = rs.getString(9);
                    if (json != null) {
                        try {
                            data = objectMapper.readValue(json, new TypeReference<>() { });
                        } catch (Exception e) {
                            data = Map.of();
                        }
                    }
                    return new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDate(5).toLocalDate(),
                            rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), data, rs.getString(10), rs.getTimestamp(11).toInstant());
                });
    }

    /** Acciones válidas según el estado (el backend vuelve a validar cada una). */
    static List<String> actions(SubscriptionRow r) {
        List<String> a = new ArrayList<>();
        switch (SubscriptionStatus.valueOf(r.status())) {
            case PENDING_ACTIVATION -> a.addAll(List.of("ACTIVATE", "CHANGE_PLAN", "CANCEL"));
            case TRIAL -> a.addAll(List.of("CONVERT_TRIAL", "CHANGE_PLAN", "CANCEL"));
            case ACTIVE, GRACE -> {
                a.addAll(List.of("CHANGE_PLAN", "DISCOUNT", "SUSPEND", "CANCEL", "TERMS"));
                if (!r.autoRenew()) {
                    a.add("RENEW");
                }
            }
            case SUSPENDED -> a.addAll(List.of("REACTIVATE", "CANCEL", "TERMS"));
            case CANCEL_SCHEDULED -> a.addAll(List.of("UNDO_CANCEL", "SUSPEND", "CANCEL", "TERMS"));
            case CANCELLED -> a.add("REACTIVATE");
            case EXPIRED -> a.add(r.currentPeriodStart() == null ? "CONVERT_TRIAL" : "REACTIVATE");
        }
        return a;
    }

    private static SubscriptionRow row(ResultSet rs) throws SQLException {
        boolean money = SecurityUtils.has("charges.read") || SecurityUtils.has("collections.read");
        return new SubscriptionRow(rs.getLong("id"), rs.getString("code"), rs.getLong("customer_id"), rs.getString("customer_code"),
                rs.getString("trade_name"), rs.getLong("product_id"), rs.getString("product_code"), rs.getString("product_name"),
                rs.getString("color"), rs.getLong("plan_id"), rs.getString("plan_name"), BillingPeriod.valueOf(rs.getString("billing_period")),
                rs.getBigDecimal("price"), rs.getBigDecimal("mrr"), rs.getString("currency"), rs.getString("status"),
                rs.getString("collection_status"), date(rs, "start_date"), date(rs, "trial_ends_on"), date(rs, "current_period_start"),
                date(rs, "current_period_end"), date(rs, "next_charge_on"), money ? rs.getBigDecimal("overdue_amount") : null,
                rs.getInt("days_overdue"), rs.getBoolean("auto_renew"), rs.getBoolean("cancel_at_period_end"), rs.getString("discount_type"),
                rs.getBigDecimal("discount_value"), (Long) rs.getObject("seller_user_id"), rs.getString("seller_name"));
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

