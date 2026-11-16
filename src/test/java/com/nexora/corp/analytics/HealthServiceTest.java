package com.nexora.corp.analytics;

import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthServiceTest {

    private static Map<String, Object> facts(int daysOverdue, int late, int open, int cancelled, LocalDate since) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", 1L);
        m.put("customer_since", Date.valueOf(since));
        m.put("days_overdue", daysOverdue);
        m.put("late_charges", late);
        m.put("open_tickets", open);
        m.put("urgent_tickets", 0);
        m.put("tickets90", open);
        m.put("cancelled_subs", cancelled);
        m.put("suspended_subs", 0);
        m.put("scheduled_cancel", 0);
        return m;
    }

    @Test
    void reglaExplicable() {
        var healthy = HealthService.compute(facts(0, 0, 0, 0, LocalDate.now().minusYears(2)));
        assertThat(healthy.score()).isEqualTo(100);
        assertThat(healthy.level()).isEqualTo("HEALTHY");
        var risk = HealthService.compute(facts(45, 4, 3, 1, LocalDate.now().minusMonths(6)));
        assertThat(risk.score()).isEqualTo(100 - 35 - 15 - 15 - 10);
        assertThat(risk.level()).isEqualTo("RISK");
        assertThat(risk.factors()).extracting(HealthService.Factor::key).contains("overdue", "late", "tickets", "cancelled");
    }
}
