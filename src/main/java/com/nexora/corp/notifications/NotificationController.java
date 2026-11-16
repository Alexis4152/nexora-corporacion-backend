package com.nexora.corp.notifications;

import com.nexora.corp.notifications.NotificationService.NotificationRow;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.api.PageQuery;
import com.nexora.corp.shared.api.PageResponse;
import com.nexora.corp.shared.api.Sql;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;
    private final DeliveryService deliveries;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    @GetMapping("/notifications")
    public ApiResponse<PageResponse<NotificationRow>> mine(@RequestParam(required = false) String category,
                                                           @RequestParam(required = false) Boolean unread,
                                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(notifications.mine(category, unread, PageQuery.of(page, size, null)));
    }

    @GetMapping("/notifications/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(notifications.summary());
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id) {
        notifications.markRead(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<Integer> readAll() {
        return ApiResponse.ok(notifications.markAllRead());
    }

    // ── Preferencias por rol ───────────────────────────────────────────────────────────

    public record Preference(Long roleId, String roleCode, String roleName, String category, boolean inApp, boolean email) {
    }

    @GetMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public ApiResponse<List<Preference>> preferences() {
        return ApiResponse.ok(jdbc.query("""
                SELECT r.id, r.code, r.name, c.category, COALESCE(np.in_app, FALSE), COALESCE(np.email, FALSE)
                FROM roles r CROSS JOIN (VALUES ('COLLECTION'), ('PROSPECTS'), ('SUBSCRIPTIONS'), ('PAYMENTS'), ('TRIALS'), ('CANCELLATIONS'),
                     ('SUPPORT'), ('SECURITY'), ('ADMIN')) AS c(category)
                LEFT JOIN notification_preferences np ON np.role_id = r.id AND np.category = c.category
                ORDER BY r.system DESC, r.name, c.category""", Map.of(),
                (rs, i) -> new Preference(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6))));
    }

    @PutMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public ApiResponse<List<Preference>> savePreferences(@RequestBody List<Preference> prefs) {
        for (Preference p : prefs) {
            try {
                NotificationService.Category.valueOf(p.category());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Categoría no válida");
            }
            jdbc.update("""
                    INSERT INTO notification_preferences (role_id, category, in_app, email) VALUES (:r, :c, :i, :e)
                    ON CONFLICT (role_id, category) DO UPDATE SET in_app = EXCLUDED.in_app, email = EXCLUDED.email""",
                    Map.of("r", p.roleId(), "c", p.category(), "i", p.inApp(), "e", p.email()));
        }
        audit.record(AuditEntry.success("settings", "NOTIFICATION_PREFERENCES_UPDATED", "Settings", null).detail(prefs.size() + " preferencias").build());
        return preferences();
    }

    // ── Plantillas y bandeja de salida ─────────────────────────────────────────────────

    public record Template(String code, String name, String channel, String subject, String body, boolean active) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 20000) String body, boolean active) {
    }

    @GetMapping("/notification-templates")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public ApiResponse<List<Template>> templates() {
        return ApiResponse.ok(jdbc.query("SELECT code, name, channel, subject, body, active FROM notification_templates ORDER BY code", Map.of(),
                (rs, i) -> new Template(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6))));
    }

    @PutMapping("/notification-templates/{code}")
    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public ApiResponse<List<Template>> saveTemplate(@PathVariable String code, @Valid @RequestBody TemplateRequest r) {
        String lower = r.body().toLowerCase();
        if (lower.contains("<script") || lower.contains("javascript:") || lower.matches("(?s).*\\son[a-z]+\\s*=.*")) {
            throw new BusinessException("La plantilla no puede contener scripts ni atributos de eventos");
        }
        int n = jdbc.update("UPDATE notification_templates SET subject = :s, body = :b, active = :a, updated_at = now(), version = version + 1 WHERE code = :c",
                Map.of("s", r.subject(), "b", r.body(), "a", r.active(), "c", code));
        if (n == 0) {
            throw new BusinessException("Plantilla no encontrada");
        }
        audit.record(AuditEntry.success("settings", "TEMPLATE_UPDATED", "Template", code).build());
        return templates();
    }

    @PostMapping("/notification-templates/{code}/preview")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public ApiResponse<Map<String, String>> preview(@PathVariable String code, @RequestBody TemplateRequest r) {
        Map<String, String> sample = Map.of("cliente", "Abarrotes La Esperanza", "producto", "NEXORA POS", "folio", "CG-000123",
                "importe", "$580.84", "vencimiento", "15/11/2026", "dias", "5");
        return ApiResponse.ok(Map.of("subject", deliveries.render(r.subject(), sample, false), "body", deliveries.render(r.body(), sample, true)));
    }

    public record Delivery(Long id, String channel, String templateCode, String recipient, String subject, String status, int attempts,
                           String lastError, String sentAt, String createdAt, Long customerId, String customerName) {
    }

    @GetMapping("/notification-deliveries")
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage') or hasAuthority('collections.read')")
    public ApiResponse<PageResponse<Delivery>> deliveriesList(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) Long customerId,
                                                              @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        PageQuery q = PageQuery.of(page, size, null);
        Sql sql = new Sql().and(Sql.has(status), "d.status = :st", "st", status).and(customerId != null, "d.customer_id = :c", "c", customerId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries d" + sql.where(), sql.params(), Long.class);
        List<Delivery> rows = jdbc.query("""
                SELECT d.id, d.channel, d.template_code, d.recipient, d.subject, d.status, d.attempts, d.last_error, d.sent_at, d.created_at,
                       d.customer_id, c.trade_name FROM notification_deliveries d LEFT JOIN customers c ON c.id = d.customer_id"""
                + sql.where() + " ORDER BY d.id DESC" + q.limit(), sql.params(), (rs, i) -> new Delivery(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8),
                rs.getString(9), rs.getString(10), (Long) rs.getObject(11), rs.getString(12)));
        return ApiResponse.ok(PageResponse.of(rows, q, total == null ? 0 : total));
    }

    @PostMapping("/notification-deliveries/{id}/retry")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Void> retry(@PathVariable Long id) {
        deliveries.retry(id);
        return ApiResponse.ok(null, "Reintento programado");
    }
}
