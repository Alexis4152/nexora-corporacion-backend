package com.nexora.corp.shared.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permisos efectivos de un usuario, en caché por {@code users.permissions_version}. Al cambiar roles o permisos se
 * incrementa la versión de los afectados ({@link #bumpUsersOfRole}, {@link #bumpUser}) dentro de la misma
 * transacción, así el siguiente request recalcula. No depende de memoria compartida: sirve con varias instancias.
 * <p>
 * Regla: SUPER_ADMIN recibe todo el catálogo; los demás, la unión de los permisos de sus roles activos.
 */
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    public record Resolved(int version, Set<String> roles, Set<String> permissions) {
    }

    private final JdbcTemplate jdbc;
    private final Map<Long, Resolved> cache = new ConcurrentHashMap<>();

    public Resolved resolve(Long userId, int version) {
        Resolved cached = cache.get(userId);
        if (cached != null && cached.version() == version) {
            return cached;
        }
        Set<String> roles = Set.copyOf(jdbc.queryForList("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? AND r.active""",
                String.class, userId));
        Set<String> permissions = roles.contains(AuthPrincipal.SUPER_ADMIN)
                ? Set.copyOf(jdbc.queryForList("SELECT code FROM permissions", String.class))
                : Set.copyOf(jdbc.queryForList("""
                    SELECT DISTINCT p.code FROM user_roles ur
                    JOIN roles r ON r.id = ur.role_id AND r.active
                    JOIN role_permissions rp ON rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE ur.user_id = ?""", String.class, userId));
        Resolved resolved = new Resolved(version, roles, permissions);
        cache.put(userId, resolved);
        return resolved;
    }

    public void bumpUsersOfRole(Long roleId) {
        jdbc.update("UPDATE users SET permissions_version = permissions_version + 1 WHERE id IN "
                + "(SELECT user_id FROM user_roles WHERE role_id = ?)", roleId);
    }

    public void bumpUser(Long userId) {
        jdbc.update("UPDATE users SET permissions_version = permissions_version + 1 WHERE id = ?", userId);
    }
}

