package com.nexora.corp.subscriptions.domain;

import com.nexora.corp.shared.domain.BillingPeriod;
import com.nexora.corp.shared.util.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reglas de importes (docs/04-REGLAS-FINANCIERAS.md):
 * <ul>
 *   <li>El descuento se aplica sobre el precio del periodo; porcentaje redondeado HALF_UP a 2 decimales.</li>
 *   <li>Precio con IVA incluido: subtotal = bruto / (1 + tasa), IVA = bruto − subtotal (el total no cambia).</li>
 *   <li>Precio sin IVA: subtotal = bruto, IVA = subtotal × tasa, total = subtotal + IVA.</li>
 *   <li>MRR = subtotal sin IVA ÷ meses del periodo, con descuento vigente, a 4 decimales. Solo cuenta en estados
 *       ACTIVE, GRACE y CANCEL_SCHEDULED (ingreso recurrente contratado y vigente).</li>
 * </ul>
 */
public final class Pricing {

    public record Amounts(BigDecimal amount, BigDecimal discount, BigDecimal subtotal, BigDecimal tax, BigDecimal total) {
    }

    private Pricing() {
    }

    public static BigDecimal discount(BigDecimal amount, String type, BigDecimal value) {
        if (type == null || "NONE".equals(type) || value == null || value.signum() <= 0) {
            return Money.ZERO;
        }
        BigDecimal d = "PERCENT".equals(type)
                ? amount.multiply(value).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP)
                : Money.of(value);
        return Money.min(d, Money.of(amount));
    }

    public static Amounts amounts(BigDecimal amount, BigDecimal discount, boolean taxIncluded, BigDecimal taxRate) {
        BigDecimal a = Money.of(amount);
        BigDecimal d = Money.of(discount);
        BigDecimal gross = a.subtract(d);
        if (taxIncluded) {
            BigDecimal subtotal = gross.divide(BigDecimal.ONE.add(taxRate), 2, RoundingMode.HALF_UP);
            return new Amounts(a, d, subtotal, gross.subtract(subtotal), gross);
        }
        BigDecimal tax = gross.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        return new Amounts(a, d, gross, tax, gross.add(tax));
    }

    /** Ingreso recurrente mensual normalizado sin IVA. */
    public static BigDecimal monthly(BigDecimal price, BillingPeriod period, String discountType, BigDecimal discountValue,
                                     boolean taxIncluded, BigDecimal taxRate) {
        BigDecimal gross = price.subtract(discount(price, discountType, discountValue));
        BigDecimal net = taxIncluded ? gross.divide(BigDecimal.ONE.add(taxRate), 6, RoundingMode.HALF_UP) : gross;
        return net.divide(BigDecimal.valueOf(period.months()), 4, RoundingMode.HALF_UP);
    }

    /**
     * Prorrateo de un cambio inmediato a un plan más caro dentro del mismo periodo: se cobra la diferencia por los días
     * restantes, (nuevo − anterior) × díasRestantes ÷ díasDelPeriodo. Nunca negativo (las reducciones aplican al
     * final del periodo, sin reembolsos).
     */
    public static BigDecimal proration(BigDecimal oldPeriodPrice, BigDecimal newPeriodPrice, long remainingDays, long periodDays) {
        if (periodDays <= 0 || remainingDays <= 0) {
            return Money.ZERO;
        }
        BigDecimal diff = newPeriodPrice.subtract(oldPeriodPrice);
        if (diff.signum() <= 0) {
            return Money.ZERO;
        }
        return diff.multiply(BigDecimal.valueOf(remainingDays)).divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
    }
}

