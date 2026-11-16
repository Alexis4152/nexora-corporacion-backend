package com.nexora.corp.crm;

import java.util.EnumSet;
import java.util.Set;

/** Etapas del embudo con su probabilidad sugerida y reglas de transición (docs/04 §Embudo). */
public enum ProspectStage {
    NEW(10), CONTACTED(20), QUALIFIED(35), DEMO_SCHEDULED(45), DEMO_DONE(55), QUOTE_SENT(65), NEGOTIATION(80),
    WON(100), LOST(0), DISCARDED(0);

    private final int probability;

    ProspectStage(int probability) {
        this.probability = probability;
    }

    public int probability() {
        return probability;
    }

    public boolean isOpen() {
        return this != WON && this != LOST && this != DISCARDED;
    }

    private static final Set<ProspectStage> BEFORE_QUALIFIED = EnumSet.of(NEW, CONTACTED);

    /**
     * Reglas: entre etapas abiertas se avanza o retrocede libremente; desde cualquier etapa abierta se puede perder o
     * descartar; ganar exige haber calificado la oportunidad; una oportunidad ganada ya no se mueve; una perdida o
     * descartada solo se reabre a NUEVO o CONTACTADO.
     */
    public boolean canMoveTo(ProspectStage target) {
        if (this == target) {
            return false;
        }
        if (this == WON) {
            return false;
        }
        if (this == LOST || this == DISCARDED) {
            return target == NEW || target == CONTACTED;
        }
        if (target == WON) {
            return !BEFORE_QUALIFIED.contains(this);
        }
        return true;
    }
}

