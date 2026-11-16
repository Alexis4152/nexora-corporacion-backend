package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.ActiveRequest;
import com.nexora.corp.identity.IdentityDtos.SessionInfo;
import com.nexora.corp.identity.IdentityDtos.TemporaryPassword;
import com.nexora.corp.identity.IdentityDtos.UserRequest;
import com.nexora.corp.identity.IdentityDtos.UserRow;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('users.read') or hasAuthority('users.manage')")
    public ApiResponse<List<UserRow>> list() {
        return ApiResponse.ok(service.list());
    }

    /** Lista mínima para asignar responsables (ejecutivos, soporte, cobranza): cualquier usuario autenticado. */
    @GetMapping("/directory")
    public ApiResponse<List<IdentityDtos.RoleRef>> directory() {
        return ApiResponse.ok(service.list().stream().filter(UserRow::active)
                .map(u -> new IdentityDtos.RoleRef(u.id(), u.email(), u.firstName() + " " + u.lastName())).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('users.read') or hasAuthority('users.manage')")
    public ApiResponse<UserRow> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<TemporaryPassword> create(@Valid @RequestBody UserRequest request) {
        return ApiResponse.ok(service.create(request), "Usuario creado");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<UserRow> update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return ApiResponse.ok(service.update(id, request), "Usuario actualizado");
    }

    @PostMapping("/{id}/active")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<UserRow> active(@PathVariable Long id, @Valid @RequestBody ActiveRequest request) {
        return ApiResponse.ok(service.setActive(id, request.active()));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<TemporaryPassword> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(service.resetPassword(id), "Contraseña temporal generada");
    }

    @PostMapping("/{id}/reset-mfa")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<Void> resetMfa(@PathVariable Long id) {
        service.resetMfa(id);
        return ApiResponse.ok(null, "Verificación en dos pasos restablecida");
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<Void> unlock(@PathVariable Long id) {
        service.unlock(id);
        return ApiResponse.ok(null, "Cuenta desbloqueada");
    }

    @GetMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<List<SessionInfo>> sessions(@PathVariable Long id) {
        return ApiResponse.ok(service.sessionsOf(id));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<Integer> revokeSessions(@PathVariable Long id) {
        return ApiResponse.ok(service.revokeSessions(id), "Sesiones cerradas");
    }
}

