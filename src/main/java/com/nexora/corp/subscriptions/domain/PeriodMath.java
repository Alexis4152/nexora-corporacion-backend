package com.nexora.corp.subscriptions.domain;

import com.nexora.corp.shared.domain.BillingPeriod;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Cálculo de periodos de facturación anclados a un día del mes. Si el ancla no existe en el mes (31 en febrero),
 * se usa el último día y en el mes siguiente se vuelve al ancla: 31-ene → 28/29-feb → 31-mar → 30-abr → 31-may.
 */
public final class PeriodMath {

    private PeriodMath() {
    }

    /** Inicio del periodo que sigue al que empieza en {@code start}. */
    public static LocalDate nextStart(LocalDate start, BillingPeriod period, int anchorDay) {
        YearMonth ym = YearMonth.from(start).plusMonths(period.months());
        return ym.atDay(Math.min(anchorDay, ym.lengthOfMonth()));
    }

    /** Último día (inclusivo) del periodo que empieza en {@code start}. */
    public static LocalDate periodEnd(LocalDate start, BillingPeriod period, int anchorDay) {
        return nextStart(start, period, anchorDay).minusDays(1);
    }

    /** Días del periodo [start, end] inclusivos. */
    public static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** Días restantes desde {@code from} (inclusive) hasta {@code end} (inclusive); 0 si ya pasó. */
    public static long remaining(LocalDate from, LocalDate end) {
        return from.isAfter(end) ? 0 : ChronoUnit.DAYS.between(from, end) + 1;
    }
}

