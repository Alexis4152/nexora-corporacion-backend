package com.nexora.corp.subscriptions.domain;

import java.math.BigDecimal;

/**
 * Clasificación de un cambio de MRR a nivel CLIENTE (no de suscripción), como se reporta en SaaS:
 * <ul>
 *   <li>NEW: el cliente pasa de 0 a &gt;0 y nunca había tenido MRR.</li>
 *   <li>REACTIVATION: pasa de 0 a &gt;0 y ya había tenido MRR antes.</li>
 *   <li>EXPANSION: ya tenía MRR y aumenta (nuevo producto, plan superior, fin de descuento).</li>
 *   <li>CONTRACTION: disminuye pero sigue &gt;0 (plan menor, cancelación de un producto de varios).</li>
 *   <li>CHURN: llega a 0 (cancelación, expiración o suspensión de su última suscripción).</li>
 * </ul>
 */
public enum MrrMovement {
    NONE, NEW, EXPANSION, CONTRACTION, CHURN, REACTIVATION;

    public static MrrMovement classify(BigDecimal customerBefore, BigDecimal customerAfter, boolean everHadMrr) {
        int cmp = customerAfter.compareTo(customerBefore);
        if (cmp == 0) {
            return NONE;
        }
        if (cmp > 0) {
            if (customerBefore.signum() == 0) {
                return everHadMrr ? REACTIVATION : NEW;
            }
            return EXPANSION;
        }
        return customerAfter.signum() == 0 ? CHURN : CONTRACTION;
    }
}

