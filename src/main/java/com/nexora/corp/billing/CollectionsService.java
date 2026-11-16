package com.nexora.corp.billing;

import com.nexora.corp.billing.BillingDtos.AgingBucket;
import com.nexora.corp.billing.BillingDtos.CollectionActionRequest;
import com.nexora.corp.billing.BillingDtos.CollectionActionRow;
import com.nexora.corp.billing.BillingDtos.ReceivableRow;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Cuentas por cobrar: cartera por antigüedad (intervalos configurables en {@code collections.agingBuckets}),
 * morosidad por cliente y gestiones de cobranza.
 */
@Service
@RequiredArgsConstructor
public class CollectionsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    /** Intervalos: "No vencida", "1–7", "8–15", "16–30", "31–60", "Más de 60" (según configuración). */
    public List<AgingBucket> aging() {
        List<Integer> limits = settings.intList(Setting.AGING_BUCKETS);
        List<AgingBucket> out = new ArrayList<>();
        out.add(bucket("current", "No vencida", Integer.MIN_VALUE, 0));
        int from = 1;
        for (int limit : limits) {
            out.add(bucket("d" + from + "_" + limit, from + "–" + limit + " días", from, limit));
            from = limit + 1;
        }
        out.add(bucket("d" + from + "_plus", "Más de " + (from - 1) + " días", from, null));
        return out;
    }

    private AgingBucket bucket(String key, String label, int fromDays, Integer toDays) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", fromDays).addValue("to", toDays == null ? Integer.MAX_VALUE : toDays);
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance), 0), COUNT(*), COUNT(DISTINCT customer_id) FROM charge_balances
                WHERE status IN ('OPEN', 'PARTIAL') AND (nx_today() - due_date) BETWEEN :from AND :to""", p,
                (rs, i) -> new AgingBucket(key, label, fromDays == Integer.MIN_VALUE ? 0 : fromDays, toDays == null ? null : toDays,
                        rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3)));
    }

    public record Filters(String q, String bucket, Long responsibleId, boolean overdueOnly) {
    }

    private static final Map<String, String> SORT = Map.of("overdue", "overdue_amount", "days", "days_overdue", "open", "open_amount",
            "customer", "lower(trade_name)", "nextAction", "next_action_on");

    @Transactional(readOnly = true)
    public PageResponse<ReceivableRow> receivables(Filters f, PageQuery page) {
        List<AgingBucket> buckets = aging();
        StringBuilder bucketCase = new StringBuilder("CASE");
        for (AgingBucket b : buckets) {
            if ("current".equals(b.key())) {
                bucketCase.append(" WHEN x.days_overdue <= 0 THEN 'current'");
            } else if (b.toDays() == null) {
                bucketCase.append(" WHEN x.days_overdue >= ").append(b.fromDays()).append(" THEN '").append(b.key()).append("'");
            } else {
                bucketCase.append(" WHEN x.days_overdue BETWEEN ").append(b.fromDays()).append(" AND ").append(b.toDays())
                        .append(" THEN '").append(b.key()).append("'");
            }
        }
        bucketCase.append(" END");
        String base = """
                SELECT x.*, %s AS bucket FROM (
                  SELECT c.id AS customer_id, c.code, c.trade_name, c.email, c.phone,
                         SUM(ch.balance) AS open_amount,
                         SUM(CASE WHEN ch.due_date < nx_today() THEN ch.balance ELSE 0 END) AS overdue_amount,
                         MIN(ch.due_date) FILTER (WHERE ch.due_date < nx_today()) AS oldest_due,
                         COALESCE(MAX(nx_today() - ch.due_date) FILTER (WHERE ch.due_date < nx_today()), 0) AS days_overdue,
                         COUNT(*) FILTER (WHERE ch.due_date < nx_today() AND ch.type = 'SUBSCRIPTION') AS overdue_periods,
                         ARRAY(SELECT DISTINCT p.code FROM charges c2 JOIN products p ON p.id = c2.product_id
                               WHERE c2.customer_id = c.id AND c2.status IN ('OPEN', 'PARTIAL') ORDER BY p.code) AS products,
                         (SELECT MAX(received_on) FROM payments py WHERE py.customer_id = c.id AND py.status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED')) AS last_payment_on,
                         (SELECT py.amount FROM payments py WHERE py.customer_id = c.id AND py.status IN ('CAPTURED', 'CONFIRMED', 'RECONCILED')
                          ORDER BY py.received_on DESC, py.id DESC LIMIT 1) AS last_payment_amount,
                         la.created_at AS last_action_at, la.type AS last_action_type, na.next_action_on,
                         COALESCE(na.responsible_user_id, c.owner_user_id) AS responsible_id,
                         (SELECT COALESCE(SUM(unapplied), 0) FROM payment_balances pb WHERE pb.customer_id = c.id) AS credit
                  FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id
                  LEFT JOIN LATERAL (SELECT created_at, type FROM collection_actions ca WHERE ca.customer_id = c.id ORDER BY created_at DESC LIMIT 1) la ON TRUE
                  LEFT JOIN LATERAL (SELECT next_action_on, responsible_user_id FROM collection_actions ca WHERE ca.customer_id = c.id
                                     AND next_action_on IS NOT NULL ORDER BY created_at DESC LIMIT 1) na ON TRUE
                  WHERE ch.status IN ('OPEN', 'PARTIAL')
                  GROUP BY c.id, la.created_at, la.type, na.next_action_on, na.responsible_user_id
                ) x""".formatted(bucketCase);
        Sql sql = new Sql()
                .and(Sql.has(f.q()), "(lower(y.trade_name) LIKE :q OR lower(y.code) LIKE :q)", "q", Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(Sql.has(f.bucket()), "y.bucket = :bucket", "bucket", f.bucket())
                .and(f.responsibleId() != null, "y.responsible_id = :resp", "resp", f.responsibleId())
                .and(f.overdueOnly(), "y.overdue_amount > 0", "oo", null);
        String from = " FROM (" + base + ") y LEFT JOIN users u ON u.id = y.responsible_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + sql.where(), sql.params(), Long.class);
        List<ReceivableRow> rows = jdbc.query("SELECT y.*, u.first_name || ' ' || u.last_name AS responsible_name" + from + sql.where()
                + page.orderBy(SORT, "overdue_amount DESC, days_overdue DESC") + page.limit(), sql.params(), (rs, i) -> receivable(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    private static ReceivableRow receivable(ResultSet rs) throws SQLException {
        Date oldest = rs.getDate("oldest_due");
        Date lastPay = rs.getDate("last_payment_on");
        Timestamp lastAction = rs.getTimestamp("last_action_at");
        Date next = rs.getDate("next_action_on");
        return new ReceivableRow(rs.getLong("customer_id"), rs.getString("code"), rs.getString("trade_name"), rs.getString("email"),
                rs.getString("phone"), Arrays.asList((String[]) rs.getArray("products").getArray()), rs.getBigDecimal("open_amount"),
                rs.getBigDecimal("overdue_amount"), oldest == null ? null : oldest.toLocalDate(), rs.getInt("days_overdue"),
                rs.getInt("overdue_periods"), lastPay == null ? null : lastPay.toLocalDate(), rs.getBigDecimal("last_payment_amount"),
                lastAction == null ? null : lastAction.toInstant(), rs.getString("last_action_type"), next == null ? null : next.toLocalDate(),
                rs.getString("responsible_name"), (Long) rs.getObject("responsible_id"), rs.getString("bucket"), rs.getBigDecimal("credit"));
    }

    @Transactional
    public CollectionActionRow addAction(CollectionActionRequest r) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE id = :id", Map.of("id", r.customerId()), Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("Cliente no encontrado");
        }
        if (r.subscriptionId() != null) {
            Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM subscriptions WHERE id = :s AND customer_id = :c",
                    Map.of("s", r.subscriptionId(), "c", r.customerId()), Integer.class);
            if (ok == null || ok == 0) {
                throw new BusinessException("La suscripción no pertenece al cliente");
            }
        }
        if ("PROMISE".equals(r.type()) && (r.promiseDate() == null || r.promiseAmount() == null)) {
            throw new BusinessException("Una promesa de pago requiere fecha e importe");
        }
        if (r.nextActionOn() != null && r.nextActionOn().isBefore(settings.today())) {
            throw new BusinessException("La próxima acción no puede ser en el pasado");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO collection_actions (customer_id, subscription_id, type, notes, promise_date, promise_amount, next_action_on,
                                                responsible_user_id, created_by)
                VALUES (:c, :s, :t, :n, :pd, :pa, :na, :r, :by) RETURNING id""", new MapSqlParameterSource()
                .addValue("c", r.customerId()).addValue("s", r.subscriptionId()).addValue("t", r.type()).addValue("n", r.notes().trim())
                .addValue("pd", r.promiseDate()).addValue("pa", r.promiseAmount()).addValue("na", r.nextActionOn())
                .addValue("r", r.responsibleUserId() != null ? r.responsibleUserId() : SecurityUtils.currentUserId())
                .addValue("by", SecurityUtils.currentUserId()), Long.class);
        audit.record(AuditEntry.success("collections", "COLLECTION_ACTION", "Customer", r.customerId()).detail(r.type() + ": " + r.notes()).build());
        return actions(r.customerId(), null).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<CollectionActionRow> actions(Long customerId, Boolean upcoming) {
        Sql sql = new Sql().and(customerId != null, "a.customer_id = :c", "c", customerId)
                .and(Boolean.TRUE.equals(upcoming), "a.next_action_on IS NOT NULL AND a.next_action_on >= nx_today() - 30", "u", null);
        return jdbc.query("""
                SELECT a.id, a.customer_id, c.trade_name, a.subscription_id, a.type, a.notes, a.promise_date, a.promise_amount,
                       a.next_action_on, a.responsible_user_id, r.first_name || ' ' || r.last_name AS responsible, b.first_name || ' ' || b.last_name AS author,
                       a.created_at
                FROM collection_actions a JOIN customers c ON c.id = a.customer_id LEFT JOIN users r ON r.id = a.responsible_user_id
                LEFT JOIN users b ON b.id = a.created_by""" + sql.where() + " ORDER BY a.created_at DESC LIMIT 300", sql.params(), (rs, i) -> {
            Date pd = rs.getDate("promise_date");
            Date na = rs.getDate("next_action_on");
            return new CollectionActionRow(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("trade_name"),
                    (Long) rs.getObject("subscription_id"), rs.getString("type"), rs.getString("notes"), pd == null ? null : pd.toLocalDate(),
                    rs.getBigDecimal("promise_amount"), na == null ? null : na.toLocalDate(), (Long) rs.getObject("responsible_user_id"),
                    rs.getString("responsible"), rs.getString("author"), rs.getTimestamp("created_at").toInstant());
        });
    }

    public BigDecimal totalOverdue() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM charge_balances WHERE status IN ('OPEN', 'PARTIAL') AND due_date < nx_today()",
                Map.of(), BigDecimal.class);
    }
}

