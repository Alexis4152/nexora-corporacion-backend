package com.nexora.corp.notifications;

import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.mail.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bandeja de salida (outbox) de correos. Los recordatorios de cobranza se generan con plantillas editables y una
 * llave idempotente por cargo y etapa; el procesador envía con el SMTP real, reintenta con espera creciente y registra
 * cada intento. Sin SMTP configurado el envío se marca OMITIDO (SKIPPED) con el motivo: jamás se simula.
 * Es seguro con varias instancias: toma los pendientes con {@code FOR UPDATE SKIP LOCKED}.
 * <p>
 * WhatsApp: el canal existe en el modelo (columna {@code channel}); se habilitará al integrar un proveedor real.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_ATTEMPTS = 4;

    private final JdbcTemplate jdbc;
    private final MailService mail;
    private final SettingsService settings;
    private final AppProperties props;

    /** Recordatorios a clientes según la configuración de cobranza. */
    @Transactional
    public void collectionReminders(LocalDate today) {
        int before = settings.integer(Setting.REMINDER_BEFORE_DAYS);
        boolean onDue = settings.bool(Setting.REMINDER_ON_DUE);
        List<Integer> after = settings.intList(Setting.REMINDER_AFTER_DAYS);
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT ch.id, ch.folio, ch.balance, ch.due_date, ch.customer_id, ch.subscription_id, c.trade_name, p.name AS product,
                       COALESCE((SELECT cc.email FROM customer_contacts cc WHERE cc.customer_id = c.id AND cc.is_billing AND cc.email IS NOT NULL
                                 ORDER BY cc.id LIMIT 1), c.email) AS email,
                       nx_today() - ch.due_date AS days, s.grace_days
                FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id LEFT JOIN products p ON p.id = ch.product_id
                LEFT JOIN subscriptions s ON s.id = ch.subscription_id
                WHERE ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date BETWEEN ? AND ?""", today.minusDays(120), today.plusDays(before))) {
            int days = ((Number) r.get("days")).intValue();
            Long chargeId = ((Number) r.get("id")).longValue();
            Integer grace = r.get("grace_days") == null ? null : ((Number) r.get("grace_days")).intValue();
            String template = null;
            String key = null;
            if (days == -before && before > 0) {
                template = "PAYMENT_REMINDER";
                key = "REM:BEFORE:" + chargeId;
            } else if (days == 0 && onDue) {
                template = "PAYMENT_DUE_TODAY";
                key = "REM:DUE:" + chargeId;
            } else if (grace != null && days == grace && days > 0) {
                template = "GRACE_ENDING";
                key = "REM:GRACE:" + chargeId;
            } else if (after.contains(days)) {
                template = "PAYMENT_OVERDUE";
                key = "REM:AFTER:" + chargeId + ":" + days;
            } else if (days > 30 && days % 14 == 0) {
                template = "REGULARIZATION";
                key = "REM:REG:" + chargeId + ":" + days;
            }
            if (template != null) {
                enqueue(template, (String) r.get("email"), ((Number) r.get("customer_id")).longValue(), "Charge", chargeId, key, Map.of(
                        "cliente", String.valueOf(r.get("trade_name")), "producto", r.get("product") == null ? "tu servicio" : String.valueOf(r.get("product")),
                        "folio", String.valueOf(r.get("folio")), "importe", money((BigDecimal) r.get("balance")),
                        "vencimiento", ((java.sql.Date) r.get("due_date")).toLocalDate().format(DAY), "dias", String.valueOf(Math.max(days, 0))));
            }
        }
    }

    /** Aviso al cliente cuando su suscripción se suspende (solo si los correos a clientes están activos). */
    public void suspensionNotice(Long subscriptionId) {
        if (!settings.bool(Setting.CUSTOMER_EMAILS)) {
            return;
        }
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT s.customer_id, c.trade_name, c.email, p.name, (SELECT COALESCE(SUM(balance), 0) FROM charge_balances b
                WHERE b.subscription_id = s.id AND b.status IN ('OPEN', 'PARTIAL')) AS balance
                FROM subscriptions s JOIN customers c ON c.id = s.customer_id JOIN products p ON p.id = s.product_id WHERE s.id = ?""", subscriptionId);
        enqueue("SUSPENSION_NOTICE", (String) r.get("email"), ((Number) r.get("customer_id")).longValue(), "Subscription", subscriptionId,
                "SUSP:" + subscriptionId + ":" + System.currentTimeMillis() / 86_400_000, Map.of("cliente", String.valueOf(r.get("trade_name")),
                        "producto", String.valueOf(r.get("name")), "importe", money((BigDecimal) r.get("balance")), "folio", "", "vencimiento", "", "dias", ""));
    }

    void enqueue(String templateCode, String to, Long customerId, String entityType, Long entityId, String key, Map<String, String> vars) {
        List<Map<String, Object>> tpl = jdbc.queryForList("SELECT subject, body FROM notification_templates WHERE code = ? AND active", templateCode);
        if (tpl.isEmpty() || to == null || to.isBlank()) {
            return;
        }
        String subject = render((String) tpl.get(0).get("subject"), vars, false);
        String body = render((String) tpl.get(0).get("body"), vars, true);
        jdbc.update("""
                INSERT INTO notification_deliveries (channel, template_code, recipient, subject, body, customer_id, entity_type, entity_id, dedupe_key)
                VALUES ('EMAIL', ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (dedupe_key) DO NOTHING""",
                templateCode, to, subject.length() > 200 ? subject.substring(0, 200) : subject, body, customerId, entityType, entityId, key);
    }

    /** Sustituye {{variable}}; en HTML los valores se escapan (un nombre de cliente nunca inyecta marcado). */
    public String render(String template, Map<String, String> vars, boolean html) {
        Map<String, String> all = new java.util.HashMap<>(vars);
        all.putIfAbsent("empresa", settings.string(Setting.COMPANY_NAME));
        String out = template;
        for (var e : all.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{{" + e.getKey() + "}}", html ? HtmlUtils.htmlEscape(v) : v);
        }
        return out;
    }

    /** Procesa la bandeja cada minuto. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void process() {
        if (!props.security().jobsEnabled()) {
            return;
        }
        for (int i = 0; i < 20; i++) {
            if (!processOne()) {
                return;
            }
        }
    }

    @Transactional
    public boolean processOne() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, recipient, subject, body, attempts FROM notification_deliveries
                WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED""");
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> r = rows.get(0);
        long id = ((Number) r.get("id")).longValue();
        int attempts = ((Number) r.get("attempts")).intValue() + 1;
        MailService.Result result = mail.send((String) r.get("recipient"), (String) r.get("subject"), (String) r.get("body"));
        if (result.sent()) {
            jdbc.update("UPDATE notification_deliveries SET status = 'SENT', attempts = ?, sent_at = now(), last_error = NULL WHERE id = ?", attempts, id);
        } else if (!result.configured()) {
            jdbc.update("UPDATE notification_deliveries SET status = 'SKIPPED', attempts = ?, last_error = ? WHERE id = ?", attempts,
                    "No se envió: " + result.error(), id);
        } else if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("UPDATE notification_deliveries SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?", attempts, result.error(), id);
        } else {
            int minutes = attempts == 1 ? 5 : attempts == 2 ? 30 : 120;
            jdbc.update("UPDATE notification_deliveries SET attempts = ?, last_error = ?, next_attempt_at = now() + make_interval(mins => ?) WHERE id = ?",
                    attempts, result.error(), minutes, id);
        }
        return true;
    }

    @Transactional
    public void retry(Long id) {
        jdbc.update("UPDATE notification_deliveries SET status = 'PENDING', next_attempt_at = now() WHERE id = ? AND status IN ('FAILED', 'SKIPPED')", id);
    }

    static String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).format(v == null ? BigDecimal.ZERO : v);
    }
}

