package com.nexora.corp.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Principal inmutable de cada request autenticada: usuario, sesión y permisos efectivos. Es lo único que ven
 * las capas superiores de quién está operando.
 */
public record AuthPrincipal(Long userId, UUID sessionId, String email, String fullName, Set<String> roles,
                            Set<String> permissions) {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public boolean isSuperAdmin() {
        return roles.contains(SUPER_ADMIN);
    }
}

