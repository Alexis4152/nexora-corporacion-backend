package com.nexora.corp.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Bitácora inmutable (triggers impiden UPDATE/DELETE/TRUNCATE).
 * <ul>
 *   <li>{@link #record}: dentro de la transacción del cambio; si el cambio se revierte, el evento también.</li>
 *   <li>{@link #recordIndependent}: en transacción propia, para lo que debe quedar aunque la operación falle
 *       (login fallido, acceso denegado, reutilización de token). Nunca rompe el flujo.</li>
 * </ul>
 */
@Slf4j
@Service
public class AuditService {

    private static final String INSERT = """
            INSERT INTO audit_logs (actor_user_id, actor_email, action, module, entity_type, entity_id, before_data,
                                    after_data, result, detail, correlation_id, ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(AuditEntry entry) {
        insert(entry);
    }

    public void recordIndependent(AuditEntry entry) {
        try {
            requiresNew.executeWithoutResult(s -> insert(entry));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar el evento de auditoría {}: {}", entry.action(), e.getMessage());
        }
    }

    private void insert(AuditEntry e) {
        AuthPrincipal principal = SecurityUtils.currentPrincipal().orElse(null);
        jdbc.update(INSERT,
                e.actorUserId() != null ? e.actorUserId() : principal != null ? principal.userId() : null,
                e.actorEmail() != null ? e.actorEmail() : principal != null ? principal.email() : null,
                e.action(), e.module(), e.entityType(), e.entityId() == null ? null : String.valueOf(e.entityId()),
                json(e.before()), json(e.after()), e.result().name(), truncate(e.detail(), 500),
                MDC.get("requestId"), SecurityUtils.clientIp(), SecurityUtils.userAgent());
    }

    private String json(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el detalle de auditoría", ex);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

