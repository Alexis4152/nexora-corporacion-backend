package com.nexora.corp.identity;

/**
 * Evento de seguridad publicado por identidad y consumido por notificaciones (sin dependencia directa entre módulos).
 *
 * @param type NEW_LOCATION_LOGIN, ACCOUNT_LOCKED, TOKEN_REUSE, MFA_DISABLED, PASSWORD_CHANGED
 */
public record SecurityEvent(Long userId, String type, String detail) {
}

