package com.nexora.corp.expenses;

import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Money;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Gastos corporativos (empresa o producto). No se borran: se anulan con motivo. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    public record ExpenseRequest(@NotBlank(message = "Escribe el concepto") @Size(max = 200) String concept,
                                 @NotNull @DecimalMin(value = "0.01", message = "Importe mayor a cero") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                 @NotNull(message = "Indica la fecha") LocalDate expenseDate,
                                 @NotNull(message = "Elige la categoría") Long categoryId,
                                 Long productId,
                                 @Size(max = 150) String supplier,
                                 @NotBlank @Pattern(regexp = "^(ONE_TIME|MONTHLY|QUARTERLY|ANNUAL)$") String periodicity,
                                 @Size(max = 1000) String notes,
                                 Integer version) {
    }

    public record ExpenseRow(Long id, String concept, BigDecimal amount, String currency, LocalDate expenseDate, Long categoryId,
                             String categoryName, boolean acquisitionCost, Long productId, String productCode, String productColor,
                             String supplier, String periodicity, String notes, Instant voidedAt, String voidReason, int version,
                             String createdBy) {
    }

    public record CategoryRow(Long id, String name, boolean acquisitionCost, boolean active, long expenses) {
    }

    public record CategoryRequest(@NotBlank @Size(max = 60) String name, boolean acquisitionCost) {
    }

    public record Filters(String q, LocalDate from, LocalDate to, Long categoryId, Long productId, boolean includeVoided, Boolean companyOnly) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    private static final String SELECT = """
            SELECT e.*, c.name AS category_name, c.acquisition_cost, p.code AS product_code, p.color AS product_color,
                   u.first_name || ' ' || u.last_name AS created_name
            FROM expenses e JOIN expense_categories c ON c.id = e.category_id LEFT JOIN products p ON p.id = e.product_id
            LEFT JOIN users u ON u.id = e.created_by""";

    private static final Map<String, String> SORT = Map.of("date", "e.expense_date", "amount", "e.amount", "concept", "lower(e.concept)");

    @Transactional(readOnly = true)
    public Map<String, Object> list(Filters f, PageQuery page) {
        Sql sql = filters(f);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM expenses e" + sql.where(), sql.params(), Long.class);
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(e.amount), 0) FROM expenses e" + sql.where() + " AND e.voided_at IS NULL",
                sql.params(), BigDecimal.class);
        List<ExpenseRow> rows = jdbc.query(SELECT + sql.where() + page.orderBy(SORT, "e.expense_date DESC, e.id DESC") + page.limit(),
                sql.params(), (rs, i) -> {
                    Timestamp v = rs.getTimestamp("voided_at");
                    return new ExpenseRow(rs.getLong("id"), rs.getString("concept"), rs.getBigDecimal("amount"), rs.getString("currency"),
                            rs.getDate("expense_date").toLocalDate(), rs.getLong("category_id"), rs.getString("category_name"),
                            rs.getBoolean("acquisition_cost"), (Long) rs.getObject("product_id"), rs.getString("product_code"),
                            rs.getString("product_color"), rs.getString("supplier"), rs.getString("periodicity"), rs.getString("notes"),
                            v == null ? null : v.toInstant(), rs.getString("void_reason"), rs.getInt("version"), rs.getString("created_name"));
                });
        List<Map<String, Object>> byCategory = jdbc.queryForList("SELECT c.name AS label, SUM(e.amount) AS value FROM expenses e "
                + "JOIN expense_categories c ON c.id = e.category_id" + sql.where() + " AND e.voided_at IS NULL GROUP BY c.name ORDER BY 2 DESC", sql.params());
        return Map.of("page", PageResponse.of(rows, page, total == null ? 0 : total), "total", sum, "byCategory", byCategory);
    }

    private static Sql filters(Filters f) {
        return new Sql()
                .and(Sql.has(f.q()), "(lower(e.concept) LIKE :q OR lower(COALESCE(e.supplier, '')) LIKE :q)", "q", Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(f.from() != null, "e.expense_date >= :from", "from", f.from())
                .and(f.to() != null, "e.expense_date <= :to", "to", f.to())
                .and(f.categoryId() != null, "e.category_id = :cat", "cat", f.categoryId())
                .and(f.productId() != null, "e.product_id = :pid", "pid", f.productId())
                .and(Boolean.TRUE.equals(f.companyOnly()), "e.product_id IS NULL", "co", null)
                .and(!f.includeVoided(), "e.voided_at IS NULL", "iv", null);
    }

    @Transactional
    public Long create(ExpenseRequest r) {
        validate(r);
        Long id = jdbc.queryForObject("""
                INSERT INTO expenses (concept, amount, currency, expense_date, category_id, product_id, supplier, periodicity, notes, created_by)
                VALUES (:c, :a, :cur, :d, :cat, :p, :s, :per, :n, :by) RETURNING id""", params(r).addValue("cur", settings.currency())
                .addValue("by", SecurityUtils.currentUserId()), Long.class);
        audit.record(AuditEntry.success("expenses", "EXPENSE_CREATED", "Expense", id).after(Map.of("concept", r.concept(), "amount", r.amount())).build());
        return id;
    }

    @Transactional
    public void update(Long id, ExpenseRequest r) {
        validate(r);
        int n = jdbc.update("""
                UPDATE expenses SET concept = :c, amount = :a, expense_date = :d, category_id = :cat, product_id = :p, supplier = :s,
                  periodicity = :per, notes = :n, updated_at = now(), updated_by = :by, version = version + 1
                WHERE id = :id AND voided_at IS NULL AND version = :v""", params(r).addValue("id", id)
                .addValue("by", SecurityUtils.currentUserId()).addValue("v", r.version() == null ? -1 : r.version()));
        if (n == 0) {
            throw new ConflictException("El gasto cambió, está anulado o no existe. Recarga e intenta de nuevo.");
        }
        audit.record(AuditEntry.success("expenses", "EXPENSE_UPDATED", "Expense", id).after(Map.of("concept", r.concept(), "amount", r.amount())).build());
    }

    @Transactional
    public void voidExpense(Long id, String reason) {
        int n = jdbc.update("UPDATE expenses SET voided_at = now(), voided_by = :by, void_reason = :r, version = version + 1 WHERE id = :id AND voided_at IS NULL",
                Map.of("by", SecurityUtils.currentUserId(), "r", reason, "id", id));
        if (n == 0) {
            throw new NotFoundException("Gasto no encontrado o ya anulado");
        }
        audit.record(AuditEntry.success("expenses", "EXPENSE_VOIDED", "Expense", id).detail(reason).build());
    }

    private void validate(ExpenseRequest r) {
        if (r.expenseDate().isAfter(settings.today().plusDays(31))) {
            throw new BusinessException("La fecha del gasto es demasiado lejana");
        }
        Integer cat = jdbc.queryForObject("SELECT COUNT(*) FROM expense_categories WHERE id = :id AND active", Map.of("id", r.categoryId()), Integer.class);
        if (cat == null || cat == 0) {
            throw new BusinessException("Categoría no válida");
        }
        if (r.productId() != null) {
            Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = :id", Map.of("id", r.productId()), Integer.class);
            if (p == null || p == 0) {
                throw new BusinessException("Producto no válido");
            }
        }
    }

    private static MapSqlParameterSource params(ExpenseRequest r) {
        return new MapSqlParameterSource().addValue("c", r.concept().trim()).addValue("a", Money.of(r.amount())).addValue("d", r.expenseDate())
                .addValue("cat", r.categoryId()).addValue("p", r.productId())
                .addValue("s", r.supplier() == null || r.supplier().isBlank() ? null : r.supplier().trim()).addValue("per", r.periodicity())
                .addValue("n", r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
    }

    public List<CategoryRow> categories() {
        return jdbc.query("""
                SELECT c.id, c.name, c.acquisition_cost, c.active, (SELECT COUNT(*) FROM expenses e WHERE e.category_id = c.id)
                FROM expense_categories c ORDER BY c.name""", Map.of(),
                (rs, i) -> new CategoryRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5)));
    }

    @Transactional
    public void saveCategory(Long id, CategoryRequest r) {
        if (id == null) {
            jdbc.update("INSERT INTO expense_categories (name, acquisition_cost) VALUES (:n, :a)", Map.of("n", r.name().trim(), "a", r.acquisitionCost()));
        } else {
            jdbc.update("UPDATE expense_categories SET name = :n, acquisition_cost = :a WHERE id = :id",
                    Map.of("n", r.name().trim(), "a", r.acquisitionCost(), "id", id));
        }
        audit.record(AuditEntry.success("expenses", "EXPENSE_CATEGORY_SAVED", "ExpenseCategory", id).detail(r.name()).build());
    }

    @Transactional
    public void toggleCategory(Long id, boolean active) {
        jdbc.update("UPDATE expense_categories SET active = :a WHERE id = :id", Map.of("a", active, "id", id));
    }
}

