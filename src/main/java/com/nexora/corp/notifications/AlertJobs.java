package com.nexora.corp.notifications;

import com.nexora.corp.notifications.NotificationService.Alert;
import com.nexora.corp.notifications.NotificationService.Category;
import com.nexora.corp.notifications.NotificationService.Severity;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.jobs.JobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Alertas que dependen del paso del tiempo (pruebas por terminar, cargos que se vencen, prospectos sin seguimiento,
 * promesas de pago, renovaciones manuales, fallas de tareas). Todas con {@code dedupe_key}: idempotentes.
 */
@Component
@RequiredArgsConstructor
public class AlertJobs {

    private final JobRunner runner;
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final DeliveryService deliveries;
    private final SettingsService settings;
    private final AppProperties props;

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onStartup() {
        if (props.security().jobsEnabled()) {
            run();
        }
    }

    @Scheduled(cron = "0 20 * * * *")
    public void hourly() {
        if (props.security().jobsEnabled()) {
            run();
        }
    }

    public void run() {
        LocalDate today = settings.today();
        runner.run("alerts.generate", today, () -> List.of(1L, 2L, 3L, 4L, 5L, 6L), step -> {
            switch (step.intValue()) {
                case 1 -> trialsEnding(today);
                case 2 -> newOverdue(today);
                case 3 -> staleProspects();
                case 4 -> promisesDue(today);
                case 5 -> renewalsDue(today);
                default -> failedJobs();
            }
        });
        if (settings.bool(Setting.CUSTOMER_EMAILS)) {
            runner.run("collections.reminders", today, () -> List.of(1L), x -> deliveries.collectionReminders(today));
        }
    }

    private void trialsEnding(LocalDate today) {
        int days = settings.integer(Setting.TRIAL_ENDING_DAYS);
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT s.id, s.code, s.trial_ends_on, c.trade_name, p.name FROM subscriptions s JOIN customers c ON c.id = s.customer_id
                JOIN products p ON p.id = s.product_id WHERE s.status = 'TRIAL' AND s.trial_ends_on BETWEEN ? AND ?""", today, today.plusDays(days))) {
            Long id = ((Number) r.get("id")).longValue();
            notifications.toCategory(new Alert(Category.TRIALS, "TRIAL_ENDING", Severity.WARNING,
                    "Prueba por terminar: " + r.get("trade_name") + " · " + r.get("name"), "Termina el " + r.get("trial_ends_on")
                    + ". Al terminar no se cobra sola: conviértela si el cliente acepta.", "/suscripciones/" + id, "Subscription", id,
                    "TRIAL_ENDING:" + id + ":" + r.get("trial_ends_on")), null);
        }
    }

    private void newOverdue(LocalDate today) {
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT ch.id, ch.folio, ch.balance, ch.customer_id, c.trade_name FROM charge_balances ch JOIN customers c ON c.id = ch.customer_id
                WHERE ch.status IN ('OPEN', 'PARTIAL') AND ch.due_date = ? """, today.minusDays(1))) {
            Long id = ((Number) r.get("id")).longValue();
            notifications.toCategory(new Alert(Category.COLLECTION, "CHARGE_OVERDUE", Severity.WARNING,
                    "Pago vencido: " + r.get("trade_name"), "El cargo " + r.get("folio") + " venció ayer con saldo de $" + r.get("balance") + ".",
                    "/clientes/" + r.get("customer_id") + "?tab=cobranza", "Charge", id, "OVERDUE:" + id), null);
        }
    }

    private void staleProspects() {
        int days = settings.integer(Setting.PROSPECT_STALE_DAYS);
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT p.id, p.business_name, p.owner_user_id, p.stage_changed_at FROM prospects p
                WHERE p.stage NOT IN ('WON', 'LOST', 'DISCARDED') AND p.owner_user_id IS NOT NULL
                  AND COALESCE(GREATEST(p.stage_changed_at, (SELECT MAX(a.created_at) FROM sales_activities a WHERE a.prospect_id = p.id)),
                               p.created_at) < now() - make_interval(days => ?)""", days)) {
            Long id = ((Number) r.get("id")).longValue();
            notifications.toUser(((Number) r.get("owner_user_id")).longValue(), new Alert(Category.PROSPECTS, "PROSPECT_STALE", Severity.WARNING,
                    "Prospecto sin seguimiento: " + r.get("business_name"), "Lleva más de " + days + " días sin actividad.",
                    "/crm?p=" + id, "Prospect", id, "STALE:" + id + ":" + LocalDate.now().getYear() + "-" + (LocalDate.now().getDayOfYear() / 7)));
        }
    }

    private void promisesDue(LocalDate today) {
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id, a.customer_id, a.promise_amount, a.responsible_user_id, c.trade_name FROM collection_actions a
                JOIN customers c ON c.id = a.customer_id WHERE a.type = 'PROMISE' AND a.promise_date = ?""", today)) {
            Long id = ((Number) r.get("id")).longValue();
            Alert alert = new Alert(Category.COLLECTION, "PROMISE_DUE", Severity.INFO, "Hoy vence una promesa de pago: " + r.get("trade_name"),
                    "Importe prometido: $" + r.get("promise_amount"), "/clientes/" + r.get("customer_id") + "?tab=cobranza", "Customer",
                    ((Number) r.get("customer_id")).longValue(), "PROMISE:" + id);
            Object resp = r.get("responsible_user_id");
            if (resp != null) {
                notifications.toUser(((Number) resp).longValue(), alert);
            } else {
                notifications.toCategory(alert, null);
            }
        }
    }

    private void renewalsDue(LocalDate today) {
        int days = settings.integer(Setting.RENEWAL_DAYS);
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT s.id, s.current_period_end, c.trade_name, p.name FROM subscriptions s JOIN customers c ON c.id = s.customer_id
                JOIN products p ON p.id = s.product_id WHERE s.status IN ('ACTIVE', 'GRACE') AND NOT s.auto_renew
                  AND s.current_period_end BETWEEN ? AND ?""", today, today.plusDays(days))) {
            Long id = ((Number) r.get("id")).longValue();
            notifications.toCategory(new Alert(Category.SUBSCRIPTIONS, "RENEWAL_DUE", Severity.WARNING,
                    "Renovación manual pendiente: " + r.get("trade_name") + " · " + r.get("name"), "El periodo termina el "
                    + r.get("current_period_end") + ". Si no se confirma, la suscripción expira.", "/suscripciones/" + id, "Subscription", id,
                    "RENEWAL:" + id + ":" + r.get("current_period_end")), null);
        }
    }

    private void failedJobs() {
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT id, job, status, failed, detail FROM job_runs WHERE status IN ('FAILED', 'PARTIAL') AND started_at > now() - interval '1 day'""")) {
            Long id = ((Number) r.get("id")).longValue();
            notifications.toCategory(new Alert(Category.ADMIN, "JOB_FAILED", Severity.CRITICAL, "Error en la tarea " + r.get("job"),
                    r.get("failed") + " elemento(s) fallaron: " + r.get("detail"), "/configuracion?tab=tareas", "JobRun", id, "JOB:" + id), null);
        }
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void purge() {
        notifications.purgeOld();
    }
}

