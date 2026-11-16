package com.nexora.corp.subscriptions;

import com.nexora.corp.billing.Charge;
import com.nexora.corp.billing.ChargeRepository;
import com.nexora.corp.billing.ChargeService;
import com.nexora.corp.catalog.CatalogService;
import com.nexora.corp.catalog.Coupon;
import com.nexora.corp.catalog.CouponRepository;
import com.nexora.corp.catalog.Plan;
import com.nexora.corp.catalog.PlanPrice;
import com.nexora.corp.catalog.PlanPriceRepository;
import com.nexora.corp.catalog.Product;
import com.nexora.corp.customers.Customer;
import com.nexora.corp.customers.CustomerService;
import com.nexora.corp.identity.User;
import com.nexora.corp.identity.UserRepository;
import com.nexora.corp.settings.Setting;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import com.nexora.corp.shared.util.Money;
import com.nexora.corp.subscriptions.SubscriptionDtos.CancelRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.ChangePlanRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.CreateRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.DiscountRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.SellerRequest;
import com.nexora.corp.subscriptions.SubscriptionDtos.TermsRequest;
import com.nexora.corp.subscriptions.domain.PeriodMath;
import com.nexora.corp.subscriptions.domain.Pricing;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Motor del ciclo de vida de suscripciones (docs/04-REGLAS-FINANCIERAS.md). Cada operación crítica es transaccional,
 * bloquea la fila de la suscripción, registra un evento inmutable con su efecto en el MRR y queda auditada.
 * Regla clave: una prueba gratuita NUNCA se convierte sola en cobro; al terminar expira y se convierte a mano.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SubscriptionRepository subscriptions;
    private final SubscriptionEvents events;
    private final CatalogService catalog;
    private final PlanPriceRepository prices;
    private final CouponRepository coupons;
    private final CustomerService customers;
    private final ChargeService chargeService;
    private final ChargeRepository charges;
    private final SettingsService settings;
    private final Folios folios;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final com.nexora.corp.shared.security.ReauthGuard reauth;
    private final UserRepository users;
    private final Clock clock;

    /** Evento para notificaciones: cambió el estado de una suscripción. */
    public record StatusChanged(Long subscriptionId, String code, Long customerId, Long productId, String type,
                                SubscriptionStatus from, SubscriptionStatus to) {
    }

    // ── Alta ────────────────────────────────────────────────────────────────────────────

    @Transactional
    public Subscription create(CreateRequest r) {
        LocalDate today = settings.today();
        Customer customer = customers.require(r.customerId());
        if (customer.isArchived()) {
            throw new BusinessException("El cliente está archivado");
        }
        Product product = catalog.product(r.productId());
        if (!product.isActive()) {
            throw new BusinessException("El producto no está activo");
        }
        Plan plan = catalog.plan(r.planId());
        if (!plan.getProductId().equals(product.getId())) {
            throw new BusinessException("El plan no pertenece al producto");
        }
        LocalDate start = r.startDate();
        if (start.isBefore(today.minusDays(62))) {
            throw new BusinessException("La fecha de inicio no puede ser anterior a dos meses");
        }
        if (start.isAfter(today.plusDays(365))) {
            throw new BusinessException("La fecha de inicio es demasiado lejana");
        }
        if (!plan.sellableOn(start.isBefore(today) ? today : start)) {
            throw new BusinessException("El plan no está disponible para contratarse en esa fecha");
        }
        if (!r.allowDuplicate() && subscriptions.countByCustomerIdAndProductIdAndStatusIn(customer.getId(), product.getId(),
                SubscriptionStatus.LIVE) > 0) {
            throw new BusinessException("El cliente ya tiene una suscripción vigente de " + product.getName()
                    + ". Si es para otra sucursal, marca la opción de suscripción adicional.", "DUPLICATE_SUBSCRIPTION");
        }
        PlanPrice catalogPrice = prices.findCurrent(plan.getId(), r.billingPeriod()).orElse(null);
        if (catalogPrice == null && r.price() == null) {
            throw new BusinessException("El plan no tiene precio " + r.billingPeriod().label().toLowerCase(Locale.ROOT)
                    + ". Indica un precio pactado o configura el precio en el catálogo.");
        }
        BigDecimal listPrice = catalogPrice != null ? catalogPrice.getAmount() : Money.of(r.price());
        BigDecimal price = r.price() != null ? Money.of(r.price()) : listPrice;
        boolean taxIncluded = catalogPrice != null ? catalogPrice.isTaxIncluded() : settings.bool(Setting.PRICES_INCLUDE_TAX);

        Subscription s = Subscription.builder().code(folios.next("subscription_code_seq", "SUS", 6)).customerId(customer.getId())
                .productId(product.getId()).planId(plan.getId()).planPriceId(catalogPrice == null ? null : catalogPrice.getId())
                .billingPeriod(r.billingPeriod()).listPrice(listPrice).price(price).currency(settings.currency()).taxIncluded(taxIncluded)
                .startDate(start).billingAnchorDay(start.getDayOfMonth())
                .autoRenew(r.autoRenew() != null ? r.autoRenew() : "AUTO".equals(product.getRenewalPolicy()))
                .graceDays(r.graceDays() != null ? r.graceDays() : product.getDefaultGraceDays())
                .preferredPaymentMethod(blank(r.preferredPaymentMethod())).notes(blank(r.notes()))
                .sellerUserId(r.sellerUserId() != null ? activeUser(r.sellerUserId())
                        : customer.getOwnerUserId() != null ? customer.getOwnerUserId() : SecurityUtils.currentUserId()).build();
        applyDiscountTerms(s, r, product.getId(), today);

        if (r.trial()) {
            int days = r.trialDays() != null ? r.trialDays()
                    : plan.getTrialDays() != null ? plan.getTrialDays() : product.getDefaultTrialDays();
            if (days <= 0) {
                throw new BusinessException("El plan no contempla prueba gratuita; indica los días de prueba");
            }
            s.setStatus(SubscriptionStatus.TRIAL);
            s.setTrialEndsOn(start.plusDays(days - 1L));
        } else {
            s.setStatus(SubscriptionStatus.PENDING_ACTIVATION);
        }
        s = subscriptions.saveAndFlush(s);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", plan.getCode());
        data.put("period", r.billingPeriod().name());
        data.put("price", price);
        if (price.compareTo(listPrice) != 0) {
            data.put("listPrice", listPrice);
        }
        events.record(s, "CREATED", null, BigDecimal.ZERO, start, data);
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_CREATED", "Subscription", s.getId())
                .after(Map.of("code", s.getCode(), "customer", customer.getCode(), "product", product.getCode(), "plan", plan.getCode(),
                        "price", price, "status", s.getStatus().name())).build());
        if (!r.trial() && !start.isAfter(today)) {
            activate(s, start, "ACTIVATED");
        }
        notify(s, "CREATED", null);
        return s;
    }

    private void applyDiscountTerms(Subscription s, CreateRequest r, Long productId, LocalDate today) {
        boolean manual = r.discountType() != null && !"NONE".equals(r.discountType()) && r.discountValue() != null && r.discountValue().signum() > 0;
        if (r.couponCode() != null && !r.couponCode().isBlank()) {
            if (manual) {
                throw new BusinessException("Usa un cupón o un descuento manual, no ambos");
            }
            Coupon c = coupons.findByCodeForUpdate(r.couponCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new BusinessException("El cupón no existe"));
            if (!c.usableOn(today, productId)) {
                throw new BusinessException("El cupón no está vigente o no aplica a este producto");
            }
            c.setRedemptions(c.getRedemptions() + 1);
            s.setCouponId(c.getId());
            s.setDiscountType(c.getDiscountType());
            s.setDiscountValue(c.getDiscountValue());
            s.setDiscountCyclesRemaining(c.getDurationCycles());
        } else if (manual) {
            validateDiscount(r.discountType(), r.discountValue(), s.getPrice());
            s.setDiscountType(r.discountType());
            s.setDiscountValue(Money.of(r.discountValue()));
            s.setDiscountCyclesRemaining(r.discountCycles());
        }
    }

    private static void validateDiscount(String type, BigDecimal value, BigDecimal price) {
        if ("PERCENT".equals(type) && value.compareTo(Money.HUNDRED) > 0) {
            throw new BusinessException("El descuento no puede ser mayor a 100 %");
        }
        if ("AMOUNT".equals(type) && value.compareTo(price) > 0) {
            throw new BusinessException("El descuento no puede ser mayor al precio");
        }
    }

    /**
     * Activa (o reactiva) a partir de {@code start}: abre el periodo, emite su cargo y fija el MRR. Los ciclos de
     * descuento se cuentan por periodo: el periodo actual tiene descuento si {@code discountType != NONE} y
     * {@code discountCyclesRemaining} son los periodos con descuento DESPUÉS del actual.
     */
    void activate(Subscription s, LocalDate start, String eventType) {
        SubscriptionStatus from = s.getStatus();
        BigDecimal mrrBefore = s.getMrr();
        LocalDate end = PeriodMath.periodEnd(start, s.getBillingPeriod(), s.getBillingAnchorDay());
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCurrentPeriodStart(start);
        s.setCurrentPeriodEnd(end);
        s.setNextChargeOn(end.plusDays(1));
        if (!"NONE".equals(s.getDiscountType()) && s.getDiscountCyclesRemaining() != null) {
            s.setDiscountCyclesRemaining(Math.max(0, s.getDiscountCyclesRemaining() - 1));
        }
        recalcMrr(s);
        subscriptions.saveAndFlush(s);
        issuePeriodCharge(s, start, end, s.getPrice(), s.getPlanId(), !"NONE".equals(s.getDiscountType()));
        events.record(s, eventType, from, mrrBefore, start, Map.of("periodStart", start.toString(), "periodEnd", end.toString()));
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_" + eventType, "Subscription", s.getId())
                .before(Map.of("status", from.name())).after(Map.of("status", s.getStatus().name(), "mrr", s.getMrr())).build());
        notify(s, eventType, from);
    }

    Charge issuePeriodCharge(Subscription s, LocalDate start, LocalDate end, BigDecimal price, Long planId, boolean discounted) {
        Product product = catalog.product(s.getProductId());
        Plan plan = catalog.plan(planId);
        BigDecimal discount = discounted ? Pricing.discount(price, s.getDiscountType(), s.getDiscountValue()) : BigDecimal.ZERO;
        LocalDate today = settings.today();
        return chargeService.periodCharge(new ChargeService.PeriodCharge(s.getCustomerId(), s.getId(), s.getProductId(), planId,
                product.getName() + " · " + plan.getName() + " · " + s.getBillingPeriod().label() + " · " + start.format(DAY) + " – " + end.format(DAY),
                start, end, today.isBefore(start) ? today : start, start, price, discount, s.isTaxIncluded(), s.getCurrency()));
    }

    void recalcMrr(Subscription s) {
        s.setMrr(s.getStatus().recurring()
                ? Pricing.monthly(s.getPrice(), s.getBillingPeriod(), s.getDiscountType(), s.getDiscountValue(), s.isTaxIncluded(), settings.taxRate())
                : Money.precise(BigDecimal.ZERO));
    }

    // ── Operaciones de usuario ─────────────────────────────────────────────────────────

    @Transactional
    public Subscription activateNow(Long id) {
        Subscription s = lock(id);
        if (s.getStatus() != SubscriptionStatus.PENDING_ACTIVATION) {
            throw new BusinessException("Solo se activan suscripciones pendientes de activación");
        }
        LocalDate today = settings.today();
        activate(s, s.getStartDate().isAfter(today) ? today : s.getStartDate(), "ACTIVATED");
        return s;
    }

    /** Prueba → plan pagado. Solo a petición expresa (nunca automática). */
    @Transactional
    public Subscription convertTrial(Long id) {
        Subscription s = lock(id);
        if (s.getStatus() != SubscriptionStatus.TRIAL && !(s.getStatus() == SubscriptionStatus.EXPIRED && s.getTrialEndsOn() != null
                && s.getCurrentPeriodStart() == null)) {
            throw new BusinessException("La suscripción no está en prueba gratuita");
        }
        LocalDate today = settings.today();
        s.setBillingAnchorDay(today.getDayOfMonth());
        activate(s, today, "TRIAL_CONVERTED");
        return s;
    }

    @Transactional
    public Subscription changePlan(Long id, ChangePlanRequest r) {
        Subscription s = lock(id);
        LocalDate today = settings.today();
        Plan plan = catalog.plan(r.planId());
        if (!plan.getProductId().equals(s.getProductId())) {
            throw new BusinessException("El plan no pertenece al producto de la suscripción");
        }
        if (!plan.sellableOn(today)) {
            throw new BusinessException("El plan no está disponible para contratarse");
        }
        PlanPrice catalogPrice = prices.findCurrent(plan.getId(), r.billingPeriod()).orElse(null);
        if (catalogPrice == null && r.price() == null) {
            throw new BusinessException("El plan no tiene precio para esa periodicidad; indica un precio pactado");
        }
        BigDecimal newPrice = r.price() != null ? Money.of(r.price()) : catalogPrice.getAmount();
        BigDecimal newList = catalogPrice != null ? catalogPrice.getAmount() : newPrice;
        boolean samePlan = plan.getId().equals(s.getPlanId()) && r.billingPeriod() == s.getBillingPeriod();
        if (samePlan && newPrice.compareTo(s.getPrice()) == 0) {
            throw new BusinessException("No hay cambios respecto al plan actual");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromPlan", catalog.plan(s.getPlanId()).getCode());
        data.put("toPlan", plan.getCode());
        data.put("fromPrice", s.getPrice());
        data.put("toPrice", newPrice);
        data.put("fromPeriod", s.getBillingPeriod().name());
        data.put("toPeriod", r.billingPeriod().name());

        switch (s.getStatus()) {
            case TRIAL, PENDING_ACTIVATION -> {
                applyPlan(s, plan.getId(), catalogPrice, newPrice, newList, r.billingPeriod());
                subscriptions.saveAndFlush(s);
                events.record(s, "PLAN_CHANGED", s.getStatus(), s.getMrr(), today, data);
            }
            case ACTIVE, GRACE -> {
                if ("IMMEDIATE".equals(r.effective())) {
                    if (r.billingPeriod() != s.getBillingPeriod()) {
                        throw new BusinessException("El cambio de periodicidad aplica al final del periodo actual");
                    }
                    BigDecimal oldGross = s.getPrice().subtract(Pricing.discount(s.getPrice(), s.getDiscountType(), s.getDiscountValue()));
                    BigDecimal newGross = newPrice.subtract(Pricing.discount(newPrice, s.getDiscountType(), s.getDiscountValue()));
                    if (newGross.compareTo(oldGross) <= 0) {
                        throw new BusinessException("Las reducciones de plan o precio aplican al final del periodo (sin reembolsos)");
                    }
                    long remaining = PeriodMath.remaining(today, s.getCurrentPeriodEnd());
                    long total = PeriodMath.days(s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
                    BigDecimal proration = Pricing.proration(oldGross, newGross, remaining, total);
                    BigDecimal mrrBefore = s.getMrr();
                    applyPlan(s, plan.getId(), catalogPrice, newPrice, newList, r.billingPeriod());
                    recalcMrr(s);
                    subscriptions.saveAndFlush(s);
                    if (proration.signum() > 0) {
                        Product product = catalog.product(s.getProductId());
                        chargeService.prorationCharge(s.getCustomerId(), s.getId(), s.getProductId(), plan.getId(),
                                product.getName() + " · Prorrateo a " + plan.getName() + " (" + remaining + " de " + total + " días)",
                                today, s.getCurrentPeriodEnd(), proration, s.isTaxIncluded(), s.getCurrency());
                        data.put("proration", proration);
                    }
                    data.put("remainingDays", remaining);
                    events.record(s, "PLAN_CHANGED", s.getStatus(), mrrBefore, today, data);
                } else {
                    s.setPendingPlanId(plan.getId());
                    s.setPendingPlanPriceId(catalogPrice == null ? null : catalogPrice.getId());
                    s.setPendingPrice(newPrice);
                    s.setPendingBillingPeriod(r.billingPeriod());
                    subscriptions.saveAndFlush(s);
                    data.put("effectiveOn", s.getCurrentPeriodEnd().plusDays(1).toString());
                    events.record(s, "PLAN_CHANGE_SCHEDULED", s.getStatus(), s.getMrr(), today, data);
                }
                reissueNextCharge(s, "Cambio de plan");
            }
            default -> throw new BusinessException("No se puede cambiar el plan de una suscripción " + s.getStatus().label().toLowerCase(Locale.ROOT));
        }
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_PLAN_CHANGED", "Subscription", id).before(Map.of(
                "plan", data.get("fromPlan"), "price", data.get("fromPrice"), "period", data.get("fromPeriod"))).after(Map.of(
                "plan", data.get("toPlan"), "price", data.get("toPrice"), "period", data.get("toPeriod"), "effective", r.effective())).build());
        return s;
    }

    private void applyPlan(Subscription s, Long planId, PlanPrice catalogPrice, BigDecimal price, BigDecimal list, BillingPeriod period) {
        s.setPlanId(planId);
        s.setPlanPriceId(catalogPrice == null ? null : catalogPrice.getId());
        s.setPrice(price);
        s.setListPrice(list);
        s.setBillingPeriod(period);
        if (catalogPrice != null) {
            s.setTaxIncluded(catalogPrice.isTaxIncluded());
        }
        if ("AMOUNT".equals(s.getDiscountType()) && s.getDiscountValue().compareTo(price) > 0) {
            s.setDiscountValue(price);
        }
    }

    @Transactional
    public Subscription cancelPendingChange(Long id) {
        Subscription s = lock(id);
        if (!s.hasPendingChange()) {
            throw new BusinessException("No hay un cambio programado");
        }
        s.setPendingPlanId(null);
        s.setPendingPlanPriceId(null);
        s.setPendingPrice(null);
        s.setPendingBillingPeriod(null);
        subscriptions.saveAndFlush(s);
        events.record(s, "PLAN_CHANGE_CANCELLED", s.getStatus(), s.getMrr(), settings.today(), null);
        reissueNextCharge(s, "Se canceló el cambio de plan");
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_PLAN_CHANGE_CANCELLED", "Subscription", id).build());
        return s;
    }

    /**
     * Si el cargo del siguiente periodo ya se emitió (días de anticipación) y no tiene pagos, se anula; la tarea lo
     * vuelve a emitir con las condiciones nuevas. Si ya tiene pagos se conserva (el ajuste se hace a mano).
     */
    private void reissueNextCharge(Subscription s, String reason) {
        if (s.getCurrentPeriodEnd() == null) {
            return;
        }
        charges.findPeriodCharge(s.getId(), s.getCurrentPeriodEnd().plusDays(1)).ifPresent(c -> {
            if (c.getPaidAmount().signum() == 0 && c.getAdjustedAmount().signum() == 0) {
                chargeService.voidInternal(c.getId(), reason + ": se reemite con las nuevas condiciones", false);
            }
        });
    }

    @Transactional
    public Subscription cancel(Long id, CancelRequest r) {
        Subscription s = lock(id);
        LocalDate today = settings.today();
        SubscriptionStatus from = s.getStatus();
        BigDecimal mrrBefore = s.getMrr();
        if (from.closed()) {
            throw new BusinessException("La suscripción ya está " + from.label().toLowerCase(Locale.ROOT));
        }
        if (r.waiveOpenBalance()) {
            if (!SecurityUtils.has("charges.void")) {
                throw new BusinessException("No tienes permiso para condonar adeudos al cancelar");
            }
            reauth.require();
        }
        s.setCancellationReason(r.reason());
        s.setCancellationDetail(blank(r.detail()));
        s.setCancellationRequestedAt(clock.instant());
        boolean endOfPeriod = "END_OF_PERIOD".equals(r.mode()) && (from == SubscriptionStatus.ACTIVE || from == SubscriptionStatus.GRACE);
        Map<String, Object> data = new HashMap<>(Map.of("reason", r.reason(), "mode", r.mode()));
        if (endOfPeriod) {
            s.setStatus(SubscriptionStatus.CANCEL_SCHEDULED);
            s.setCancelAtPeriodEnd(true);
            s.setNextChargeOn(null);
            subscriptions.saveAndFlush(s);
            reissueNextCharge(s, "Cancelación programada");
            data.put("effectiveOn", s.getCurrentPeriodEnd().plusDays(1).toString());
            events.record(s, "CANCEL_SCHEDULED", from, mrrBefore, today, data);
        } else {
            closeAsCancelled(s, today);
            int voided = voidFutureCharges(s, today, r.waiveOpenBalance());
            data.put("voidedCharges", voided);
            events.record(s, "CANCELLED", from, mrrBefore, today, data);
        }
        audit.record(AuditEntry.success("subscriptions", endOfPeriod ? "SUBSCRIPTION_CANCEL_SCHEDULED" : "SUBSCRIPTION_CANCELLED",
                "Subscription", id).before(Map.of("status", from.name(), "mrr", mrrBefore))
                .after(Map.of("status", s.getStatus().name(), "reason", r.reason(), "waived", r.waiveOpenBalance())).detail(r.detail()).build());
        notify(s, endOfPeriod ? "CANCEL_SCHEDULED" : "CANCELLED", from);
        return s;
    }

    void closeAsCancelled(Subscription s, LocalDate effective) {
        s.setStatus(SubscriptionStatus.CANCELLED);
        s.setCancelledOn(effective);
        s.setCancelAtPeriodEnd(false);
        s.setNextChargeOn(null);
        s.setPendingPlanId(null);
        s.setPendingPlanPriceId(null);
        s.setPendingPrice(null);
        s.setPendingBillingPeriod(null);
        recalcMrr(s);
        subscriptions.saveAndFlush(s);
    }

    /** Anula cargos sin pagos de periodos que aún no empiezan; con {@code waiveAll} también condona el saldo vigente. */
    private int voidFutureCharges(Subscription s, LocalDate today, boolean waiveAll) {
        int n = 0;
        for (Charge c : charges.openForSubscription(s.getId())) {
            boolean future = c.getPeriodStart() != null && c.getPeriodStart().isAfter(today);
            if ((future || waiveAll) && c.getPaidAmount().signum() == 0 && c.getAdjustedAmount().signum() == 0) {
                chargeService.voidInternal(c.getId(), future ? "Periodo posterior a la cancelación" : "Adeudo condonado al cancelar", !future);
                n++;
            } else if (waiveAll) {
                chargeService.adjust(c.getId(), new com.nexora.corp.billing.BillingDtos.AdjustmentRequest(c.balance(), "Saldo condonado al cancelar"));
                n++;
            }
        }
        return n;
    }

    @Transactional
    public Subscription undoCancel(Long id) {
        Subscription s = lock(id);
        if (s.getStatus() != SubscriptionStatus.CANCEL_SCHEDULED) {
            throw new BusinessException("La suscripción no tiene una cancelación programada");
        }
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCancelAtPeriodEnd(false);
        s.setCancellationReason(null);
        s.setCancellationDetail(null);
        s.setCancellationRequestedAt(null);
        s.setNextChargeOn(s.getCurrentPeriodEnd().plusDays(1));
        subscriptions.saveAndFlush(s);
        events.record(s, "CANCEL_REVERTED", SubscriptionStatus.CANCEL_SCHEDULED, s.getMrr(), settings.today(), null);
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_CANCEL_REVERTED", "Subscription", id).build());
        return s;
    }

    @Transactional
    public Subscription suspend(Long id, String reason) {
        Subscription s = lock(id);
        SubscriptionStatus from = s.getStatus();
        if (from != SubscriptionStatus.ACTIVE && from != SubscriptionStatus.GRACE && from != SubscriptionStatus.CANCEL_SCHEDULED) {
            throw new BusinessException("Solo se suspenden suscripciones activas o en gracia");
        }
        doSuspend(s, reason, false);
        return s;
    }

    void doSuspend(Subscription s, String reason, boolean automatic) {
        SubscriptionStatus from = s.getStatus();
        BigDecimal before = s.getMrr();
        s.setStatus(SubscriptionStatus.SUSPENDED);
        s.setSuspendedAt(clock.instant());
        s.setCancelAtPeriodEnd(false);
        recalcMrr(s);
        subscriptions.saveAndFlush(s);
        events.record(s, automatic ? "AUTO_SUSPENDED" : "SUSPENDED", from, before, settings.today(), Map.of("reason", reason));
        audit.record(AuditEntry.success("subscriptions", automatic ? "SUBSCRIPTION_AUTO_SUSPENDED" : "SUBSCRIPTION_SUSPENDED",
                "Subscription", s.getId()).before(Map.of("status", from.name())).after(Map.of("status", "SUSPENDED")).detail(reason).build());
        notify(s, "SUSPENDED", from);
    }

    /** Reactiva una suscripción suspendida (continúa su periodo si sigue vigente) o cancelada/expirada (periodo nuevo). */
    @Transactional
    public Subscription reactivate(Long id) {
        Subscription s = lock(id);
        LocalDate today = settings.today();
        SubscriptionStatus from = s.getStatus();
        if (from == SubscriptionStatus.SUSPENDED && s.getCurrentPeriodEnd() != null && !s.getCurrentPeriodEnd().isBefore(today)) {
            BigDecimal before = s.getMrr();
            s.setStatus(SubscriptionStatus.ACTIVE);
            s.setSuspendedAt(null);
            recalcMrr(s);
            subscriptions.saveAndFlush(s);
            events.record(s, "REACTIVATED", from, before, today, null);
            audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_REACTIVATED", "Subscription", id)
                    .before(Map.of("status", from.name())).after(Map.of("status", "ACTIVE")).build());
            notify(s, "REACTIVATED", from);
        } else if (from == SubscriptionStatus.SUSPENDED || from.closed()) {
            if (s.getTrialEndsOn() != null && s.getCurrentPeriodStart() == null) {
                throw new BusinessException("Esta suscripción nunca salió de prueba: usa «Convertir a plan pagado»");
            }
            s.setSuspendedAt(null);
            s.setCancelledOn(null);
            s.setCancellationReason(null);
            s.setCancellationDetail(null);
            s.setCancellationRequestedAt(null);
            s.setBillingAnchorDay(today.getDayOfMonth());
            activate(s, today, "REACTIVATED");
        } else {
            throw new BusinessException("La suscripción no está suspendida, cancelada ni expirada");
        }
        return s;
    }

    /** Renovación manual (suscripciones sin renovación automática): emite ya el cargo del siguiente periodo. */
    @Transactional
    public Subscription renewNow(Long id) {
        Subscription s = lock(id);
        if (s.getStatus() == SubscriptionStatus.EXPIRED) {
            return reactivate(id);
        }
        if (s.getStatus() != SubscriptionStatus.ACTIVE && s.getStatus() != SubscriptionStatus.GRACE) {
            throw new BusinessException("Solo se renuevan suscripciones activas o expiradas");
        }
        issueNextPeriodCharge(s);
        events.record(s, "RENEWAL_CONFIRMED", s.getStatus(), s.getMrr(), settings.today(),
                Map.of("nextPeriodStart", s.getCurrentPeriodEnd().plusDays(1).toString()));
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_RENEWAL_CONFIRMED", "Subscription", id).build());
        return s;
    }

    @Transactional
    public Subscription setDiscount(Long id, DiscountRequest r) {
        Subscription s = lock(id);
        if (s.getStatus().closed()) {
            throw new BusinessException("La suscripción está cerrada");
        }
        BigDecimal before = s.getMrr();
        Map<String, Object> old = Map.of("type", s.getDiscountType(), "value", s.getDiscountValue());
        if ("NONE".equals(r.type()) || r.value() == null || r.value().signum() == 0) {
            s.setDiscountType("NONE");
            s.setDiscountValue(BigDecimal.ZERO);
            s.setDiscountCyclesRemaining(null);
            s.setCouponId(null);
        } else {
            validateDiscount(r.type(), r.value(), s.getPrice());
            s.setDiscountType(r.type());
            s.setDiscountValue(Money.of(r.value()));
            s.setDiscountCyclesRemaining(r.cycles() == null ? null : r.cycles() - 1);
        }
        recalcMrr(s);
        subscriptions.saveAndFlush(s);
        events.record(s, "DISCOUNT_CHANGED", s.getStatus(), before, settings.today(), Map.of("type", s.getDiscountType(), "value", s.getDiscountValue()));
        reissueNextCharge(s, "Cambio de descuento");
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_DISCOUNT_CHANGED", "Subscription", id).before(old)
                .after(Map.of("type", s.getDiscountType(), "value", s.getDiscountValue(), "cycles", String.valueOf(r.cycles()))).detail(r.reason()).build());
        return s;
    }

    @Transactional
    public Subscription updateTerms(Long id, TermsRequest r) {
        Subscription s = lock(id);
        Map<String, Object> before = new HashMap<>();
        before.put("autoRenew", s.isAutoRenew());
        before.put("graceDays", s.getGraceDays());
        s.setAutoRenew(r.autoRenew());
        s.setGraceDays(r.graceDays());
        s.setPreferredPaymentMethod(blank(r.preferredPaymentMethod()));
        s.setNotes(blank(r.notes()));
        subscriptions.saveAndFlush(s);
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_TERMS_UPDATED", "Subscription", id).before(before)
                .after(Map.of("autoRenew", r.autoRenew(), "graceDays", r.graceDays())).build());
        return s;
    }

    /** Reasigna el vendedor (corrección o venta compartida). No toca importes ni el historial de MRR. */
    @Transactional
    public Subscription changeSeller(Long id, SellerRequest r) {
        Subscription s = lock(id);
        Long before = s.getSellerUserId();
        if (Objects.equals(before, r.sellerUserId())) {
            return s;
        }
        s.setSellerUserId(activeUser(r.sellerUserId()));
        subscriptions.saveAndFlush(s);
        Map<String, Object> old = new HashMap<>();
        old.put("seller", before);
        audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_SELLER_CHANGED", "Subscription", id).before(old)
                .after(Map.of("seller", r.sellerUserId())).detail(blank(r.reason())).build());
        return s;
    }

    private Long activeUser(Long userId) {
        if (!users.findById(userId).map(User::isActive).orElse(false)) {
            throw new BusinessException("El vendedor no existe o está inactivo");
        }
        return userId;
    }

    // ── Tareas programadas (cada método se ejecuta en su propia transacción) ───────────

    @Transactional
    public void activateIfDue(Long id, LocalDate today) {
        Subscription s = lock(id);
        if (s.getStatus() == SubscriptionStatus.PENDING_ACTIVATION && !s.getStartDate().isAfter(today)) {
            activate(s, s.getStartDate(), "ACTIVATED");
        }
    }

    /** Fin de prueba sin conversión: EXPIRA (sin cobro automático, por regla). */
    @Transactional
    public void expireTrialIfDue(Long id, LocalDate today) {
        Subscription s = lock(id);
        if (s.getStatus() == SubscriptionStatus.TRIAL && s.getTrialEndsOn().isBefore(today)) {
            s.setStatus(SubscriptionStatus.EXPIRED);
            subscriptions.saveAndFlush(s);
            events.record(s, "TRIAL_EXPIRED", SubscriptionStatus.TRIAL, BigDecimal.ZERO, s.getTrialEndsOn().plusDays(1), null);
            audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_TRIAL_EXPIRED", "Subscription", id).build());
            notify(s, "TRIAL_EXPIRED", SubscriptionStatus.TRIAL);
        }
    }

    /**
     * Cierre de periodo: cancela las programadas, expira las de renovación manual no confirmada y renueva las demás
     * (aplicando cambios programados y fin de descuentos). Repite si el sistema estuvo detenido varios periodos.
     */
    @Transactional
    public void rollOverIfDue(Long id, LocalDate today) {
        Subscription s = lock(id);
        int guard = 0;
        while (s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isBefore(today) && guard++ < 36) {
            SubscriptionStatus from = s.getStatus();
            LocalDate nextStart = s.getCurrentPeriodEnd().plusDays(1);
            BigDecimal mrrBefore = s.getMrr();
            if (from == SubscriptionStatus.CANCEL_SCHEDULED) {
                closeAsCancelled(s, nextStart);
                events.record(s, "CANCELLED", from, mrrBefore, nextStart, Map.of("reason", String.valueOf(s.getCancellationReason()), "scheduled", true));
                audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_CANCELLED", "Subscription", id).detail("Cancelación programada").build());
                notify(s, "CANCELLED", from);
                return;
            }
            if (from != SubscriptionStatus.ACTIVE && from != SubscriptionStatus.GRACE) {
                return;
            }
            boolean renewalConfirmed = charges.findPeriodCharge(s.getId(), nextStart).isPresent();
            if (!s.isAutoRenew() && !renewalConfirmed) {
                s.setStatus(SubscriptionStatus.EXPIRED);
                s.setNextChargeOn(null);
                recalcMrr(s);
                subscriptions.saveAndFlush(s);
                events.record(s, "EXPIRED", from, mrrBefore, nextStart, Map.of("reason", "Renovación manual no confirmada"));
                audit.record(AuditEntry.success("subscriptions", "SUBSCRIPTION_EXPIRED", "Subscription", id).build());
                notify(s, "EXPIRED", from);
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (s.hasPendingChange()) {
                PlanPrice pp = s.getPendingPlanPriceId() == null ? null : prices.findById(s.getPendingPlanPriceId()).orElse(null);
                data.put("planChange", Map.of("plan", catalog.plan(s.getPendingPlanId()).getCode(), "price", s.getPendingPrice(),
                        "period", s.getPendingBillingPeriod().name()));
                applyPlan(s, s.getPendingPlanId(), pp, s.getPendingPrice(), pp != null ? pp.getAmount() : s.getPendingPrice(), s.getPendingBillingPeriod());
                s.setPendingPlanId(null);
                s.setPendingPlanPriceId(null);
                s.setPendingPrice(null);
                s.setPendingBillingPeriod(null);
            }
            if (!"NONE".equals(s.getDiscountType()) && s.getDiscountCyclesRemaining() != null) {
                if (s.getDiscountCyclesRemaining() > 0) {
                    s.setDiscountCyclesRemaining(s.getDiscountCyclesRemaining() - 1);
                } else {
                    s.setDiscountType("NONE");
                    s.setDiscountValue(BigDecimal.ZERO);
                    s.setDiscountCyclesRemaining(null);
                    data.put("discountEnded", true);
                }
            }
            LocalDate end = PeriodMath.periodEnd(nextStart, s.getBillingPeriod(), s.getBillingAnchorDay());
            s.setCurrentPeriodStart(nextStart);
            s.setCurrentPeriodEnd(end);
            s.setNextChargeOn(end.plusDays(1));
            recalcMrr(s);
            subscriptions.saveAndFlush(s);
            issuePeriodCharge(s, nextStart, end, s.getPrice(), s.getPlanId(), !"NONE".equals(s.getDiscountType()));
            data.put("periodStart", nextStart.toString());
            data.put("periodEnd", end.toString());
            events.record(s, "RENEWED", from, mrrBefore, nextStart, data);
        }
    }

    /** Emite por adelantado el cargo del siguiente periodo ({@code billing.chargeLeadDays}). Idempotente. */
    @Transactional
    public void issueNextChargeIfDue(Long id, LocalDate today, int leadDays) {
        Subscription s = lock(id);
        if ((s.getStatus() == SubscriptionStatus.ACTIVE || s.getStatus() == SubscriptionStatus.GRACE) && s.isAutoRenew()
                && !s.isCancelAtPeriodEnd() && s.getCurrentPeriodEnd() != null
                && !s.getCurrentPeriodEnd().plusDays(1).minusDays(leadDays).isAfter(today)) {
            issueNextPeriodCharge(s);
        }
    }

    private void issueNextPeriodCharge(Subscription s) {
        LocalDate nextStart = s.getCurrentPeriodEnd().plusDays(1);
        BillingPeriod period = s.hasPendingChange() ? s.getPendingBillingPeriod() : s.getBillingPeriod();
        BigDecimal price = s.hasPendingChange() ? s.getPendingPrice() : s.getPrice();
        Long planId = s.hasPendingChange() ? s.getPendingPlanId() : s.getPlanId();
        int anchor = s.getBillingAnchorDay();
        LocalDate end = PeriodMath.periodEnd(nextStart, period, anchor);
        boolean discounted = !"NONE".equals(s.getDiscountType())
                && (s.getDiscountCyclesRemaining() == null || s.getDiscountCyclesRemaining() > 0);
        BillingPeriod current = s.getBillingPeriod();
        s.setBillingPeriod(period);
        try {
            issuePeriodCharge(s, nextStart, end, price, planId, discounted);
        } finally {
            s.setBillingPeriod(current);
        }
    }

    /**
     * Cobranza → ciclo de vida. ACTIVE con adeudo vencido pasa a GRACE; GRACE sin adeudo vuelve a ACTIVE; GRACE con más
     * días de atraso que los de gracia se suspende SOLO si la política del producto es AUTO_AFTER_GRACE (nunca por
     * omisión). Una suspendida al corriente NO se reactiva sola: se avisa para que alguien la reactive.
     */
    @Transactional
    public void applyCollectionRules(Long id, LocalDate today) {
        Subscription s = lock(id);
        LocalDate oldest = charges.openForSubscription(id).stream().map(Charge::getDueDate).filter(d -> d.isBefore(today))
                .min(LocalDate::compareTo).orElse(null);
        int daysOverdue = oldest == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(oldest, today);
        SubscriptionStatus st = s.getStatus();
        if (st == SubscriptionStatus.ACTIVE && daysOverdue > 0) {
            s.setStatus(SubscriptionStatus.GRACE);
            subscriptions.saveAndFlush(s);
            events.record(s, "GRACE_STARTED", st, s.getMrr(), today, Map.of("daysOverdue", daysOverdue));
            notify(s, "GRACE_STARTED", st);
        } else if (st == SubscriptionStatus.GRACE && daysOverdue == 0) {
            s.setStatus(SubscriptionStatus.ACTIVE);
            subscriptions.saveAndFlush(s);
            events.record(s, "GRACE_RECOVERED", st, s.getMrr(), today, null);
            notify(s, "GRACE_RECOVERED", st);
        } else if (st == SubscriptionStatus.GRACE && daysOverdue > s.getGraceDays()) {
            Product product = catalog.product(s.getProductId());
            if ("AUTO_AFTER_GRACE".equals(product.getSuspensionPolicy())) {
                doSuspend(s, "Adeudo de " + daysOverdue + " días (gracia de " + s.getGraceDays() + ")", true);
            } else {
                notify(s, "GRACE_ENDED", st);
            }
        } else if (st == SubscriptionStatus.SUSPENDED && daysOverdue == 0
                && charges.openForSubscription(id).stream().noneMatch(c -> c.getDueDate().isBefore(today))) {
            notify(s, "READY_TO_REACTIVATE", st);
        }
    }

    // ── Apoyo ───────────────────────────────────────────────────────────────────────────

    public Subscription lock(Long id) {
        return subscriptions.lockById(id).orElseThrow(() -> new NotFoundException("Suscripción no encontrada"));
    }

    public Subscription require(Long id) {
        return subscriptions.findById(id).orElseThrow(() -> new NotFoundException("Suscripción no encontrada"));
    }

    private void notify(Subscription s, String type, SubscriptionStatus from) {
        publisher.publishEvent(new StatusChanged(s.getId(), s.getCode(), s.getCustomerId(), s.getProductId(), type, from, s.getStatus()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}

