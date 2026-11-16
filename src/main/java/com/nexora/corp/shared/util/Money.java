package com.nexora.corp.shared.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Política monetaria (docs/04-REGLAS-FINANCIERAS.md): importes en BigDecimal con 2 decimales y redondeo
 * HALF_UP en cada importe que se cobra; los cálculos intermedios de MRR usan 4 decimales. Nunca float/double.
 */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal of(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String v) {
        return of(new BigDecimal(v));
    }

    public static BigDecimal precise(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(4) : v.setScale(4, RoundingMode.HALF_UP);
    }

    public static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }
}

