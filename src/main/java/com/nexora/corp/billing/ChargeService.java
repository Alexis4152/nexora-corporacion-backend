package com.nexora.corp.billing;

import com.nexora.corp.billing.BillingDtos.AdjustmentRequest;
import com.nexora.corp.billing.BillingDtos.ChargeRequest;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import com.nexora.corp.shared.util.Money;
import com.nexora.corp.subscriptions.domain.Pricing;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cargos: emisión (por suscripción, prorrateo o manual), ajustes a favor y anulación. Un cargo con pagos aplicados
 * no se anula: primero se desaplican los pagos (queda trazabilidad completa).
 */
@Service
@RequiredArgsConstructor
public class ChargeService {

    public record PeriodCharge(Long customerId, Long subscriptionId, Long productId, Long planId, String description,
                               LocalDate periodStart, LocalDate periodEnd, LocalDate issueDate, LocalDate dueDate,
                               BigDecimal amount, BigDecimal discount, boolean taxIncluded, String currency) {
    }

    private final ChargeRepository charges;
    private final SettingsService settings;
    private final Folios folios;
    private final AuditService audit;
    private final ReauthGuard reauth;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Cargo del periodo de una suscripción. Idempotente por {@code SUB:<id>:<inicio>}: si ya existe uno vigente para ese
     * periodo se devuelve el existente (la tarea programada puede correr varias veces sin duplicar).
     */
    @Transactional
    public Charge periodCharge(PeriodCharge p) {
        Optional<Charge> existing = charges.findPeriodCharge(p.subscriptionId(), p.periodStart());
        if (existing.isPresent()) {
            return existing.get();
        }
        Pricing.Amounts a = Pricing.amounts(p.amount(), p.discount(), p.taxIncluded(), settings.taxRate());
        String key = "SUB:" + p.subscriptionId() + ":" + p.periodStart();
        // Si hubo un cargo anulado del mismo periodo, la llave idempotente debe ser otra.
        if (charges.findByIdempotencyKey(key).isPresent()) {
            key = key + ":" + clock.millis();
        }
        Charge c = charges.saveAndFlush(Charge.builder().folio(folios.next("charge_folio_seq", "CG", 6)).customerId(p.customerId())
                .subscriptionId(p.subscriptionId()).productId(p.productId()).planId(p.planId()).type("SUBSCRIPTION")
                .description(p.description()).periodStart(p.periodStart()).periodEnd(p.periodEnd()).issueDate(p.issueDate())
                .dueDate(p.dueDate().isBefore(p.issueDate()) ? p.issueDate() : p.dueDate())
                .amount(a.amount()).discount(a.discount()).subtotal(a.subtotal()).tax(a.tax()).total(a.total())
                .currency(p.currency()).idempotencyKey(key).build());
        audit.record(AuditEntry.success("billing", "CHARGE_ISSUED", "Charge", c.getId())
                .after(Map.of("folio", c.getFolio(), "total", c.getTotal(), "period", p.periodStart() + "/" + p.periodEnd())).build());
        return c;
    }

    /** Cargo por prorrateo (cambio inmediato a un plan superior). */
    @Transactional
    public Charge prorationCharge(Long customerId, Long subscriptionId, Long productId, Long planId, String description,
                                  LocalDate from, LocalDate to, BigDecimal amount, boolean taxIncluded, String currency) {
        LocalDate today = settings.today();
        Pricing.Amounts a = Pricing.amounts(amount, BigDecimal.ZERO, taxIncluded, settings.taxRate());
        Charge c = charges.saveAndFlush(Charge.builder().folio(folios.next("charge_folio_seq", "CG", 6)).customerId(customerId)
                .subscriptionId(subscriptionId).productId(productId).planId(planId).type("PRORATION").description(description)
                .periodStart(from).periodEnd(to).issueDate(today).dueDate(today).amount(a.amount()).discount(a.discount())
                .subtotal(a.subtotal()).tax(a.tax()).total(a.total()).currency(currency).build());
        audit.record(AuditEntry.success("billing", "CHARGE_ISSUED", "Charge", c.getId())
                .after(Map.of("folio", c.getFolio(), "total", c.getTotal(), "type", "PRORATION")).build());
        return c;
    }

    @Transactional
    public Charge manual(ChargeRequest r) {
        LocalDate today = settings.today();
        if (r.dueDate().isBefore(today.minusDays(365))) {
            throw new BusinessException("La fecha de vencimiento no es válida");
        }
        Long productId = null;
        if (r.subscriptionId() != null) {
            List<Map<String, Object>> sub = jdbc.queryForList("SELECT customer_id, product_id FROM subscriptions WHERE id = ?", r.subscriptionId());
            if (sub.isEmpty() || ((Number) sub.get(0).get("customer_id")).longValue() != r.customerId()) {
                throw new BusinessException("La suscripción no pertenece al cliente");
            }
            productId = ((Number) sub.get(0).get("product_id")).longValue();
        } else {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE id = ?", Integer.class, r.customerId());
            if (exists == null || exists == 0) {
                throw new NotFoundException("Cliente no encontrado");
            }
        }
        boolean taxIncluded = r.taxIncluded() != null ? r.taxIncluded() : settings.bool(Setting.PRICES_INCLUDE_TAX);
        Pricing.Amounts a = Pricing.amounts(r.amount(), BigDecimal.ZERO, taxIncluded, settings.taxRate());
        Charge c = charges.saveAndFlush(Charge.builder().folio(folios.next("charge_folio_seq", "CG", 6)).customerId(r.customerId())
                .subscriptionId(r.subscriptionId()).productId(productId).type(r.type()).description(r.description().trim())
                .issueDate(today).dueDate(r.dueDate().isBefore(today) ? today : r.dueDate()).amount(a.amount()).discount(a.discount())
                .subtotal(a.subtotal()).tax(a.tax()).total(a.total()).currency(settings.currency()).build());
        audit.record(AuditEntry.success("billing", "CHARGE_CREATED", "Charge", c.getId())
                .after(Map.of("folio", c.getFolio(), "total", c.getTotal(), "type", c.getType(), "description", c.getDescription())).build());
        return c;
    }

    @Transactional
    public Charge voidCharge(Long id, String reason) {
        reauth.require();
        return voidInternal(id, reason, true);
    }

    /** Anulación interna (cancelaciones, cambios de plan). {@code audited=true} también cuando la pide un usuario. */
    @Transactional
    public Charge voidInternal(Long id, String reason, boolean byUser) {
        Charge c = charges.lockById(id).orElseThrow(() -> new NotFoundException("Cargo no encontrado"));
        if ("VOID".equals(c.getStatus())) {
            throw new BusinessException("El cargo ya está anulado");
        }
        if (c.getPaidAmount().signum() > 0) {
            throw new BusinessException("El cargo tiene pagos aplicados. Desaplícalos antes de anularlo.");
        }
        c.setStatus("VOID");
        c.setVoidedAt(clock.instant());
        c.setVoidedBy(SecurityUtils.currentUserId());
        c.setVoidReason(reason);
        audit.record(AuditEntry.success("billing", byUser ? "CHARGE_VOIDED" : "CHARGE_VOIDED_AUTO", "Charge", id)
                .before(Map.of("status", "OPEN", "total", c.getTotal())).after(Map.of("status", "VOID")).detail(reason).build());
        return c;
    }

    @Transactional
    public Charge adjust(Long id, AdjustmentRequest r) {
        reauth.require();
        Charge c = charges.lockById(id).orElseThrow(() -> new NotFoundException("Cargo no encontrado"));
        if (!c.open()) {
            throw new BusinessException("Solo se ajustan cargos con saldo pendiente");
        }
        BigDecimal amount = Money.of(r.amount());
        if (amount.compareTo(c.balance()) > 0) {
            throw new BusinessException("El ajuste no puede ser mayor al saldo del cargo (" + c.balance() + ")");
        }
        jdbc.update("INSERT INTO charge_adjustments (charge_id, amount, reason, created_by) VALUES (?, ?, ?, ?)",
                id, amount, r.reason().trim(), SecurityUtils.currentUserId());
        c.setAdjustedAmount(c.getAdjustedAmount().add(amount));
        c.refreshStatus();
        audit.record(AuditEntry.success("billing", "CHARGE_ADJUSTED", "Charge", id)
                .after(Map.of("amount", amount, "newBalance", c.balance())).detail(r.reason()).build());
        if (c.getSubscriptionId() != null) {
            events.publishEvent(new BillingEvents.BalanceChanged(c.getCustomerId(), List.of(c.getSubscriptionId())));
        }
        return c;
    }

    public Charge require(Long id) {
        return charges.findById(id).orElseThrow(() -> new NotFoundException("Cargo no encontrado"));
    }
}

