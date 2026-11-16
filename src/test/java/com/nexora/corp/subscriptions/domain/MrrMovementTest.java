package com.nexora.corp.subscriptions.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MrrMovementTest {

    private static BigDecimal b(String v) {
        return new BigDecimal(v);
    }

    @Test
    void clasificaANivelCliente() {
        assertThat(MrrMovement.classify(b("0"), b("499"), false)).isEqualTo(MrrMovement.NEW);
        assertThat(MrrMovement.classify(b("0"), b("499"), true)).isEqualTo(MrrMovement.REACTIVATION);
        assertThat(MrrMovement.classify(b("499"), b("1398"), true)).isEqualTo(MrrMovement.EXPANSION);
        assertThat(MrrMovement.classify(b("1398"), b("899"), true)).isEqualTo(MrrMovement.CONTRACTION);
        assertThat(MrrMovement.classify(b("899"), b("0"), true)).isEqualTo(MrrMovement.CHURN);
        assertThat(MrrMovement.classify(b("899"), b("899"), true)).isEqualTo(MrrMovement.NONE);
    }
}

