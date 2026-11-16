package com.nexora.corp.subscriptions.domain;

import com.nexora.corp.shared.domain.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodMathTest {

    @Test
    void renovacionEnFinDeMesRegresaAlAncla() {
        LocalDate start = LocalDate.of(2026, 1, 31);
        LocalDate feb = PeriodMath.nextStart(start, BillingPeriod.MONTHLY, 31);
        assertThat(feb).isEqualTo(LocalDate.of(2026, 2, 28));
        LocalDate mar = PeriodMath.nextStart(feb, BillingPeriod.MONTHLY, 31);
        assertThat(mar).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(PeriodMath.nextStart(mar, BillingPeriod.MONTHLY, 31)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(PeriodMath.periodEnd(start, BillingPeriod.MONTHLY, 31)).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    @Test
    void anioBisiestoYAnual() {
        assertThat(PeriodMath.nextStart(LocalDate.of(2028, 1, 29), BillingPeriod.MONTHLY, 29)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(PeriodMath.nextStart(LocalDate.of(2028, 2, 29), BillingPeriod.ANNUAL, 29)).isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(PeriodMath.periodEnd(LocalDate.of(2026, 3, 15), BillingPeriod.QUARTERLY, 15)).isEqualTo(LocalDate.of(2026, 6, 14));
    }

    @Test
    void diasRestantes() {
        assertThat(PeriodMath.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEqualTo(31);
        assertThat(PeriodMath.remaining(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31))).isEqualTo(11);
        assertThat(PeriodMath.remaining(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31))).isZero();
    }
}

