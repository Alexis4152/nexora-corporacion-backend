package com.nexora.corp.subscriptions.domain;

import com.nexora.corp.shared.domain.BillingPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PricingTest {

    private static final BigDecimal IVA = new BigDecimal("0.16");

    @Test
    void precioMasIva() {
        Pricing.Amounts a = Pricing.amounts(new BigDecimal("899"), BigDecimal.ZERO, false, IVA);
        assertThat(a.subtotal()).isEqualByComparingTo("899.00");
        assertThat(a.tax()).isEqualByComparingTo("143.84");
        assertThat(a.total()).isEqualByComparingTo("1042.84");
    }

    @Test
    void precioConIvaIncluidoNoCambiaElTotal() {
        Pricing.Amounts a = Pricing.amounts(new BigDecimal("1160"), BigDecimal.ZERO, true, IVA);
        assertThat(a.total()).isEqualByComparingTo("1160.00");
        assertThat(a.subtotal()).isEqualByComparingTo("1000.00");
        assertThat(a.tax()).isEqualByComparingTo("160.00");
    }

    @Test
    void descuentoPorcentualYMaximoDelImporte() {
        assertThat(Pricing.discount(new BigDecimal("499"), "PERCENT", new BigDecimal("20"))).isEqualByComparingTo("99.80");
        assertThat(Pricing.discount(new BigDecimal("100"), "AMOUNT", new BigDecimal("150"))).isEqualByComparingTo("100.00");
        assertThat(Pricing.discount(new BigDecimal("100"), "NONE", new BigDecimal("50"))).isEqualByComparingTo("0");
    }

    @Test
    void mrrNormalizadoSinIvaYConDescuento() {
        assertThat(Pricing.monthly(new BigDecimal("12000"), BillingPeriod.ANNUAL, "NONE", BigDecimal.ZERO, false, IVA)).isEqualByComparingTo("1000.0000");
        assertThat(Pricing.monthly(new BigDecimal("1160"), BillingPeriod.MONTHLY, "NONE", BigDecimal.ZERO, true, IVA)).isEqualByComparingTo("1000.0000");
        assertThat(Pricing.monthly(new BigDecimal("499"), BillingPeriod.MONTHLY, "PERCENT", new BigDecimal("20"), false, IVA)).isEqualByComparingTo("399.2000");
        // Dos productos de un cliente: 499 + 899 = 1,398 de MRR (ejemplo del prompt).
        assertThat(Pricing.monthly(new BigDecimal("499"), BillingPeriod.MONTHLY, "NONE", BigDecimal.ZERO, false, IVA)
                .add(Pricing.monthly(new BigDecimal("899"), BillingPeriod.MONTHLY, "NONE", BigDecimal.ZERO, false, IVA))).isEqualByComparingTo("1398");
    }

    @Test
    void prorrateoSoloParaSubirYProporcionalALosDias() {
        assertThat(Pricing.proration(new BigDecimal("499"), new BigDecimal("899"), 15, 30)).isEqualByComparingTo("200.00");
        assertThat(Pricing.proration(new BigDecimal("899"), new BigDecimal("499"), 15, 30)).isEqualByComparingTo("0");
        assertThat(Pricing.proration(new BigDecimal("499"), new BigDecimal("899"), 0, 30)).isEqualByComparingTo("0");
    }
}

