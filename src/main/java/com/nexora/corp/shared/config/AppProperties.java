package com.nexora.corp.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Parámetros técnicos de despliegue ({@code app.*}) validados al arrancar: sin secretos la aplicación no inicia.
 * Solo infraestructura: la configuración de negocio vive en la tabla {@code system_settings}.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String frontendUrl,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cookie cookie,
        @Valid @NotNull Encryption encryption,
        @Valid @NotNull Storage storage,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap) {

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }

    public record Jwt(
            @NotBlank(message = "Define APP_JWT_SECRET (mínimo 32 caracteres)")
            @Size(min = 32, message = "APP_JWT_SECRET debe tener al menos 32 caracteres") String secret,
            @NotNull Duration accessTtl,
            @NotNull Duration refreshTtl) {
    }

    public record Cookie(boolean secure, @NotBlank String sameSite) {
    }

    public record Encryption(
            @NotBlank(message = "Define APP_ENCRYPTION_KEY (mínimo 32 caracteres)")
            @Size(min = 32, message = "APP_ENCRYPTION_KEY debe tener al menos 32 caracteres") String key) {
    }

    public record Storage(@NotBlank String dir, @Min(1024) long maxBytes) {
    }

    /**
     * Protecciones con pisos mínimos: no se pueden relajar por debajo de lo seguro.
     *
     * @param reauthWindow vigencia de la confirmación de contraseña para operaciones sensibles
     * @param jobsEnabled  tareas programadas (se apagan en pruebas automatizadas)
     */
    public record Security(
            @DefaultValue("true") boolean rateLimitEnabled,
            @DefaultValue("5") @Min(3) @Max(10) int maxFailedLogins,
            @DefaultValue("PT15M") Duration lockDuration,
            @DefaultValue("PT30M") Duration resetTokenTtl,
            @DefaultValue("PT10M") Duration reauthWindow,
            @DefaultValue("true") boolean jobsEnabled) {
    }

    public record Bootstrap(String superAdminEmail, String superAdminPassword,
                            @DefaultValue("false") boolean demoData, String demoPassword) {
    }
}

