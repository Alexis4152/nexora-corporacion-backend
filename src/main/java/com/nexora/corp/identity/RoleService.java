package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.PermissionResponse;
import com.nexora.corp.identity.IdentityDtos.RoleRequest;
import com.nexora.corp.identity.IdentityDtos.RoleResponse;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.ConflictException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.PermissionResolver;
import com.nexora.corp.shared.security.ReauthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Roles con permisos granulares. SUPER_ADMIN no se edita; los roles de sistema no se eliminan ni cambian de código. */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final PermissionResolver resolver;
    private final ReauthGuard reauth;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        Map<Long, Long> counts = jdbc.query("SELECT role_id, COUNT(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id "
                        + "WHERE u.active GROUP BY role_id",
                rs -> {
                    Map<Long, Long> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getLong(1), rs.getLong(2));
                    }
                    return m;
                });
        return roles.findAllByOrderBySystemDescNameAsc().stream().map(r -> response(r, counts.getOrDefault(r.getId(), 0L))).toList();
    }

    public List<PermissionResponse> catalog() {
        return permissions.findAllByOrderBySortOrderAsc().stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getModule(), p.getDescription(), p.isSensitive())).toList();
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        reauth.require();
        if (request.code() == null || request.code().isBlank()) {
            throw new BusinessException("Escribe el código del rol");
        }
        if (roles.findByCode(request.code()).isPresent()) {
            throw new ConflictException("Ya existe un rol con ese código");
        }
        Role role = roles.save(Role.builder().code(request.code()).name(request.name().trim()).description(request.description())
                .permissions(resolve(request.permissions())).build());
        audit.record(AuditEntry.success("users", "ROLE_CREATED", "Role", role.getId())
                .after(Map.of("code", role.getCode(), "permissions", codes(role))).build());
        return response(role, 0);
    }

    @Transactional
    public RoleResponse update(Long id, RoleRequest request) {
        reauth.require();
        Role role = roles.findWithPermissionsById(id).orElseThrow(() -> new NotFoundException("Rol no encontrado"));
        if (Role.SUPER_ADMIN.equals(role.getCode())) {
            throw new BusinessException("El rol de super administrador siempre tiene todos los permisos y no se modifica");
        }
        List<String> before = codes(role);
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setPermissions(resolve(request.permissions()));
        roles.saveAndFlush(role);
        resolver.bumpUsersOfRole(id);
        audit.record(AuditEntry.success("users", "ROLE_PERMISSIONS_CHANGED", "Role", id)
                .before(Map.of("permissions", before)).after(Map.of("permissions", codes(role))).build());
        return response(role, 0);
    }

    @Transactional
    public void delete(Long id) {
        reauth.require();
        Role role = roles.findById(id).orElseThrow(() -> new NotFoundException("Rol no encontrado"));
        if (role.isSystem()) {
            throw new BusinessException("Los roles de sistema no se eliminan");
        }
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE role_id = ?", Integer.class, id);
        if (users != null && users > 0) {
            throw new BusinessException("El rol tiene usuarios asignados; reasígnalos antes de eliminarlo");
        }
        roles.delete(role);
        audit.record(AuditEntry.success("users", "ROLE_DELETED", "Role", id).before(Map.of("code", role.getCode())).build());
    }

    private Set<Permission> resolve(Set<String> codes) {
        Set<Permission> found = new HashSet<>(permissions.findByCodeIn(codes));
        if (found.size() != codes.size()) {
            throw new BusinessException("Alguno de los permisos no existe");
        }
        return found;
    }

    private static List<String> codes(Role r) {
        return r.getPermissions().stream().map(Permission::getCode).sorted().toList();
    }

    private static RoleResponse response(Role r, long users) {
        return new RoleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystem(), r.isActive(),
                r.getPermissions().stream().map(Permission::getCode).sorted().collect(Collectors.toList()), users);
    }
}

