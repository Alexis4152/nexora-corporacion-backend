package com.nexora.corp.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class IdentityDtos {

    private IdentityDtos() {
    }

    public record LoginRequest(@NotBlank(message = "Escribe tu correo") @Email(message = "Correo no válido") @Size(max = 150) String email,
                               @NotBlank(message = "Escribe tu contraseña") @Size(max = 72) String password) {
    }

    public record MfaRequest(@NotBlank String mfaToken,
                             @NotBlank(message = "Escribe el código") @Size(max = 20) String code) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 150) String email) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 100) String token,
                                       @NotBlank(message = "Escribe la nueva contraseña") @Size(max = 72) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "Escribe tu contraseña actual") @Size(max = 72) String currentPassword,
                                        @NotBlank(message = "Escribe la nueva contraseña") @Size(max = 72) String newPassword) {
    }

    public record ReauthRequest(@NotBlank(message = "Escribe tu contraseña") @Size(max = 72) String password) {
    }

    public record CodeRequest(@NotBlank(message = "Escribe el código de 6 dígitos") @Pattern(regexp = "^\\d{6}$", message = "El código tiene 6 dígitos") String code) {
    }

    public record SessionUser(Long id, String email, String firstName, String lastName, String jobTitle, List<String> roles,
                              List<String> permissions, boolean mustChangePassword, boolean mfaEnabled) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResponse(String accessToken, Long expiresIn, SessionUser user, Boolean mfaRequired, String mfaToken) {

        public static SessionResponse of(String token, long expiresIn, SessionUser user) {
            return new SessionResponse(token, expiresIn, user, null, null);
        }

        public static SessionResponse mfa(String mfaToken) {
            return new SessionResponse(null, null, null, true, mfaToken);
        }
    }

    public record MfaSetupResponse(String secret, String otpauthUri, String qrDataUri) {
    }

    public record RecoveryCodesResponse(List<String> codes) {
    }

    public record SessionInfo(String id, String ip, String userAgent, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
                              boolean current) {
    }

    // ── Administración de usuarios ──────────────────────────────────────────────────────

    public record UserRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 100) String firstName,
            @NotBlank(message = "Escribe los apellidos") @Size(max = 100) String lastName,
            @NotBlank(message = "Escribe el correo") @Email(message = "Correo no válido") @Size(max = 150) String email,
            @Pattern(regexp = "^$|^\\d{10}$", message = "El teléfono debe tener 10 dígitos") String phone,
            @Size(max = 100) String jobTitle,
            @NotEmpty(message = "Asigna al menos un rol") Set<Long> roleIds) {
    }

    public record UserRow(Long id, String email, String firstName, String lastName, String phone, String jobTitle, boolean active,
                          boolean mfaEnabled, boolean locked, Instant lastLoginAt, List<RoleRef> roles, Instant createdAt) {
    }

    public record RoleRef(Long id, String code, String name) {
    }

    public record TemporaryPassword(Long userId, String email, String temporaryPassword) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }

    // ── Roles ────────────────────────────────────────────────────────────────────────────

    public record RoleRequest(
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$", message = "Código en mayúsculas, números y guion bajo") String code,
            @NotBlank(message = "Escribe el nombre") @Size(max = 80) String name,
            @Size(max = 250) String description,
            @NotNull Set<String> permissions) {
    }

    public record RoleResponse(Long id, String code, String name, String description, boolean system, boolean active,
                               List<String> permissions, long users) {
    }

    public record PermissionResponse(String code, String module, String description, boolean sensitive) {
    }
}

