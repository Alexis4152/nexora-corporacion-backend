package com.nexora.corp.identity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.nexora.corp.identity.IdentityDtos.MfaSetupResponse;
import com.nexora.corp.identity.IdentityDtos.SessionInfo;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.NotFoundException;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.CredentialEncryptionService;
import com.nexora.corp.shared.security.ReauthGuard;
import com.nexora.corp.shared.security.SecureTokens;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.security.Totp;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** Cuenta propia: segundo factor (TOTP + códigos de recuperación) y sesiones abiertas. */
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository users;
    private final SessionStore sessions;
    private final CredentialEncryptionService encryption;
    private final ReauthGuard reauth;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public MfaSetupResponse startMfaSetup() {
        User user = current();
        if (user.isMfaEnabled()) {
            throw new BusinessException("La verificación en dos pasos ya está activa");
        }
        String secret = Totp.newSecret();
        user.setMfaPendingSecretEnc(encryption.encrypt(secret));
        String uri = Totp.otpauthUri("Nexora Corporación", user.getEmail(), secret);
        return new MfaSetupResponse(secret, uri, qr(uri));
    }

    @Transactional
    public List<String> enableMfa(String code) {
        User user = current();
        if (user.getMfaPendingSecretEnc() == null) {
            throw new BusinessException("Primero genera el código QR");
        }
        String secret = encryption.decrypt(user.getMfaPendingSecretEnc());
        OptionalLong step = Totp.verify(secret, code, clock.instant(), null);
        if (step.isEmpty()) {
            throw new BusinessException("El código no coincide. Revisa que la hora de tu teléfono sea correcta.");
        }
        user.setMfaSecretEnc(user.getMfaPendingSecretEnc());
        user.setMfaPendingSecretEnc(null);
        user.setMfaEnabled(true);
        user.setMfaLastStep(step.getAsLong());
        audit.record(AuditEntry.success("auth", "MFA_ENABLED", "User", user.getId()).build());
        return newRecoveryCodes(user.getId());
    }

    @Transactional
    public void disableMfa(String code) {
        reauth.require();
        User user = current();
        if (!user.isMfaEnabled()) {
            return;
        }
        if (Totp.verify(encryption.decrypt(user.getMfaSecretEnc()), code, clock.instant(), user.getMfaLastStep()).isEmpty()) {
            throw new BusinessException("El código no es correcto");
        }
        clearMfa(user);
        audit.record(AuditEntry.success("auth", "MFA_DISABLED", "User", user.getId()).build());
        events.publishEvent(new SecurityEvent(user.getId(), "MFA_DISABLED", "Se desactivó la verificación en dos pasos"));
    }

    @Transactional
    public List<String> regenerateRecoveryCodes() {
        reauth.require();
        User user = current();
        if (!user.isMfaEnabled()) {
            throw new BusinessException("Activa primero la verificación en dos pasos");
        }
        audit.record(AuditEntry.success("auth", "MFA_RECOVERY_REGENERATED", "User", user.getId()).build());
        return newRecoveryCodes(user.getId());
    }

    void clearMfa(User user) {
        user.setMfaEnabled(false);
        user.setMfaSecretEnc(null);
        user.setMfaPendingSecretEnc(null);
        user.setMfaLastStep(null);
        jdbc.update("DELETE FROM mfa_recovery_codes WHERE user_id = ?", user.getId());
    }

    private List<String> newRecoveryCodes(Long userId) {
        jdbc.update("DELETE FROM mfa_recovery_codes WHERE user_id = ?", userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = SecureTokens.randomCode(RECOVERY_ALPHABET, 10);
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
            jdbc.update("INSERT INTO mfa_recovery_codes (user_id, code_hash) VALUES (?, ?)", userId, SecureTokens.sha256(code));
        }
        return codes;
    }

    public List<SessionInfo> mySessions() {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        return sessions.activeSessions(p.userId()).stream().map(r -> toInfo(r, p.sessionId())).toList();
    }

    public static SessionInfo toInfo(Map<String, Object> r, UUID current) {
        UUID id = (UUID) r.get("id");
        return new SessionInfo(id.toString(), (String) r.get("ip"), (String) r.get("user_agent"),
                ((Timestamp) r.get("created_at")).toInstant(), ((Timestamp) r.get("last_seen_at")).toInstant(),
                ((Timestamp) r.get("expires_at")).toInstant(), id.equals(current));
    }

    @Transactional
    public void revokeMySession(UUID sessionId) {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        if (!sessions.belongsTo(sessionId, p.userId())) {
            throw new NotFoundException("Sesión no encontrada");
        }
        sessions.revoke(sessionId, "USER_REVOKED");
        audit.record(AuditEntry.success("auth", "SESSION_REVOKED", "Session", sessionId).build());
    }

    @Transactional
    public int revokeOtherSessions() {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        int n = sessions.revokeAllForUser(p.userId(), "USER_REVOKED_OTHERS", p.sessionId());
        audit.record(AuditEntry.success("auth", "SESSIONS_REVOKED", "User", p.userId()).detail(n + " sesiones").build());
        return n;
    }

    private User current() {
        return users.findById(SecurityUtils.requirePrincipal().userId()).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    private static String qr(String text) {
        try {
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240, Map.of(EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el código QR", e);
        }
    }
}

