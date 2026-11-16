package com.nexora.corp.settings;

import com.nexora.corp.shared.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    /** Datos públicos para cualquier usuario autenticado: marca, moneda y zona (los usa toda la interfaz). */
    @GetMapping("/branding")
    public ApiResponse<Map<String, Object>> branding() {
        return ApiResponse.ok(Map.of(
                "companyName", settings.string(Setting.COMPANY_NAME),
                "primaryColor", settings.string(Setting.PRIMARY_COLOR),
                "accentColor", settings.string(Setting.ACCENT_COLOR),
                "currency", settings.currency(),
                "timezone", settings.zone().getId(),
                "taxRate", settings.taxRate(),
                "today", settings.today().toString()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.read') or hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Map<String, Object>>> all() {
        return ApiResponse.ok(settings.all());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<Map<String, Map<String, Object>>> update(@RequestBody Map<String, Object> changes) {
        return ApiResponse.ok(settings.update(changes), "Configuración guardada");
    }
}

