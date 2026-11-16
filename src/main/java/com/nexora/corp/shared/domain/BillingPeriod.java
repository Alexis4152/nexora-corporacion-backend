package com.nexora.corp.shared.domain;

/** Periodicidad de cobro. {@code months} normaliza a mes para MRR (anual / 12, trimestral / 3…). */
public enum BillingPeriod {
    MONTHLY(1, "Mensual"), QUARTERLY(3, "Trimestral"), SEMIANNUAL(6, "Semestral"), ANNUAL(12, "Anual");

    private final int months;
    private final String label;

    BillingPeriod(int months, String label) {
        this.months = months;
        this.label = label;
    }

    public int months() {
        return months;
    }

    public String label() {
        return label;
    }
}

