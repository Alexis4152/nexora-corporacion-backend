package com.nexora.corp.support;

import com.nexora.corp.notifications.NotificationService;
import com.nexora.corp.notifications.NotificationService.Alert;
import com.nexora.corp.notifications.NotificationService.Category;
import com.nexora.corp.notifications.NotificationService.Severity;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Seguimiento de incidencias con tiempos de atención (primera respuesta y resolución). */
@Service
@RequiredArgsConstructor
public class TicketService {

    public enum Status { OPEN, IN_REVIEW, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED }

    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.OPEN, EnumSet.of(Status.IN_REVIEW, Status.IN_PROGRESS, Status.WAITING_CUSTOMER, Status.RESOLVED, Status.CLOSED),
            Status.IN_REVIEW, EnumSet.of(Status.IN_PROGRESS, Status.WAITING_CUSTOMER, Status.RESOLVED, Status.CLOSED),
            Status.IN_PROGRESS, EnumSet.of(Status.IN_REVIEW, Status.WAITING_CUSTOMER, Status.RESOLVED),
            Status.WAITING_CUSTOMER, EnumSet.of(Status.IN_PROGRESS, Status.RESOLVED, Status.CLOSED),
            Status.RESOLVED, EnumSet.of(Status.CLOSED, Status.IN_PROGRESS),
            Status.CLOSED, EnumSet.of(Status.IN_PROGRESS));

    public record TicketRequest(@NotNull(message = "Elige el cliente") Long customerId, Long productId,
                                @NotBlank(message = "Escribe el asunto") @Size(max = 200) String subject,
                                @NotBlank(message = "Describe la incidencia") @Size(max = 5000) String description,
                                @NotBlank @Pattern(regexp = "^(INCIDENT|QUESTION|REQUEST|BILLING|TRAINING|BUG)$") String category,
                                @NotBlank @Pattern(regexp = "^(LOW|MEDIUM|HIGH|URGENT)$") String priority,
                                Long assigneeId) {
    }

    public record StatusRequest(@NotNull Status status, @Size(max = 3000) String resolution) {
    }

    public record CommentRequest(@NotBlank(message = "Escribe el comentario") @Size(max = 5000) String body) {
    }

    public record TicketRow(Long id, String folio, Long customerId, String customerName, Long productId, String productCode,
                            String productColor, String subject, String category, String priority, String status, Long assigneeId,
                            String assigneeName, Instant createdAt, Instant firstResponseAt, Instant resolvedAt, Instant updatedAt,
                            long comments) {
    }

    public record CommentRow(Long id, String body, boolean system, String authorName, Long authorId, Instant createdAt) {
    }

    public record TicketDetail(TicketRow ticket, String description, String resolution, Instant closedAt, List<CommentRow> comments, int version) {
    }

    public record Filters(String q, String status, String priority, Long assigneeId, Long customerId, Long productId, boolean openOnly, boolean mine) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Folios folios;
    private final AuditService audit;
    private final NotificationService notifications;

    private static final String SELECT = """
            SELECT t.id, t.folio, t.customer_id, c.trade_name, t.product_id, p.code AS product_code, p.color, t.subject, t.category,
                   t.priority, t.status, t.assignee_id, u.first_name || ' ' || u.last_name AS assignee, t.created_at, t.first_response_at,
                   t.resolved_at, t.updated_at, (SELECT COUNT(*) FROM support_comments sc WHERE sc.ticket_id = t.id AND NOT sc.system) AS comments,
                   t.description, t.resolution, t.closed_at, t.version
            FROM support_tickets t JOIN customers c ON c.id = t.customer_id LEFT JOIN products p ON p.id = t.product_id
            LEFT JOIN users u ON u.id = t.assignee_id""";

    private static final Map<String, String> SORT = Map.of("created", "t.created_at", "updated", "t.updated_at", "priority",
            "CASE t.priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END");

    @Transactional(readOnly = true)
    public PageResponse<TicketRow> list(Filters f, PageQuery page) {
        Sql sql = new Sql()
                .and(Sql.has(f.q()), "(lower(t.subject) LIKE :q OR lower(t.folio) LIKE :q OR lower(c.trade_name) LIKE :q)", "q", Sql.has(f.q()) ? Sql.like(f.q()) : null)
                .and(Sql.has(f.status()), "t.status = :st", "st", f.status())
                .and(Sql.has(f.priority()), "t.priority = :pr", "pr", f.priority())
                .and(f.assigneeId() != null, "t.assignee_id = :as", "as", f.assigneeId())
                .and(f.customerId() != null, "t.customer_id = :cid", "cid", f.customerId())
                .and(f.productId() != null, "t.product_id = :pid", "pid", f.productId())
                .and(f.openOnly(), "t.status NOT IN ('RESOLVED', 'CLOSED')", "oo", null)
                .and(f.mine(), "t.assignee_id = :me", "me", SecurityUtils.currentUserId());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_tickets t JOIN customers c ON c.id = t.customer_id" + sql.where(), sql.params(), Long.class);
        List<TicketRow> rows = jdbc.query(SELECT + sql.where() + page.orderBy(SORT,
                "t.status IN ('RESOLVED', 'CLOSED'), CASE t.priority WHEN 'URGENT' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, t.created_at DESC")
                + page.limit(), sql.params(), (rs, i) -> row(rs));
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public TicketDetail get(Long id) {
        List<TicketDetail> found = jdbc.query(SELECT + " WHERE t.id = :id", Map.of("id", id), (rs, i) -> {
            Timestamp closed = rs.getTimestamp("closed_at");
            return new TicketDetail(row(rs), rs.getString("description"), rs.getString("resolution"), closed == null ? null : closed.toInstant(),
                    comments(id), rs.getInt("version"));
        });
        if (found.isEmpty()) {
            throw new NotFoundException("Ticket no encontrado");
        }
        return found.get(0);
    }

    @Transactional
    public TicketDetail create(TicketRequest r) {
        assertCustomer(r.customerId(), r.productId());
        String folio = folios.next("ticket_folio_seq", "TK", 5);
        Long id = jdbc.queryForObject("""
                INSERT INTO support_tickets (folio, customer_id, product_id, subject, description, category, priority, assignee_id, created_by)
                VALUES (:f, :c, :p, :s, :d, :cat, :pr, :a, :by) RETURNING id""", new MapSqlParameterSource().addValue("f", folio)
                .addValue("c", r.customerId()).addValue("p", r.productId()).addValue("s", r.subject().trim()).addValue("d", r.description().trim())
                .addValue("cat", r.category()).addValue("pr", r.priority()).addValue("a", r.assigneeId()).addValue("by", SecurityUtils.currentUserId()), Long.class);
        audit.record(AuditEntry.success("support", "TICKET_CREATED", "Ticket", id).after(Map.of("folio", folio, "priority", r.priority())).build());
        Severity sev = "URGENT".equals(r.priority()) || "HIGH".equals(r.priority()) ? Severity.WARNING : Severity.INFO;
        notifications.toCategory(new Alert(Category.SUPPORT, "TICKET_CREATED", sev, "Nuevo ticket " + folio + ": " + r.subject(),
                "Prioridad " + r.priority(), "/soporte/" + id, "Ticket", id, "TICKET:" + id), SecurityUtils.currentUserId());
        if (r.assigneeId() != null) {
            notifyAssignee(id, folio, r.subject(), r.assigneeId());
        }
        return get(id);
    }

    @Transactional
    public TicketDetail update(Long id, TicketRequest r) {
        get(id);
        assertCustomer(r.customerId(), r.productId());
        jdbc.update("""
                UPDATE support_tickets SET product_id = :p, subject = :s, description = :d, category = :cat, priority = :pr,
                  updated_at = now(), version = version + 1 WHERE id = :id""", new MapSqlParameterSource().addValue("p", r.productId())
                .addValue("s", r.subject().trim()).addValue("d", r.description().trim()).addValue("cat", r.category()).addValue("pr", r.priority())
                .addValue("id", id));
        return get(id);
    }

    @Transactional
    public TicketDetail changeStatus(Long id, StatusRequest r) {
        TicketDetail t = get(id);
        Status from = Status.valueOf(t.ticket().status());
        if (!TRANSITIONS.get(from).contains(r.status())) {
            throw new BusinessException("No se puede pasar de " + label(from) + " a " + label(r.status()));
        }
        boolean resolving = r.status() == Status.RESOLVED || r.status() == Status.CLOSED;
        String resolution = r.resolution() != null && !r.resolution().isBlank() ? r.resolution().trim() : t.resolution();
        if (resolving && (resolution == null || resolution.isBlank())) {
            throw new BusinessException("Registra la solución antes de resolver o cerrar");
        }
        jdbc.update("""
                UPDATE support_tickets SET status = :st, resolution = :res,
                  first_response_at = COALESCE(first_response_at, CASE WHEN :st <> 'OPEN' THEN now() END),
                  resolved_at = CASE WHEN :st IN ('RESOLVED', 'CLOSED') THEN COALESCE(resolved_at, now()) ELSE NULL END,
                  closed_at = CASE WHEN :st = 'CLOSED' THEN now() ELSE NULL END, updated_at = now(), version = version + 1 WHERE id = :id""",
                new MapSqlParameterSource().addValue("st", r.status().name()).addValue("res", resolution).addValue("id", id));
        systemComment(id, "Estado: " + label(from) + " → " + label(r.status()));
        audit.record(AuditEntry.success("support", "TICKET_STATUS", "Ticket", id).before(Map.of("status", from.name()))
                .after(Map.of("status", r.status().name())).build());
        return get(id);
    }

    @Transactional
    public TicketDetail assign(Long id, Long userId) {
        TicketDetail t = get(id);
        if (userId != null) {
            Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id AND active", Map.of("id", userId), Integer.class);
            if (ok == null || ok == 0) {
                throw new BusinessException("El responsable no existe o está inactivo");
            }
        }
        jdbc.update("UPDATE support_tickets SET assignee_id = :a, updated_at = now(), version = version + 1 WHERE id = :id",
                new MapSqlParameterSource().addValue("a", userId).addValue("id", id));
        String name = userId == null ? "nadie" : jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM users WHERE id = :id", Map.of("id", userId), String.class);
        systemComment(id, "Asignado a " + name);
        if (userId != null && !userId.equals(SecurityUtils.currentUserId())) {
            notifyAssignee(id, t.ticket().folio(), t.ticket().subject(), userId);
        }
        return get(id);
    }

    @Transactional
    public TicketDetail comment(Long id, CommentRequest r) {
        get(id);
        jdbc.update("INSERT INTO support_comments (ticket_id, body, created_by) VALUES (:t, :b, :by)",
                Map.of("t", id, "b", r.body().trim(), "by", SecurityUtils.currentUserId()));
        jdbc.update("UPDATE support_tickets SET first_response_at = COALESCE(first_response_at, now()), updated_at = now() WHERE id = :id", Map.of("id", id));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> metrics(Long productId) {
        MapSqlParameterSource p = new MapSqlParameterSource("pid", productId);
        String scope = productId == null ? "" : " AND product_id = :pid";
        return jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CLOSED')) AS open,
                       COUNT(*) FILTER (WHERE status NOT IN ('RESOLVED', 'CLOSED') AND priority IN ('HIGH', 'URGENT')) AS urgent,
                       COUNT(*) FILTER (WHERE created_at > now() - interval '30 days') AS last30,
                       ROUND(AVG(EXTRACT(EPOCH FROM (first_response_at - created_at)) / 3600) FILTER (WHERE first_response_at IS NOT NULL
                             AND created_at > now() - interval '90 days')::numeric, 1) AS avg_first_response_hours,
                       ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600) FILTER (WHERE resolved_at IS NOT NULL
                             AND created_at > now() - interval '90 days')::numeric, 1) AS avg_resolution_hours
                FROM support_tickets WHERE 1 = 1""" + scope, p);
    }

    private void systemComment(Long ticketId, String body) {
        jdbc.update("INSERT INTO support_comments (ticket_id, body, system, created_by) VALUES (:t, :b, TRUE, :by)",
                new MapSqlParameterSource().addValue("t", ticketId).addValue("b", body).addValue("by", SecurityUtils.currentUserId()));
    }

    private void notifyAssignee(Long id, String folio, String subject, Long userId) {
        notifications.toUser(userId, new Alert(Category.SUPPORT, "TICKET_ASSIGNED", Severity.INFO, "Te asignaron el ticket " + folio,
                subject, "/soporte/" + id, "Ticket", id, "TICKET_ASSIGNED:" + id + ":" + userId + ":" + System.currentTimeMillis() / 60_000));
    }

    private void assertCustomer(Long customerId, Long productId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE id = :id", Map.of("id", customerId), Integer.class);
        if (c == null || c == 0) {
            throw new NotFoundException("Cliente no encontrado");
        }
        if (productId != null) {
            Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = :id", Map.of("id", productId), Integer.class);
            if (p == null || p == 0) {
                throw new BusinessException("Producto no válido");
            }
        }
    }

    private List<CommentRow> comments(Long id) {
        return jdbc.query("""
                SELECT sc.id, sc.body, sc.system, u.first_name || ' ' || u.last_name, sc.created_by, sc.created_at FROM support_comments sc
                LEFT JOIN users u ON u.id = sc.created_by WHERE sc.ticket_id = :id ORDER BY sc.created_at""", Map.of("id", id),
                (rs, i) -> new CommentRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), (Long) rs.getObject(5),
                        rs.getTimestamp(6).toInstant()));
    }

    private static TicketRow row(ResultSet rs) throws SQLException {
        Timestamp fr = rs.getTimestamp("first_response_at");
        Timestamp rv = rs.getTimestamp("resolved_at");
        return new TicketRow(rs.getLong("id"), rs.getString("folio"), rs.getLong("customer_id"), rs.getString("trade_name"),
                (Long) rs.getObject("product_id"), rs.getString("product_code"), rs.getString("color"), rs.getString("subject"),
                rs.getString("category"), rs.getString("priority"), rs.getString("status"), (Long) rs.getObject("assignee_id"),
                rs.getString("assignee"), rs.getTimestamp("created_at").toInstant(), fr == null ? null : fr.toInstant(),
                rv == null ? null : rv.toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("comments"));
    }

    static String label(Status s) {
        return switch (s) {
            case OPEN -> "Abierto";
            case IN_REVIEW -> "En revisión";
            case IN_PROGRESS -> "En proceso";
            case WAITING_CUSTOMER -> "En espera del cliente";
            case RESOLVED -> "Resuelto";
            case CLOSED -> "Cerrado";
        };
    }
}

