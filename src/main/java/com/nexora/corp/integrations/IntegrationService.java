package com.nexora.corp.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.mail.MailService;
import com.nexora.corp.shared.security.CredentialEncryptionService;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecureTokens;
import com.nexora.corp.shared.security.SecurityUtils;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integraciones. Hoy hay una real (SMTP) y una API propia (estado de licencias para los productos Nexora). WhatsApp,
 * PAC/CFDI y pasarela quedan declaradas como pendientes: la interfaz existe en el modelo pero NO se pueden activar
 * hasta integrar un proveedor verificado (no se simulan).
 */
@Service
@RequiredArgsConstructor
public class IntegrationService {

    public record IntegrationRow(String code, String name, String description, String status, boolean enabled, Map<String, Object> config,
                                 boolean hasSecret, Instant lastTestAt, Boolean lastTestOk, String lastTestMessage, boolean available) {
    }

    public record SmtpRequest(@NotBlank(message = "Escribe el servidor") @Size(max = 150) @Pattern(regexp = "^[A-Za-z0-9.-]+$", message = "Servidor no válido") String host,
                              @NotNull @Min(1) @Max(65535) Integer port, @Size(max = 150) String username, @Size(max = 200) String password,
                              @NotBlank @Email(message = "Correo remitente no válido") @Size(max = 150) String from, boolean starttls, boolean enabled) {
    }

    public record ApiKeyRow(Long id, Long productId, String productCode, String name, String prefix, boolean active, Instant lastUsedAt,
                            Instant createdAt, Instant revokedAt) {
    }

    public record NewApiKey(Long id, String key) {
    }

    private static final Map<String, String[]> META = Map.of(
            "SMTP", new String[]{"Correo SMTP", "Envío real de recuperación de contraseña, alertas y recordatorios de cobranza."},
            "WHATSAPP", new String[]{"WhatsApp Business", "Recordatorios por WhatsApp. Requiere integrar un proveedor (Meta Cloud API, Twilio)."},
            "PAC_CFDI", new String[]{"Facturación CFDI (PAC)", "Emisión de CFDI 4.0 y complementos de pago a través de un PAC autorizado por el SAT."},
            "PAYMENT_GATEWAY", new String[]{"Pasarela de pagos", "Cobro con tarjeta / domiciliación. Requiere autorización del cliente."});

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryption;
    private final MailService mail;
    private final ReauthGuard reauth;
    private final AuditService audit;

    public List<IntegrationRow> list() {
        return jdbc.query("SELECT code, enabled, config::text, secret_enc IS NOT NULL, last_test_at, last_test_ok, last_test_msg FROM integration_configs ORDER BY code",
                (rs, i) -> {
                    String code = rs.getString(1);
                    boolean available = "SMTP".equals(code);
                    Map<String, Object> config;
                    try {
                        config = objectMapper.readValue(rs.getString(3), Map.class);
                    } catch (Exception e) {
                        config = Map.of();
                    }
                    Timestamp t = rs.getTimestamp(5);
                    String[] meta = META.getOrDefault(code, new String[]{code, ""});
                    String status = !available ? "PENDING_PROVIDER" : rs.getBoolean(2) ? "ENABLED" : "DISABLED";
                    return new IntegrationRow(code, meta[0], meta[1], status, rs.getBoolean(2), config, rs.getBoolean(4),
                            t == null ? null : t.toInstant(), (Boolean) rs.getObject(6), rs.getString(7), available);
                });
    }

    @Transactional
    public List<IntegrationRow> saveSmtp(SmtpRequest r) {
        reauth.require();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", r.host().trim());
        config.put("port", r.port());
        config.put("username", r.username() == null ? "" : r.username().trim());
        config.put("from", r.from().trim());
        config.put("starttls", r.starttls());
        try {
            String json = objectMapper.writeValueAsString(config);
            if (r.password() != null && !r.password().isBlank()) {
                jdbc.update("UPDATE integration_configs SET enabled = ?, config = ?::jsonb, secret_enc = ?, updated_at = now(), updated_by = ?, version = version + 1 WHERE code = 'SMTP'",
                        r.enabled(), json, encryption.encrypt(r.password()), SecurityUtils.currentUserId());
            } else {
                jdbc.update("UPDATE integration_configs SET enabled = ?, config = ?::jsonb, updated_at = now(), updated_by = ?, version = version + 1 WHERE code = 'SMTP'",
                        r.enabled(), json, SecurityUtils.currentUserId());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        audit.record(AuditEntry.success("settings", "INTEGRATION_UPDATED", "Integration", "SMTP")
                .after(Map.of("host", r.host(), "port", r.port(), "enabled", r.enabled(), "passwordChanged", r.password() != null && !r.password().isBlank())).build());
        return list();
    }

    @Transactional
    public IntegrationRow testSmtp() {
        String to = SecurityUtils.requirePrincipal().email();
        MailService.Result result = mail.send(to, "Prueba de correo · Nexora Corporación",
                "<p>La configuración SMTP de Nexora Corporación funciona correctamente.</p>");
        String msg = result.sent() ? "Correo de prueba enviado a " + to : result.error();
        jdbc.update("UPDATE integration_configs SET last_test_at = now(), last_test_ok = ?, last_test_msg = ? WHERE code = 'SMTP'",
                result.sent(), msg.length() > 300 ? msg.substring(0, 300) : msg);
        audit.record(AuditEntry.success("settings", "INTEGRATION_TESTED", "Integration", "SMTP").detail(msg).build());
        return list().stream().filter(i -> i.code().equals("SMTP")).findFirst().orElseThrow();
    }

    public List<ApiKeyRow> apiKeys() {
        return jdbc.query("""
                SELECT k.id, k.product_id, p.code, k.name, k.key_prefix, k.active, k.last_used_at, k.created_at, k.revoked_at
                FROM product_api_keys k JOIN products p ON p.id = k.product_id ORDER BY k.created_at DESC""", (rs, i) -> new ApiKeyRow(rs.getLong(1),
                rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)),
                ts(rs.getTimestamp(9))));
    }

    /** La llave en claro se muestra UNA vez; en BD solo queda su hash. */
    @Transactional
    public NewApiKey createKey(Long productId, String name) {
        reauth.require();
        Integer ok = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ?", Integer.class, productId);
        if (ok == null || ok == 0) {
            throw new NotFoundException("Producto no encontrado");
        }
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new BusinessException("Escribe un nombre para la llave (máx. 80)");
        }
        String raw = "nxk_" + SecureTokens.randomToken();
        Long id = jdbc.queryForObject("INSERT INTO product_api_keys (product_id, name, key_prefix, key_hash, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, productId, name.trim(), raw.substring(0, 12), SecureTokens.sha256(raw), SecurityUtils.currentUserId());
        audit.record(AuditEntry.success("settings", "API_KEY_CREATED", "ApiKey", id).after(Map.of("product", productId, "prefix", raw.substring(0, 12))).build());
        return new NewApiKey(id, raw);
    }

    @Transactional
    public void revokeKey(Long id) {
        reauth.require();
        jdbc.update("UPDATE product_api_keys SET active = FALSE, revoked_at = now(), revoked_by = ? WHERE id = ? AND active", SecurityUtils.currentUserId(), id);
        audit.record(AuditEntry.success("settings", "API_KEY_REVOKED", "ApiKey", id).build());
    }

    public Long productForKey(String raw) {
        if (raw == null || !raw.startsWith("nxk_") || raw.length() > 100) {
            return null;
        }
        List<Long> ids = jdbc.queryForList("SELECT product_id FROM product_api_keys WHERE key_hash = ? AND active", Long.class, SecureTokens.sha256(raw));
        if (ids.isEmpty()) {
            return null;
        }
        jdbc.update("UPDATE product_api_keys SET last_used_at = now() WHERE key_hash = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')",
                SecureTokens.sha256(raw));
        return ids.get(0);
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static JsonNode none() {
        return null;
    }
}

