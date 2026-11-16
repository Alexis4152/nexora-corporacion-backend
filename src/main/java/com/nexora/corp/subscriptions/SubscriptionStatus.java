package com.nexora.corp.subscriptions;

import java.util.EnumSet;
import java.util.Set;

/** Ciclo de vida de la suscripción. El estado de cobranza es OTRO concepto (vista subscription_collection). */
public enum SubscriptionStatus {
    PENDING_ACTIVATION, TRIAL, ACTIVE, GRACE, SUSPENDED, CANCEL_SCHEDULED, CANCELLED, EXPIRED;

    /** Estados cuyo ingreso cuenta como recurrente contratado (MRR). */
    public static final Set<SubscriptionStatus> RECURRING = EnumSet.of(ACTIVE, GRACE, CANCEL_SCHEDULED);
    public static final Set<SubscriptionStatus> LIVE = EnumSet.of(PENDING_ACTIVATION, TRIAL, ACTIVE, GRACE, SUSPENDED, CANCEL_SCHEDULED);

    public boolean recurring() {
        return RECURRING.contains(this);
    }

    public boolean closed() {
        return this == CANCELLED || this == EXPIRED;
    }

    public String label() {
        return switch (this) {
            case PENDING_ACTIVATION -> "Pendiente de activación";
            case TRIAL -> "Prueba gratuita";
            case ACTIVE -> "Activa";
            case GRACE -> "En gracia";
            case SUSPENDED -> "Suspendida";
            case CANCEL_SCHEDULED -> "Cancelación programada";
            case CANCELLED -> "Cancelada";
            case EXPIRED -> "Expirada";
        };
    }
}

