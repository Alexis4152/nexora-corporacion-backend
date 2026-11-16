package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.RoleRef;
import com.nexora.corp.identity.IdentityDtos.SessionInfo;
import com.nexora.corp.identity.IdentityDtos.TemporaryPassword;
import com.nexora.corp.identity.IdentityDtos.UserRequest;
import com.nexora.corp.identity.IdentityDtos.UserRow;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.audit.Changes;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.PermissionResolver;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecureTokens;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Administración de usuarios internos. Toda operación que cambia accesos exige reautenticación reciente y queda
 * auditada. Salvaguardas: solo un SUPER_ADMIN otorga SUPER_ADMIN, nadie se desactiva a sí mismo y nunca se queda
 * la plataforma sin un SUPER_ADMIN activo.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final SessionStore sessions;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final ReauthGuard reauth;
    private final AuditService audit;
    private final AccountService accountService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<UserRow> list() {
        return users.findAll().stream().sorted(Comparator.comparing(User::getFirstName, String.CASE_INSENSITIVE_ORDER))
                .map(this::row).toList();
    }

    @Transactional(readOnly = true)
    public UserRow get(Long id) {
        return row(find(id));
    }

    UserRow row(User u) {
        return new UserRow(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getPhone(), u.getJobTitle(), u.isActive(),
                u.isMfaEnabled(), u.isLocked(clock.instant()), u.getLastLoginAt(),
                u.getRoles().stream().map(r -> new RoleRef(r.getId(), r.getCode(), r.getName()))
                        .sorted(Comparator.comparing(RoleRef::name)).toList(), u.getCreatedAt());
    }

    @Transactional
    public TemporaryPassword create(UserRequest request) {
        reauth.require();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ConflictException("Ya existe un usuario con ese correo");
        }
        Set<Role> assigned = resolveRoles(request.roleIds());
        String temp = SecureTokens.temporaryPassword();
        User user = users.save(User.builder().email(email).firstName(request.firstName().trim()).lastName(request.lastName().trim())
                .phone(blank(request.phone())).jobTitle(blank(request.jobTitle())).passwordHash(passwordEncoder.encode(temp))
                .mustChangePassword(true).roles(assigned).build());
        audit.record(AuditEntry.success("users", "USER_CREATED", "User", user.getId())
                .after(java.util.Map.of("email", email, "roles", assigned.stream().map(Role::getCode).sorted().toList())).build());
        return new TemporaryPassword(user.getId(), email, temp);
    }

    @Transactional
    public UserRow update(Long id, UserRequest request) {
        reauth.require();
        User user = find(id);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
            throw new ConflictException("Ya existe un usuario con ese correo");
        }
        Set<Role> assigned = resolveRoles(request.roleIds());
        boolean wasSuper = user.hasRole(Role.SUPER_ADMIN);
        boolean willBeSuper = assigned.stream().anyMatch(r -> r.getCode().equals(Role.SUPER_ADMIN));
        if (wasSuper && !willBeSuper) {
            requireAnotherSuperAdmin(user);
        }
        Changes changes = new Changes()
                .track("email", user.getEmail(), email)
                .track("firstName", user.getFirstName(), request.firstName().trim())
                .track("lastName", user.getLastName(), request.lastName().trim())
                .track("phone", user.getPhone(), blank(request.phone()))
                .track("jobTitle", user.getJobTitle(), blank(request.jobTitle()))
                .track("roles", user.getRoles().stream().map(Role::getCode).sorted().toList(), assigned.stream().map(Role::getCode).sorted().toList());
        user.setEmail(email);
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPhone(blank(request.phone()));
        user.setJobTitle(blank(request.jobTitle()));
        user.setRoles(assigned);
        users.saveAndFlush(user);
        permissionResolver.bumpUser(user.getId());
        if (!changes.isEmpty()) {
            audit.record(AuditEntry.success("users", "USER_UPDATED", "User", id).before(changes.before()).after(changes.after()).build());
        }
        return row(user);
    }

    @Transactional
    public UserRow setActive(Long id, boolean active) {
        reauth.require();
        User user = find(id);
        AuthPrincipal me = SecurityUtils.requirePrincipal();
        if (!active && me.userId().equals(id)) {
            throw new BusinessException("No puedes desactivar tu propia cuenta");
        }
        if (!active && user.hasRole(Role.SUPER_ADMIN)) {
            requireAnotherSuperAdmin(user);
        }
        user.setActive(active);
        if (!active) {
            sessions.revokeAllForUser(id, "USER_DEACTIVATED", null);
        }
        audit.record(AuditEntry.success("users", active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "User", id).build());
        return row(user);
    }

    @Transactional
    public TemporaryPassword resetPassword(Long id) {
        reauth.require();
        User user = find(id);
        String temp = SecureTokens.temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temp));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLockCount(0);
        sessions.revokeAllForUser(id, "ADMIN_PASSWORD_RESET", null);
        audit.record(AuditEntry.success("users", "USER_PASSWORD_RESET", "User", id).build());
        return new TemporaryPassword(id, user.getEmail(), temp);
    }

    @Transactional
    public void resetMfa(Long id) {
        reauth.require();
        User user = find(id);
        accountService.clearMfa(user);
        sessions.revokeAllForUser(id, "ADMIN_MFA_RESET", null);
        audit.record(AuditEntry.success("users", "USER_MFA_RESET", "User", id).build());
    }

    @Transactional
    public void unlock(Long id) {
        User user = find(id);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        user.setLockCount(0);
        audit.record(AuditEntry.success("users", "USER_UNLOCKED", "User", id).build());
    }

    @Transactional(readOnly = true)
    public List<SessionInfo> sessionsOf(Long id) {
        find(id);
        return sessions.activeSessions(id).stream().map(r -> AccountService.toInfo(r, (UUID) null)).toList();
    }

    @Transactional
    public int revokeSessions(Long id) {
        reauth.require();
        find(id);
        int n = sessions.revokeAllForUser(id, "ADMIN_REVOKED", null);
        audit.record(AuditEntry.success("users", "USER_SESSIONS_REVOKED", "User", id).detail(n + " sesiones").build());
        return n;
    }

    private Set<Role> resolveRoles(Set<Long> ids) {
        Set<Role> out = new HashSet<>(roles.findAllById(ids));
        if (out.size() != ids.size()) {
            throw new BusinessException("Alguno de los roles no existe");
        }
        if (out.stream().anyMatch(r -> !r.isActive())) {
            throw new BusinessException("No se puede asignar un rol inactivo");
        }
        boolean grantsSuper = out.stream().anyMatch(r -> r.getCode().equals(Role.SUPER_ADMIN));
        if (grantsSuper && !SecurityUtils.requirePrincipal().isSuperAdmin()) {
            throw new BusinessException("Solo un super administrador puede asignar el rol de super administrador");
        }
        return out;
    }

    private void requireAnotherSuperAdmin(User user) {
        if (user.isActive() && users.countActiveSuperAdmins() <= 1) {
            throw new BusinessException("Debe existir al menos otro super administrador activo");
        }
    }

    private User find(Long id) {
        return users.findWithRolesById(id).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

