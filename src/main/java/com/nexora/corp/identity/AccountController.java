package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.CodeRequest;
import com.nexora.corp.identity.IdentityDtos.MfaSetupResponse;
import com.nexora.corp.identity.IdentityDtos.RecoveryCodesResponse;
import com.nexora.corp.identity.IdentityDtos.SessionInfo;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService account;

    @PostMapping("/mfa/setup")
    public ApiResponse<MfaSetupResponse> setup() {
        return ApiResponse.ok(account.startMfaSetup());
    }

    @PostMapping("/mfa/enable")
    public ApiResponse<RecoveryCodesResponse> enable(@Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(new RecoveryCodesResponse(account.enableMfa(request.code())), "Verificación en dos pasos activada");
    }

    @PostMapping("/mfa/disable")
    public ApiResponse<Void> disable(@Valid @RequestBody CodeRequest request) {
        account.disableMfa(request.code());
        return ApiResponse.ok(null, "Verificación en dos pasos desactivada");
    }

    @PostMapping("/mfa/recovery-codes")
    public ApiResponse<RecoveryCodesResponse> recovery() {
        return ApiResponse.ok(new RecoveryCodesResponse(account.regenerateRecoveryCodes()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionInfo>> sessions() {
        return ApiResponse.ok(account.mySessions());
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> revoke(@PathVariable UUID id) {
        account.revokeMySession(id);
        return ApiResponse.ok(null, "Sesión cerrada");
    }

    @PostMapping("/sessions/revoke-others")
    public ApiResponse<Integer> revokeOthers() {
        return ApiResponse.ok(account.revokeOtherSessions(), "Se cerraron las demás sesiones");
    }
}

