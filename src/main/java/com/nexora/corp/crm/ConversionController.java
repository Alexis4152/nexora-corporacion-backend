package com.nexora.corp.crm;

import com.nexora.corp.crm.CrmDtos.ConvertRequest;
import com.nexora.corp.crm.CrmDtos.ConvertResponse;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prospects")
@RequiredArgsConstructor
public class ConversionController {

    private final ConversionService service;

    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAuthority('prospects.convert') and hasAuthority('subscriptions.create')")
    public ApiResponse<ConvertResponse> convert(@PathVariable Long id, @Valid @RequestBody ConvertRequest request) {
        return ApiResponse.ok(service.convert(id, request), "¡Oportunidad ganada! Cliente y suscripción creados");
    }
}
