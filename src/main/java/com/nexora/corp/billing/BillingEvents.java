package com.nexora.corp.billing;

import java.math.BigDecimal;
import java.util.List;

/** Eventos de dominio de cobranza (desacoplan billing de suscripciones y notificaciones). */
public final class BillingEvents {

    private BillingEvents() {
    }

    /** Cambió el saldo de suscripciones (pago, desaplicación, ajuste): revisar estados de gracia/cobranza. */
    public record BalanceChanged(Long customerId, List<Long> subscriptionIds) {
    }

    public record PaymentRegistered(Long paymentId, String folio, Long customerId, String customerName, BigDecimal amount,
                                    List<Long> subscriptionIds) {
    }

    public record PaymentReversed(Long paymentId, String folio, Long customerId, BigDecimal amount, String reason) {
    }
}

