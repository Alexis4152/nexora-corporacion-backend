package com.nexora.corp.shared.audit;

import lombok.Builder;

import java.util.Map;

/**
 * Evento de auditoría. {@code before}/{@code after} solo con datos permitidos: nunca contraseñas, hashes, tokens,
 * secretos ni datos de tarjeta.
 */
@Builder
public record AuditEntry(String module, String action, String entityType, Object entityId, Map<String, Object> before,
                         Map<String, Object> after, AuditResult result, String detail, Long actorUserId, String actorEmail) {

    public enum AuditResult { SUCCESS, DENIED, FAILED }

    public static AuditEntryBuilder success(String module, String action, String entityType, Object entityId) {
        return builder().module(module).action(action).entityType(entityType).entityId(entityId).result(AuditResult.SUCCESS);
    }

    public static AuditEntryBuilder of(String module, String action, AuditResult result) {
        return builder().module(module).action(action).result(result);
    }
}

