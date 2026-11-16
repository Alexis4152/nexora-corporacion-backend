package com.nexora.corp.billing;

import com.nexora.corp.billing.BillingDtos.AllocationInput;
import com.nexora.corp.billing.BillingDtos.PaymentRequest;
import com.nexora.corp.billing.BillingDtos.RefundRequest;
import com.nexora.corp.customers.Customer;
import com.nexora.corp.customers.CustomerRepository;
import com.nexora.corp.settings.SettingsService;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.util.Folios;
import com.nexora.corp.shared.util.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pagos y su aplicación a cargos. Defensas contra cobros duplicados, en capas:
 * <ol>
 *   <li>{@code Idempotency-Key} obligatoria (un doble clic o reintento de red devuelve el mismo pago);</li>
 *   <li>bloqueo de la fila del cliente: dos administradores registrando pagos del mismo cliente se serializan;</li>
 *   <li>referencia bancaria única por método mientras el pago esté vigente (índice único en BD);</li>
 *   <li>bloqueo de cada cargo al aplicar y restricción CHECK de saldo en BD (paid + ajustes ≤ total).</li>
 * </ol>
 * Nada se borra: rechazos y reversos cambian el estado y desaplican con fecha, usuario y motivo.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentAllocationRepository allocations;
    private final ChargeRepository charges;
    private final CustomerRepository customers;
    private final SettingsService settings;
    private final Folios folios;
    private final AuditService audit;
    private final ReauthGuard reauth;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public Payment register(PaymentRequest r, String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9-]{16,80}$")) {
            throw new BusinessException("Falta la llave de idempotencia de la operación");
        }
        var replay = payments.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Payment p = replay.get();
            if (!p.getCustomerId().equals(r.customerId()) || p.getAmount().compareTo(Money.of(r.amount())) != 0) {
                throw new ConflictException("La llave de idempotencia ya se usó para otro pago");
            }
            return p;
        }
        Customer customer = customers.lockById(r.customerId()).orElseThrow(() -> new NotFoundException("Cliente no encontrado"));
        LocalDate today = settings.today();
        if (r.receivedOn().isAfter(today)) {
            throw new BusinessException("La fecha de pago no puede ser futura");
        }
        String reference = r.reference() == null || r.reference().isBlank() ? null : r.reference().trim();
        if (reference == null && !Set.of("CASH", "OTHER").contains(r.method())) {
            throw new BusinessException("Escribe la referencia bancaria o de la operación");
        }
        if (reference != null) {
            Integer dup = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payments WHERE method = ? AND upper(reference) = ? AND status NOT IN ('REJECTED', 'REVERSED')""",
                    Integer.class, r.method(), reference.toUpperCase(Locale.ROOT));
            if (dup != null && dup > 0) {
                throw new ConflictException("Ya existe un pago vigente con esa referencia y método. Revisa que no lo estés registrando dos veces.");
            }
        }
        boolean confirm = r.confirmNow() && SecurityUtils.has("payments.confirm");
        Long me = SecurityUtils.currentUserId();
        Payment payment = payments.saveAndFlush(Payment.builder().folio(folios.next("payment_folio_seq", "PG", 6)).customerId(customer.getId())
                .receivedOn(r.receivedOn()).amount(Money.of(r.amount())).currency(settings.currency()).method(r.method()).reference(reference)
                .status(confirm ? "CONFIRMED" : "CAPTURED").confirmedAt(confirm ? clock.instant() : null).confirmedBy(confirm ? me : null)
                .notes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim()).idempotencyKey(idempotencyKey)
                .registeredBy(me).build());
        List<Long> subs = switch (r.allocationMode()) {
            case "AUTO" -> autoAllocate(payment, r.subscriptionId());
            case "MANUAL" -> allocate(payment, r.allocations() == null ? List.of() : r.allocations());
            default -> List.of();
        };
        audit.record(AuditEntry.success("billing", "PAYMENT_REGISTERED", "Payment", payment.getId())
                .after(Map.of("folio", payment.getFolio(), "customer", customer.getCode(), "amount", payment.getAmount(),
                        "method", payment.getMethod(), "status", payment.getStatus(), "allocatedTo", subs)).build());
        events.publishEvent(new BillingEvents.PaymentRegistered(payment.getId(), payment.getFolio(), customer.getId(),
                customer.getTradeName(), payment.getAmount(), subs));
        if (!subs.isEmpty()) {
            events.publishEvent(new BillingEvents.BalanceChanged(customer.getId(), subs));
        }
        return payment;
    }

    /** Aplica el saldo disponible del pago a los cargos abiertos más antiguos (opcionalmente de una suscripción). */
    private List<Long> autoAllocate(Payment payment, Long subscriptionId) {
        BigDecimal available = unapplied(payment);
        List<AllocationInput> plan = new ArrayList<>();
        for (Charge c : subscriptionId == null ? charges.lockOpenForCustomer(payment.getCustomerId())
                : charges.lockOpenForSubscription(payment.getCustomerId(), subscriptionId)) {
            if (available.signum() <= 0) {
                break;
            }
            BigDecimal take = Money.min(available, c.balance());
            plan.add(new AllocationInput(c.getId(), take));
            available = available.subtract(take);
        }
        return allocate(payment, plan);
    }

    private List<Long> allocate(Payment payment, List<AllocationInput> inputs) {
        if (!payment.valid()) {
            throw new BusinessException("El pago no está vigente");
        }
        BigDecimal available = unapplied(payment);
        BigDecimal requested = inputs.stream().map(i -> Money.of(i.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (requested.compareTo(available) > 0) {
            throw new BusinessException("Las aplicaciones (" + requested + ") superan el saldo disponible del pago (" + available + ")");
        }
        Set<Long> subs = new LinkedHashSet<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (AllocationInput in : inputs) {
            if (!seen.add(in.chargeId())) {
                throw new BusinessException("Un cargo aparece dos veces en la aplicación");
            }
            Charge c = charges.lockById(in.chargeId()).orElseThrow(() -> new NotFoundException("Cargo no encontrado"));
            if (!c.getCustomerId().equals(payment.getCustomerId())) {
                throw new BusinessException("El cargo " + c.getFolio() + " no pertenece al cliente del pago");
            }
            if (!c.open()) {
                throw new BusinessException("El cargo " + c.getFolio() + " no tiene saldo pendiente");
            }
            BigDecimal amount = Money.of(in.amount());
            if (amount.compareTo(c.balance()) > 0) {
                throw new BusinessException("El importe aplicado a " + c.getFolio() + " supera su saldo (" + c.balance() + ")");
            }
            allocations.save(PaymentAllocation.builder().paymentId(payment.getId()).chargeId(c.getId()).amount(amount)
                    .createdBy(SecurityUtils.currentUserId()).build());
            c.setPaidAmount(c.getPaidAmount().add(amount));
            c.refreshStatus();
            charges.save(c);
            if (c.getSubscriptionId() != null) {
                subs.add(c.getSubscriptionId());
            }
        }
        allocations.flush();
        return new ArrayList<>(subs);
    }

    /** Aplica saldo a favor de un pago existente a cargos (reasignación controlada). */
    @Transactional
    public Payment applyCredit(Long paymentId, List<AllocationInput> inputs) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        customers.lockById(p.getCustomerId());
        List<Long> subs = inputs.isEmpty() ? autoAllocate(p, null) : allocate(p, inputs);
        audit.record(AuditEntry.success("billing", "PAYMENT_APPLIED", "Payment", paymentId)
                .after(Map.of("allocations", inputs.stream().map(i -> i.chargeId() + "=" + i.amount()).toList())).build());
        if (!subs.isEmpty()) {
            events.publishEvent(new BillingEvents.BalanceChanged(p.getCustomerId(), subs));
        }
        return p;
    }

    /** Desaplica una aplicación: el cargo vuelve a tener saldo y el importe regresa como saldo a favor del pago. */
    @Transactional
    public void unapply(Long allocationId, String reason) {
        reauth.require();
        PaymentAllocation a = allocations.findById(allocationId).orElseThrow(() -> new NotFoundException("Aplicación no encontrada"));
        Payment p = payments.lockById(a.getPaymentId()).orElseThrow();
        customers.lockById(p.getCustomerId());
        if (a.getReversedAt() != null) {
            throw new BusinessException("La aplicación ya fue revertida");
        }
        Long sub = reverseAllocation(a, reason);
        audit.record(AuditEntry.success("billing", "PAYMENT_UNAPPLIED", "Payment", p.getId())
                .after(Map.of("allocation", allocationId, "amount", a.getAmount())).detail(reason).build());
        if (sub != null) {
            events.publishEvent(new BillingEvents.BalanceChanged(p.getCustomerId(), List.of(sub)));
        }
    }

    private Long reverseAllocation(PaymentAllocation a, String reason) {
        Charge c = charges.lockById(a.getChargeId()).orElseThrow();
        a.setReversedAt(clock.instant());
        a.setReversedBy(SecurityUtils.currentUserId());
        a.setReversalReason(reason);
        allocations.save(a);
        c.setPaidAmount(c.getPaidAmount().subtract(a.getAmount()));
        c.refreshStatus();
        charges.save(c);
        return c.getSubscriptionId();
    }

    @Transactional
    public Payment confirm(Long id) {
        Payment p = payments.lockById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        if (!"CAPTURED".equals(p.getStatus())) {
            throw new BusinessException("Solo se confirman pagos capturados");
        }
        p.setStatus("CONFIRMED");
        p.setConfirmedAt(clock.instant());
        p.setConfirmedBy(SecurityUtils.currentUserId());
        audit.record(AuditEntry.success("billing", "PAYMENT_CONFIRMED", "Payment", id).before(Map.of("status", "CAPTURED"))
                .after(Map.of("status", "CONFIRMED")).build());
        return p;
    }

    @Transactional
    public Payment reconcile(Long id) {
        Payment p = payments.lockById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        if (!"CONFIRMED".equals(p.getStatus())) {
            throw new BusinessException("Solo se concilian pagos confirmados");
        }
        p.setStatus("RECONCILED");
        p.setReconciledAt(clock.instant());
        p.setReconciledBy(SecurityUtils.currentUserId());
        audit.record(AuditEntry.success("billing", "PAYMENT_RECONCILED", "Payment", id).before(Map.of("status", "CONFIRMED"))
                .after(Map.of("status", "RECONCILED")).build());
        return p;
    }

    /** El dinero nunca llegó (transferencia devuelta, depósito no localizado): se desaplica todo. */
    @Transactional
    public Payment reject(Long id, String reason) {
        Payment p = payments.lockById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        if (!"CAPTURED".equals(p.getStatus()) && !"CONFIRMED".equals(p.getStatus())) {
            throw new BusinessException("Solo se rechazan pagos capturados o confirmados sin conciliar");
        }
        String before = p.getStatus();
        List<Long> subs = reverseAll(p, "Pago rechazado: " + reason);
        p.setStatus("REJECTED");
        p.setRejectedAt(clock.instant());
        p.setRejectedBy(SecurityUtils.currentUserId());
        p.setRejectReason(reason);
        audit.record(AuditEntry.success("billing", "PAYMENT_REJECTED", "Payment", id).before(Map.of("status", before))
                .after(Map.of("status", "REJECTED")).detail(reason).build());
        if (!subs.isEmpty()) {
            events.publishEvent(new BillingEvents.BalanceChanged(p.getCustomerId(), subs));
        }
        return p;
    }

    /** Reverso auditable (error de captura, contracargo). Exige reautenticación; no aplica si hubo devoluciones. */
    @Transactional
    public Payment reverse(Long id, String reason) {
        reauth.require();
        Payment p = payments.lockById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        customers.lockById(p.getCustomerId());
        if (!p.valid()) {
            throw new BusinessException("El pago ya no está vigente");
        }
        BigDecimal refunded = refunded(id);
        if (refunded.signum() > 0) {
            throw new BusinessException("El pago tiene devoluciones registradas; no se puede revertir");
        }
        String before = p.getStatus();
        List<Long> subs = reverseAll(p, "Pago revertido: " + reason);
        p.setStatus("REVERSED");
        p.setReversedAt(clock.instant());
        p.setReversedBy(SecurityUtils.currentUserId());
        p.setReversalReason(reason);
        audit.record(AuditEntry.success("billing", "PAYMENT_REVERSED", "Payment", id).before(Map.of("status", before, "amount", p.getAmount()))
                .after(Map.of("status", "REVERSED")).detail(reason).build());
        events.publishEvent(new BillingEvents.PaymentReversed(p.getId(), p.getFolio(), p.getCustomerId(), p.getAmount(), reason));
        if (!subs.isEmpty()) {
            events.publishEvent(new BillingEvents.BalanceChanged(p.getCustomerId(), subs));
        }
        return p;
    }

    private List<Long> reverseAll(Payment p, String reason) {
        Set<Long> subs = new LinkedHashSet<>();
        for (PaymentAllocation a : allocations.activeForPayment(p.getId())) {
            Long sub = reverseAllocation(a, reason);
            if (sub != null) {
                subs.add(sub);
            }
        }
        return new ArrayList<>(subs);
    }

    /** Devolución del saldo a favor (nunca de lo ya aplicado a cargos: primero se desaplica). */
    @Transactional
    public Payment refund(Long id, RefundRequest r) {
        reauth.require();
        Payment p = payments.lockById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        if (!"CONFIRMED".equals(p.getStatus()) && !"RECONCILED".equals(p.getStatus())) {
            throw new BusinessException("Solo se devuelven pagos confirmados");
        }
        BigDecimal amount = Money.of(r.amount());
        BigDecimal available = unapplied(p);
        if (amount.compareTo(available) > 0) {
            throw new BusinessException("La devolución supera el saldo a favor del pago (" + available + ")");
        }
        if (r.refundedOn().isAfter(settings.today())) {
            throw new BusinessException("La fecha de devolución no puede ser futura");
        }
        jdbc.update("INSERT INTO payment_refunds (payment_id, amount, refunded_on, method, reference, reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, amount, r.refundedOn(), r.method(), r.reference() == null || r.reference().isBlank() ? null : r.reference().trim(),
                r.reason().trim(), SecurityUtils.currentUserId());
        audit.record(AuditEntry.success("billing", "PAYMENT_REFUNDED", "Payment", id).after(Map.of("amount", amount, "method", r.method()))
                .detail(r.reason()).build());
        return p;
    }

    public BigDecimal unapplied(Payment p) {
        return p.valid() ? p.getAmount().subtract(allocations.allocatedFor(p.getId())).subtract(refunded(p.getId())) : Money.ZERO;
    }

    private BigDecimal refunded(Long paymentId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payment_refunds WHERE payment_id = ?", BigDecimal.class, paymentId);
    }

    public Payment require(Long id) {
        return payments.findById(id).orElseThrow(() -> new NotFoundException("Pago no encontrado"));
    }
}
