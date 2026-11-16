package com.nexora.corp.billing;

import com.nexora.corp.billing.BillingDtos.AdjustmentRow;
import com.nexora.corp.billing.BillingDtos.AllocationRow;
import com.nexora.corp.billing.BillingDtos.ChargeDetail;
import com.nexora.corp.billing.BillingDtos.ChargeRow;
import com.nexora.corp.billing.BillingDtos.PaymentDetail;
import com.nexora.corp.billing.BillingDtos.PaymentRow;
import com.nexora.corp.billing.BillingDtos.RefundRow;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    public record ChargeFilters(String q, Long customerId, Long subscriptionId, Long productId, String status, Boolean overdue,
                                LocalDate from, LocalDate to) {
    }

    public record PaymentFilters(String q, Long customerId, String status, String method, LocalDate from, LocalDate to,
                                 Boolean withCredit) {
    }

    static final String CHARGE_SELECT = """
            SELECT ch.id, ch.folio, ch.customer_id, c.trade_name, ch.subscription_id, s.code AS sub_code, p.code AS product_code,
                   p.color, ch.type, ch.description, ch.period_start, ch.period_end, ch.issue_date, ch.due_date, ch.total,
                   ch.paid_amount, ch.adjusted_amount, ch.balance, ch.status, ch.days_overdue, ch.currency
            FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id LEFT JOIN subscriptions s ON s.id = ch.subscription_id
            LEFT JOIN products p ON p.id = ch.product_id""";

    private static final Map<String, String> CHARGE_SORT = Map.of("due", "ch.due_date", "issue", "ch.issue_date", "total", "ch.total",
            "balance", "ch.balance", "folio", "ch.folio", "customer", "lower(c.trade_name)");

    @Transactional(readOnly = true)
    public PageResponse<ChargeRow> charges(ChargeFilters f, PageQuery page) {
        Sql sql = new Sql()
                .and(Sql.has(f.q()), "(lower(ch.folio) LIKE :q OR lower(c.trade_name) LIKE :q OR lower(ch.description) LIKE :q)", "q",
                        Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(f.customerId() != null, "ch.customer_id = :cid", "cid", f.customerId())
                .and(f.subscriptionId() != null, "ch.subscription_id = :sid", "sid", f.subscriptionId())
                .and(f.productId() != null, "ch.product_id = :pid", "pid", f.productId())
                .and("PENDING".equals(f.status()), "ch.status IN ('OPEN', 'PARTIAL')", "pe", null)
                .and(Sql.has(f.status()) && !"PENDING".equals(f.status()), "ch.status = :st", "st", f.status())
                .and(Boolean.TRUE.equals(f.overdue()), "ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date < nx_today()", "ov", null)
                .and(f.from() != null, "ch.due_date >= :from", "from", f.from())
                .and(f.to() != null, "ch.due_date <= :to", "to", f.to());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id" + sql.where(),
                sql.params(), Long.class);
        List<ChargeRow> rows = jdbc.query(CHARGE_SELECT + sql.where() + page.orderBy(CHARGE_SORT, "ch.due_date DESC, ch.id DESC") + page.limit(),
                sql.params(), (rs, i) -> chargeRow(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public ChargeDetail charge(Long id) {
        var rows = jdbc.query(CHARGE_SELECT.replace("SELECT ch.id,", "SELECT ch.amount, ch.discount, ch.subtotal, ch.tax, ch.void_reason, ch.voided_at, ch.id,")
                + " WHERE ch.id = :id", Map.of("id", id), (rs, i) -> new ChargeDetail(chargeRow(rs), rs.getBigDecimal("amount"),
                rs.getBigDecimal("discount"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax"), allocationsForCharge(id), adjustments(id),
                rs.getString("void_reason"), instant(rs.getTimestamp("voided_at"))));
        if (rows.isEmpty()) {
            throw new NotFoundException("Cargo no encontrado");
        }
        return rows.get(0);
    }

    static ChargeRow chargeRow(ResultSet rs) throws SQLException {
        return new ChargeRow(rs.getLong("id"), rs.getString("folio"), rs.getLong("customer_id"), rs.getString("trade_name"),
                (Long) rs.getObject("subscription_id"), rs.getString("sub_code"), rs.getString("product_code"), rs.getString("color"),
                rs.getString("type"), rs.getString("description"), date(rs.getDate("period_start")), date(rs.getDate("period_end")),
                date(rs.getDate("issue_date")), date(rs.getDate("due_date")), rs.getBigDecimal("total"), rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("adjusted_amount"), rs.getBigDecimal("balance"), rs.getString("status"),
                "OPEN".equals(rs.getString("status")) || "PARTIAL".equals(rs.getString("status")) ? rs.getInt("days_overdue") : 0,
                rs.getString("currency"));
    }

    private static final String ALLOCATION_SELECT = """
            SELECT a.id, a.payment_id, p.folio AS payment_folio, a.charge_id, ch.folio AS charge_folio, ch.description, a.amount,
                   a.created_at, a.reversed_at, a.reversal_reason
            FROM payment_allocations a JOIN payments p ON p.id = a.payment_id JOIN charges ch ON ch.id = a.charge_id""";

    private List<AllocationRow> allocationsForCharge(Long chargeId) {
        return jdbc.query(ALLOCATION_SELECT + " WHERE a.charge_id = :id ORDER BY a.id", Map.of("id", chargeId), (rs, i) -> allocation(rs));
    }

    private List<AllocationRow> allocationsForPayment(Long paymentId) {
        return jdbc.query(ALLOCATION_SELECT + " WHERE a.payment_id = :id ORDER BY a.id", Map.of("id", paymentId), (rs, i) -> allocation(rs));
    }

    private static AllocationRow allocation(ResultSet rs) throws SQLException {
        return new AllocationRow(rs.getLong("id"), rs.getLong("payment_id"), rs.getString("payment_folio"), rs.getLong("charge_id"),
                rs.getString("charge_folio"), rs.getString("description"), rs.getBigDecimal("amount"), rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("reversed_at")), rs.getString("reversal_reason"));
    }

    private List<AdjustmentRow> adjustments(Long chargeId) {
        return jdbc.query("""
                SELECT a.id, a.amount, a.reason, u.first_name || ' ' || u.last_name, a.created_at FROM charge_adjustments a
                LEFT JOIN users u ON u.id = a.created_by WHERE a.charge_id = :id ORDER BY a.id""", Map.of("id", chargeId),
                (rs, i) -> new AdjustmentRow(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
    }

    static final String PAYMENT_SELECT = """
            SELECT p.id, p.folio, p.customer_id, c.trade_name, c.code AS customer_code, p.received_on, p.amount, pb.allocated, pb.refunded,
                   pb.unapplied, p.currency, p.method, p.reference, p.status, u.first_name || ' ' || u.last_name AS registered_by, p.created_at
            FROM payments p JOIN payment_balances pb ON pb.payment_id = p.id JOIN customers c ON c.id = p.customer_id
            LEFT JOIN users u ON u.id = p.registered_by""";

    private static final Map<String, String> PAYMENT_SORT = Map.of("received", "p.received_on", "amount", "p.amount",
            "folio", "p.folio", "customer", "lower(c.trade_name)", "created", "p.created_at");

    @Transactional(readOnly = true)
    public PageResponse<PaymentRow> payments(PaymentFilters f, PageQuery page) {
        Sql sql = paymentFilters(f);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payments p JOIN payment_balances pb ON pb.payment_id = p.id "
                + "JOIN customers c ON c.id = p.customer_id" + sql.where(), sql.params(), Long.class);
        List<PaymentRow> rows = jdbc.query(PAYMENT_SELECT + sql.where() + page.orderBy(PAYMENT_SORT, "p.received_on DESC, p.id DESC") + page.limit(),
                sql.params(), (rs, i) -> paymentRow(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    public List<PaymentRow> paymentsForExport(PaymentFilters f) {
        Sql sql = paymentFilters(f);
        return jdbc.query(PAYMENT_SELECT + sql.where() + " ORDER BY p.received_on DESC, p.id DESC LIMIT 20000", sql.params(), (rs, i) -> paymentRow(rs));
    }

    private static Sql paymentFilters(PaymentFilters f) {
        return new Sql()
                .and(Sql.has(f.q()), "(lower(p.folio) LIKE :q OR lower(c.trade_name) LIKE :q OR lower(COALESCE(p.reference, '')) LIKE :q)", "q",
                        Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(f.customerId() != null, "p.customer_id = :cid", "cid", f.customerId())
                .and(Sql.has(f.status()), "p.status = :st", "st", f.status())
                .and(Sql.has(f.method()), "p.method = :m", "m", f.method())
                .and(f.from() != null, "p.received_on >= :from", "from", f.from())
                .and(f.to() != null, "p.received_on <= :to", "to", f.to())
                .and(Boolean.TRUE.equals(f.withCredit()), "pb.unapplied > 0", "wc", null);
    }

    @Transactional(readOnly = true)
    public PaymentDetail payment(Long id) {
        var rows = jdbc.query(PAYMENT_SELECT.replace("SELECT p.id,", """
                SELECT p.notes, p.confirmed_at, cu.first_name || ' ' || cu.last_name AS confirmed_name, p.reconciled_at, p.rejected_at,
                       p.reject_reason, p.reversed_at, p.reversal_reason, p.id,""").replace("LEFT JOIN users u ON u.id = p.registered_by",
                "LEFT JOIN users u ON u.id = p.registered_by LEFT JOIN users cu ON cu.id = p.confirmed_by") + " WHERE p.id = :id",
                Map.of("id", id), (rs, i) -> new PaymentDetail(paymentRow(rs), rs.getString("notes"), allocationsForPayment(id), refunds(id),
                        instant(rs.getTimestamp("confirmed_at")), rs.getString("confirmed_name"), instant(rs.getTimestamp("reconciled_at")),
                        instant(rs.getTimestamp("rejected_at")), rs.getString("reject_reason"), instant(rs.getTimestamp("reversed_at")),
                        rs.getString("reversal_reason")));
        if (rows.isEmpty()) {
            throw new NotFoundException("Pago no encontrado");
        }
        return rows.get(0);
    }

    private List<RefundRow> refunds(Long paymentId) {
        return jdbc.query("""
                SELECT r.id, r.amount, r.refunded_on, r.method, r.reference, r.reason, u.first_name || ' ' || u.last_name, r.created_at
                FROM payment_refunds r LEFT JOIN users u ON u.id = r.created_by WHERE r.payment_id = :id ORDER BY r.id""",
                Map.of("id", paymentId), (rs, i) -> new RefundRow(rs.getLong(1), rs.getBigDecimal(2), rs.getDate(3).toLocalDate(),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()));
    }

    static PaymentRow paymentRow(ResultSet rs) throws SQLException {
        return new PaymentRow(rs.getLong("id"), rs.getString("folio"), rs.getLong("customer_id"), rs.getString("trade_name"),
                rs.getString("customer_code"), rs.getDate("received_on").toLocalDate(), rs.getBigDecimal("amount"), rs.getBigDecimal("allocated"),
                rs.getBigDecimal("refunded"), rs.getBigDecimal("unapplied"), rs.getString("currency"), rs.getString("method"),
                rs.getString("reference"), rs.getString("status"), rs.getString("registered_by"), rs.getTimestamp("created_at").toInstant());
    }

    static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

