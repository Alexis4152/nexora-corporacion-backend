package com.nexora.corp.crm;

import com.nexora.corp.billing.BillingDtos.PaymentRequest;
import com.nexora.corp.billing.Payment;
import com.nexora.corp.billing.PaymentService;
import com.nexora.corp.crm.CrmDtos.ConvertRequest;
import com.nexora.corp.crm.CrmDtos.ConvertResponse;
import com.nexora.corp.customers.Customer;
import com.nexora.corp.customers.CustomerService;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.subscriptions.Subscription;
import com.nexora.corp.subscriptions.SubscriptionDtos.CreateRequest;
import com.nexora.corp.subscriptions.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversión de una oportunidad ganada en cliente + suscripción (+ primer pago) en UNA transacción: si cualquier paso
 * falla (duplicado, plan sin precio, referencia de pago repetida) no queda nada a medias. Conserva el historial del
 * prospecto y nunca duplica clientes: si el correo o RFC ya existe, exige ligarlo al cliente existente.
 */
@Service
@RequiredArgsConstructor
public class ConversionService {

    private final ProspectService prospects;
    private final ProspectRepository repository;
    private final SalesActivityRepository activities;
    private final CustomerService customers;
    private final SubscriptionService subscriptions;
    private final PaymentService payments;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public ConvertResponse convert(Long prospectId, ConvertRequest r) {
        Prospect p = prospects.visibleForUpdate(prospectId);
        if (p.getConvertedCustomerId() != null) {
            throw new ConflictException("Este prospecto ya se convirtió en cliente");
        }
        if (p.getStage() != ProspectStage.WON && !p.getStage().canMoveTo(ProspectStage.WON)) {
            throw new BusinessException("Solo se convierten oportunidades calificadas o ganadas");
        }
        Customer customer;
        if (r.existingCustomerId() != null) {
            customer = customers.require(r.existingCustomerId());
        } else {
            if (r.customer() == null) {
                throw new BusinessException("Captura los datos del cliente o elige uno existente");
            }
            if (!SecurityUtils.has("customers.create")) {
                throw new BusinessException("No tienes permiso para registrar clientes");
            }
            Customer dup = customers.findDuplicate(r.customer().email(), r.customer().rfc());
            if (dup != null) {
                throw new BusinessException("Ya existe el cliente " + dup.getCode() + " · " + dup.getTradeName()
                        + " con ese correo o RFC. Elige «Usar cliente existente».", "DUPLICATE_CUSTOMER:" + dup.getId());
            }
            customer = customers.createEntity(r.customer(), p.getId());
        }
        Subscription sub = subscriptions.create(new CreateRequest(customer.getId(), r.productId(), r.planId(), r.billingPeriod(), r.price(),
                r.startDate(), r.trial(), r.trialDays(), null, null, null, null, null, null, r.couponCode(),
                "Alta por conversión del prospecto #" + p.getId(), false, p.getOwnerUserId()));
        Long paymentId = null;
        if (r.firstPayment() != null) {
            if (r.trial()) {
                throw new BusinessException("Una prueba gratuita no lleva primer pago");
            }
            if (!SecurityUtils.has("payments.create")) {
                throw new BusinessException("No tienes permiso para registrar pagos; deja el primer pago para Finanzas");
            }
            Payment payment = payments.register(new PaymentRequest(customer.getId(), r.firstPayment().amount(),
                    r.firstPayment().receivedOn() != null ? r.firstPayment().receivedOn() : r.startDate(), r.firstPayment().method(),
                    r.firstPayment().reference(), "Primer pago de la contratación " + sub.getCode(), "AUTO", sub.getId(), null, false),
                    "conversion-prospect-" + p.getId() + "-" + sub.getId());
            paymentId = payment.getId();
        }
        ProspectStage from = p.getStage();
        p.setStage(ProspectStage.WON);
        p.setProbability(100);
        p.setStageChangedAt(clock.instant());
        p.setConvertedCustomerId(customer.getId());
        p.setConvertedAt(clock.instant());
        repository.save(p);
        activities.save(SalesActivity.builder().prospectId(p.getId()).customerId(customer.getId()).type("CONVERSION")
                .subject("Convertido en cliente " + customer.getCode() + " con " + sub.getCode()).completedAt(clock.instant())
                .userId(SecurityUtils.currentUserId()).build());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("customer", customer.getCode());
        after.put("subscription", sub.getCode());
        after.put("payment", paymentId);
        after.put("fromStage", from.name());
        audit.record(AuditEntry.success("prospects", "PROSPECT_CONVERTED", "Prospect", p.getId()).after(after).build());
        return new ConvertResponse(customer.getId(), customer.getCode(), sub.getId(), sub.getCode(), paymentId);
    }
}

