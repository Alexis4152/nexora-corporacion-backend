package com.nexora.corp.notifications;

import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Centro de alertas. Cada alerta representa un evento real del sistema; {@code dedupe_key} (único por usuario)
 * hace que la generación sea idempotente aunque la tarea corra muchas veces. Los destinatarios salen de las
 * preferencias por rol ({@code notification_preferences}).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public enum Category { COLLECTION, PROSPECTS, SUBSCRIPTIONS, PAYMENTS, TRIALS, CANCELLATIONS, SUPPORT, SECURITY, ADMIN }

    public enum Severity { INFO, SUCCESS, WARNING, CRITICAL }

    public record Alert(Category category, String type, Severity severity, String title, String body, String link,
                        String entityType, Long entityId, String dedupeKey) {
    }

    public record NotificationRow(Long id, String category, String type, String severity, String title, String body, String link,
                                  String entityType, Long entityId, Instant readAt, Instant createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** A todos los usuarios activos cuyo rol recibe esa categoría (y por correo a quien lo tenga activado). */
    @Transactional
    public int toCategory(Alert a, Long excludeUserId) {
        List<Map<String, Object>> recipients = jdbc.queryForList("""
                SELECT u.id, u.email, bool_or(np.email) AS by_email FROM users u
                JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id AND r.active
                JOIN notification_preferences np ON np.role_id = r.id AND np.category = :cat
                WHERE u.active AND (np.in_app OR np.email) GROUP BY u.id, u.email""", Map.of("cat", a.category().name()));
        int n = 0;
        for (Map<String, Object> r : recipients) {
            Long userId = ((Number) r.get("id")).longValue();
            if (userId.equals(excludeUserId)) {
                continue;
            }
            n += insert(userId, a);
            if (Boolean.TRUE.equals(r.get("by_email"))) {
                enqueueEmail((String) r.get("email"), a);
            }
        }
        return n;
    }

    @Transactional
    public int toUser(Long userId, Alert a) {
        return insert(userId, a);
    }

    private int insert(Long userId, Alert a) {
        return jdbc.update("""
                INSERT INTO notifications (user_id, category, type, severity, title, body, link, entity_type, entity_id, dedupe_key)
                VALUES (:u, :c, :t, :s, :title, :body, :link, :et, :eid, :k) ON CONFLICT (user_id, dedupe_key) DO NOTHING""",
                new MapSqlParameterSource().addValue("u", userId).addValue("c", a.category().name()).addValue("t", a.type())
                        .addValue("s", a.severity().name()).addValue("title", cut(a.title(), 200)).addValue("body", cut(a.body(), 600))
                        .addValue("link", a.link()).addValue("et", a.entityType()).addValue("eid", a.entityId()).addValue("k", cut(a.dedupeKey(), 160)));
    }

    private void enqueueEmail(String to, Alert a) {
        String html = "<div style=\"font-family:Arial,sans-serif;max-width:560px;margin:auto;color:#0f172a\"><h3>"
                + HtmlUtils.htmlEscape(a.title()) + "</h3><p>" + HtmlUtils.htmlEscape(a.body() == null ? "" : a.body())
                + "</p><p style=\"color:#64748b;font-size:12px\">Nexora Corporación · alerta automática</p></div>";
        jdbc.update("""
                INSERT INTO notification_deliveries (channel, recipient, subject, body, entity_type, entity_id, dedupe_key)
                VALUES ('EMAIL', :to, :sub, :body, :et, :eid, :k) ON CONFLICT (dedupe_key) DO NOTHING""",
                new MapSqlParameterSource().addValue("to", to).addValue("sub", cut("[Nexora] " + a.title(), 200)).addValue("body", html)
                        .addValue("et", a.entityType()).addValue("eid", a.entityId()).addValue("k", cut("USR:" + to + ":" + a.dedupeKey(), 160)));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationRow> mine(String category, Boolean unread, PageQuery page) {
        Long me = SecurityUtils.requirePrincipal().userId();
        Sql sql = new Sql().and("user_id = :me").param("me", me)
                .and(Sql.has(category), "category = :cat", "cat", category)
                .and(Boolean.TRUE.equals(unread), "read_at IS NULL", "ur", null);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications" + sql.where(), sql.params(), Long.class);
        List<NotificationRow> rows = jdbc.query("SELECT * FROM notifications" + sql.where() + " ORDER BY created_at DESC, id DESC" + page.limit(),
                sql.params(), (rs, i) -> {
                    Timestamp read = rs.getTimestamp("read_at");
                    return new NotificationRow(rs.getLong("id"), rs.getString("category"), rs.getString("type"), rs.getString("severity"),
                            rs.getString("title"), rs.getString("body"), rs.getString("link"), rs.getString("entity_type"),
                            (Long) rs.getObject("entity_id"), read == null ? null : read.toInstant(), rs.getTimestamp("created_at").toInstant());
                });
        return PageResponse.of(rows, page, total == null ? 0 : total);
    }

    public Map<String, Object> summary() {
        Long me = SecurityUtils.requirePrincipal().userId();
        Long unread = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = :me AND read_at IS NULL", Map.of("me", me), Long.class);
        Long critical = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = :me AND read_at IS NULL AND severity = 'CRITICAL'",
                Map.of("me", me), Long.class);
        return Map.of("unread", unread == null ? 0 : unread, "critical", critical == null ? 0 : critical);
    }

    @Transactional
    public void markRead(Long id) {
        int n = jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :me",
                Map.of("id", id, "me", SecurityUtils.requirePrincipal().userId()));
        if (n == 0) {
            throw new NotFoundException("Notificación no encontrada");
        }
    }

    @Transactional
    public int markAllRead() {
        return jdbc.update("UPDATE notifications SET read_at = now() WHERE user_id = :me AND read_at IS NULL",
                Map.of("me", SecurityUtils.requirePrincipal().userId()));
    }

    @Transactional
    public void purgeOld() {
        jdbc.update("DELETE FROM notifications WHERE read_at IS NOT NULL AND created_at < now() - interval '180 days'", Map.of());
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

