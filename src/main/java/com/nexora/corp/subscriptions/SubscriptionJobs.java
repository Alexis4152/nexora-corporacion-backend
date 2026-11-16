package com.nexora.corp.subscriptions;

import com.nexora.corp.billing.BillingEvents;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.jobs.JobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ciclo diario del motor de suscripciones. Corre cada hora (y al arrancar): todo es idempotente, así que repetirlo
 * solo procesa lo pendiente. Orden: activar → expirar pruebas → cerrar/renovar periodos → emitir cargos anticipados →
 * reglas de cobranza.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionJobs {

    private final JobRunner runner;
    private final SubscriptionRepository repository;
    private final SubscriptionService service;
    private final SettingsService settings;
    private final AppProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.security().jobsEnabled()) {
            runCycle();
        }
    }

    @Scheduled(cron = "0 5 * * * *")
    public void hourly() {
        if (props.security().jobsEnabled()) {
            runCycle();
        }
    }

    public List<JobRunner.Result> runCycle() {
        LocalDate today = settings.today();
        int lead = settings.integer(Setting.CHARGE_LEAD_DAYS);
        List<JobRunner.Result> results = new ArrayList<>();
        results.add(runner.run("subscriptions.activate", today, () -> repository.dueForActivation(today), id -> service.activateIfDue(id, today)));
        results.add(runner.run("subscriptions.trials", today, () -> repository.expiredTrials(today), id -> service.expireTrialIfDue(id, today)));
        results.add(runner.run("subscriptions.renewals", today,
                () -> repository.periodEnded(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE, SubscriptionStatus.CANCEL_SCHEDULED), today),
                id -> service.rollOverIfDue(id, today)));
        results.add(runner.run("billing.charges", today, () -> repository.needingNextCharge(today.plusDays(lead)),
                id -> service.issueNextChargeIfDue(id, today, lead)));
        results.add(runner.run("billing.dunning", today, () -> {
            List<Long> ids = new ArrayList<>(repository.inGoodStanding());
            ids.addAll(repository.periodEnded(List.of(SubscriptionStatus.SUSPENDED), today.plusYears(10)));
            return ids;
        }, id -> service.applyCollectionRules(id, today)));
        return results;
    }

    /** Tras un pago, desaplicación o ajuste: actualizar gracia/recuperación en la misma transacción. */
    @EventListener
    public void onBalanceChanged(BillingEvents.BalanceChanged event) {
        LocalDate today = settings.today();
        event.subscriptionIds().forEach(id -> service.applyCollectionRules(id, today));
    }
}

