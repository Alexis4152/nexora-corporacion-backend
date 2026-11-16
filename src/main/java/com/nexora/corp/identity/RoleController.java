package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.PermissionResponse;
import com.nexora.corp.identity.IdentityDtos.RoleRequest;
import com.nexora.corp.identity.IdentityDtos.RoleResponse;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService service;

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('roles.manage') or hasAuthority('users.manage') or hasAuthority('users.read')")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('roles.manage') or hasAuthority('users.read')")
    public ApiResponse<List<PermissionResponse>> permissions() {
        return ApiResponse.ok(service.catalog());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(service.create(request), "Rol creado");
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleResponse> update(@PathVariable Long id, @Valid @RequestBody RoleRequest request) {
        return ApiResponse.ok(service.update(id, request), "Permisos actualizados");
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, "Rol eliminado");
    }
}

