package com.nexora.corp.integrations;

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
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService service;

    @GetMapping
    @PreAuthorize("hasAuthority('integrations.manage') or hasAuthority('settings.read')")
    public ApiResponse<List<IntegrationService.IntegrationRow>> list() {
        return ApiResponse.ok(service.list());
    }

    @PutMapping("/smtp")
    @PreAuthorize("hasAuthority('integrations.manage')")
    public ApiResponse<List<IntegrationService.IntegrationRow>> smtp(@Valid @RequestBody IntegrationService.SmtpRequest request) {
        return ApiResponse.ok(service.saveSmtp(request), "Configuración de correo guardada");
    }

    @PostMapping("/smtp/test")
    @PreAuthorize("hasAuthority('integrations.manage')")
    public ApiResponse<IntegrationService.IntegrationRow> test() {
        return ApiResponse.ok(service.testSmtp());
    }

    @GetMapping("/api-keys")
    @PreAuthorize("hasAuthority('integrations.manage')")
    public ApiResponse<List<IntegrationService.ApiKeyRow>> keys() {
        return ApiResponse.ok(service.apiKeys());
    }

    @PostMapping("/api-keys")
    @PreAuthorize("hasAuthority('integrations.manage')")
    public ApiResponse<IntegrationService.NewApiKey> create(@RequestBody Map<String, Object> body) {
        Object productId = body.get("productId");
        return ApiResponse.ok(service.createKey(productId == null ? null : Long.valueOf(String.valueOf(productId)), (String) body.get("name")),
                "Llave creada. Cópiala ahora: no se volverá a mostrar.");
    }

    @DeleteMapping("/api-keys/{id}")
    @PreAuthorize("hasAuthority('integrations.manage')")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        service.revokeKey(id);
        return ApiResponse.ok(null, "Llave revocada");
    }
}

