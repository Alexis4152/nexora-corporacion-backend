package com.nexora.corp.subscriptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.subscriptions.domain.MrrMovement;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Historial inmutable de la suscripción con su efecto en el MRR del cliente. Se llama SIEMPRE dentro de la misma
 * transacción del cambio, después de fijar el nuevo MRR de la suscripción.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionEvents {

    private final JdbcTemplate jdbc;
    private final SubscriptionRepository subscriptions;
    private final ObjectMapper objectMapper;

    public MrrMovement record(Subscription s, String type, SubscriptionStatus from, BigDecimal mrrBefore, LocalDate effective,
                              Map<String, Object> data) {
        BigDecimal others = subscriptions.otherMrr(s.getCustomerId(), s.getId());
        BigDecimal before = others.add(mrrBefore);
        BigDecimal after = others.add(s.getMrr());
        Boolean ever = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM subscription_events WHERE customer_id = ? AND mrr_after > 0)""",
                Boolean.class, s.getCustomerId());
        MrrMovement movement = MrrMovement.classify(before, after, Boolean.TRUE.equals(ever));
        try {
            jdbc.update("""
                    INSERT INTO subscription_events (subscription_id, customer_id, product_id, type, from_status, to_status, effective_on,
                                                     mrr_before, mrr_after, movement, data, user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
                    s.getId(), s.getCustomerId(), s.getProductId(), type, from == null ? null : from.name(), s.getStatus().name(),
                    effective, mrrBefore, s.getMrr(), movement.name(), data == null ? null : objectMapper.writeValueAsString(data),
                    SecurityUtils.currentUserId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return movement;
    }
}

