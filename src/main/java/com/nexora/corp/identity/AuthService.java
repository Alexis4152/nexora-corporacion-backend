package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.ChangePasswordRequest;
import com.nexora.corp.identity.IdentityDtos.ResetPasswordRequest;
import com.nexora.corp.identity.IdentityDtos.SessionResponse;
import com.nexora.corp.identity.IdentityDtos.SessionUser;
import com.nexora.corp.shared.audit.AuditEntry;
import com.nexora.corp.shared.audit.AuditEntry.AuditResult;
import com.nexora.corp.shared.audit.AuditService;
import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.error.BusinessException;
import com.nexora.corp.shared.error.InvalidSessionException;
import com.nexora.corp.shared.mail.MailService;
import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.CredentialEncryptionService;
import com.nexora.corp.shared.security.JwtTokenProvider;
import com.nexora.corp.shared.security.PermissionResolver;
import com.nexora.corp.shared.security.SecureTokens;
import com.nexora.corp.shared.security.SecurityUtils;
import com.nexora.corp.shared.security.Totp;
import com.nexora.corp.shared.util.Validation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Autenticación: contraseña (BCrypt) → segundo factor TOTP si está activo → sesión. Respuestas idénticas exista o
 * no la cuenta (anti-enumeración), bloqueo temporal progresivo y auditoría de cada intento.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public record LoginResult(SessionResponse session, String refreshToken) {
    }

    private static final Duration MAX_LOCK = Duration.ofHours(4);

    private final UserRepository users;
    private final SessionStore sessions;
    private final JwtTokenProvider tokens;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final CredentialEncryptionService encryption;
    private final AuditService audit;
    private final MailService mail;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Environment environment;
    private final Clock clock;

    private volatile String dummyHash;

    // Sin @Transactional (lección de Citas/HOTEL): el contador de intentos debe quedar aunque falle el login.
    public LoginResult login(String rawEmail, String password) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash());
            audit.recordIndependent(AuditEntry.of("auth", "LOGIN_FAILED", AuditResult.FAILED).actorEmail(email)
                    .detail("Cuenta inexistente").build());
            throw new BadCredentialsException("bad");
        }
        User user = found.get();
        if (user.isLocked(now)) {
            passwordEncoder.matches(password, dummyHash());
            audit.recordIndependent(event(user, "LOGIN_BLOCKED", AuditResult.DENIED, "Cuenta bloqueada temporalmente"));
            throw new BadCredentialsException("locked");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            registerFailure(user, now, "Contraseña incorrecta");
            throw new BadCredentialsException("bad");
        }
        if (!user.isActive()) {
            audit.recordIndependent(event(user, "LOGIN_DENIED", AuditResult.DENIED, "Cuenta desactivada"));
            throw new BadCredentialsException("inactive");
        }
        if (user.isMfaEnabled()) {
            audit.recordIndependent(event(user, "LOGIN_PASSWORD_OK", AuditResult.SUCCESS, "Pendiente de segundo factor"));
            return new LoginResult(SessionResponse.mfa(tokens.mfaToken(user.getId())), null);
        }
        return openSession(user, now);
    }

    public LoginResult verifyMfa(String mfaToken, String code) {
        Long userId = tokens.parseMfa(mfaToken)
                .orElseThrow(() -> new InvalidSessionException("La verificación expiró. Inicia sesión de nuevo."));
        User user = users.findWithRolesById(userId).filter(User::isActive)
                .orElseThrow(() -> new InvalidSessionException("La verificación expiró. Inicia sesión de nuevo."));
        Instant now = clock.instant();
        if (user.isLocked(now)) {
            throw new BadCredentialsException("locked");
        }
        String clean = code.replaceAll("[\\s-]", "");
        boolean ok;
        if (clean.matches("^\\d{6}$")) {
            OptionalLong step = Totp.verify(encryption.decrypt(user.getMfaSecretEnc()), clean, now, user.getMfaLastStep());
            ok = step.isPresent();
            if (ok) {
                user.setMfaLastStep(step.getAsLong());
            }
        } else {
            ok = consumeRecoveryCode(user.getId(), clean.toUpperCase(Locale.ROOT));
            if (ok) {
                audit.recordIndependent(event(user, "MFA_RECOVERY_CODE_USED", AuditResult.SUCCESS, null));
            }
        }
        if (!ok) {
            registerFailure(user, now, "Código de verificación incorrecto");
            throw new BusinessException("El código no es correcto o ya se usó. Intenta con el código actual de tu app.");
        }
        return openSession(user, now);
    }

    private boolean consumeRecoveryCode(Long userId, String code) {
        return jdbc.update("UPDATE mfa_recovery_codes SET used_at = now() WHERE user_id = ? AND code_hash = ? AND used_at IS NULL",
                userId, SecureTokens.sha256(code)) == 1;
    }

    private LoginResult openSession(User user, Instant now) {
        String ip = SecurityUtils.clientIp();
        boolean newLocation = user.getLastLoginIp() != null && ip != null && !ip.equals(user.getLastLoginIp());
        user.setFailedLoginAttempts(0);
        user.setLockCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        user.setLastLoginIp(ip);
        User saved = users.save(user);
        SessionStore.Issued issued = sessions.open(saved.getId());
        audit.recordIndependent(event(saved, "LOGIN_SUCCESS", AuditResult.SUCCESS, null));
        if (newLocation) {
            events.publishEvent(new SecurityEvent(saved.getId(), "NEW_LOCATION_LOGIN", "Inicio de sesión desde la IP " + ip));
        }
        return new LoginResult(SessionResponse.of(tokens.accessToken(saved.getId(), issued.sessionId()), tokens.accessTtlSeconds(),
                sessionUser(saved, issued)), issued.refreshToken());
    }

    private void registerFailure(User user, Instant now, String detail) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        boolean lock = attempts >= props.security().maxFailedLogins();
        if (lock) {
            // Bloqueo progresivo: 15 min, 30, 60… con tope de 4 h. Siempre temporal.
            Duration d = props.security().lockDuration().multipliedBy(1L << Math.min(user.getLockCount(), 4));
            user.setLockedUntil(now.plus(d.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : d));
            user.setLockCount(user.getLockCount() + 1);
            user.setFailedLoginAttempts(0);
        }
        users.save(user);
        audit.recordIndependent(event(user, lock ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", AuditResult.FAILED, detail));
        if (lock) {
            events.publishEvent(new SecurityEvent(user.getId(), "ACCOUNT_LOCKED", "Cuenta bloqueada por intentos fallidos"));
        }
    }

    @Transactional
    public LoginResult refresh(String refreshToken) {
        SessionStore.Rotation rotation = sessions.rotate(refreshToken);
        if (rotation == null || rotation.reuseDetected()) {
            if (rotation != null) {
                audit.recordIndependent(AuditEntry.of("auth", "REFRESH_TOKEN_REUSE", AuditResult.DENIED).entityType("User")
                        .entityId(rotation.userId()).actorUserId(rotation.userId())
                        .detail("Se reutilizó un token ya rotado; se cerró la sesión").build());
                events.publishEvent(new SecurityEvent(rotation.userId(), "TOKEN_REUSE", "Se cerró una sesión por posible robo de credenciales"));
            }
            throw new InvalidSessionException("Tu sesión expiró. Inicia sesión de nuevo.");
        }
        User user = users.findWithRolesById(rotation.userId()).filter(User::isActive)
                .orElseThrow(() -> new InvalidSessionException("Tu sesión expiró. Inicia sesión de nuevo."));
        SessionStore.Issued issued = new SessionStore.Issued(rotation.sessionId(), rotation.refreshToken());
        return new LoginResult(SessionResponse.of(tokens.accessToken(user.getId(), rotation.sessionId()), tokens.accessTtlSeconds(),
                sessionUser(user, issued)), rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        var sessionId = SecurityUtils.currentPrincipal().map(AuthPrincipal::sessionId).orElseGet(() -> sessions.sessionOf(refreshToken));
        if (sessionId != null) {
            sessions.revoke(sessionId, "LOGOUT");
        }
        SecurityUtils.currentPrincipal().ifPresent(p ->
                audit.recordIndependent(AuditEntry.success("auth", "LOGOUT", "User", p.userId()).build()));
    }

    @Transactional(readOnly = true)
    public SessionUser me() {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        User user = users.findWithRolesById(p.userId()).orElseThrow(() -> new InvalidSessionException("Tu sesión expiró."));
        return new SessionUser(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getJobTitle(),
                p.roles().stream().sorted().toList(), p.permissions().stream().sorted().toList(), user.isMustChangePassword(),
                user.isMfaEnabled());
    }

    /** Confirma la contraseña de la sesión actual para habilitar operaciones sensibles por unos minutos. */
    public void reauthenticate(String password) {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        User user = users.findById(p.userId()).orElseThrow(() -> new InvalidSessionException("Tu sesión expiró."));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            audit.recordIndependent(event(user, "REAUTH_FAILED", AuditResult.FAILED, null));
            throw new BusinessException("La contraseña no es correcta");
        }
        sessions.markReauth(p.sessionId());
        audit.recordIndependent(event(user, "REAUTH_SUCCESS", AuditResult.SUCCESS, null));
    }

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        users.findByEmail(email).filter(User::isActive).ifPresent(user -> {
            jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL", Timestamp.from(now), user.getId());
            String raw = SecureTokens.randomToken();
            jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                    user.getId(), SecureTokens.sha256(raw), Timestamp.from(now.plus(props.security().resetTokenTtl())));
            String link = props.frontendUrl().replaceAll("/+$", "") + "/restablecer/" + raw;
            sendResetEmail(user, link);
            audit.record(AuditEntry.success("auth", "PASSWORD_RESET_REQUESTED", "User", user.getId())
                    .actorUserId(user.getId()).actorEmail(user.getEmail()).build());
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = clock.instant();
        List<Long> ids = jdbc.queryForList("""
                SELECT user_id FROM password_reset_tokens WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE""",
                Long.class, SecureTokens.sha256(request.token()), Timestamp.from(now));
        if (ids.isEmpty()) {
            throw new BusinessException("El enlace no es válido o ya expiró. Solicita uno nuevo.");
        }
        User user = users.findById(ids.get(0)).filter(User::isActive)
                .orElseThrow(() -> new BusinessException("El enlace no es válido o ya expiró. Solicita uno nuevo."));
        requireStrong(request.newPassword(), user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(now);
        user.setMustChangePassword(false);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE token_hash = ?", Timestamp.from(now), SecureTokens.sha256(request.token()));
        sessions.revokeAllForUser(user.getId(), "PASSWORD_RESET", null);
        audit.record(AuditEntry.success("auth", "PASSWORD_RESET", "User", user.getId()).actorUserId(user.getId())
                .actorEmail(user.getEmail()).build());
    }

    @Transactional
    public SessionUser changePassword(ChangePasswordRequest request) {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        User user = users.findWithRolesById(p.userId()).orElseThrow(() -> new InvalidSessionException("Tu sesión expiró."));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException("La contraseña actual no es correcta");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException("La nueva contraseña debe ser distinta de la actual");
        }
        requireStrong(request.newPassword(), user.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangedAt(clock.instant());
        user.setMustChangePassword(false);
        // Cierra las demás sesiones; la actual continúa.
        sessions.revokeAllForUser(user.getId(), "PASSWORD_CHANGED", p.sessionId());
        audit.record(AuditEntry.success("auth", "PASSWORD_CHANGED", "User", user.getId()).build());
        events.publishEvent(new SecurityEvent(user.getId(), "PASSWORD_CHANGED", "Se cambió la contraseña de tu cuenta"));
        return new SessionUser(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getJobTitle(),
                p.roles().stream().sorted().toList(), p.permissions().stream().sorted().toList(), false, user.isMfaEnabled());
    }

    public static void requireStrong(String password, String email) {
        String problem = Validation.passwordProblem(password, email);
        if (problem != null) {
            throw new BusinessException(problem);
        }
    }

    private SessionUser sessionUser(User user, SessionStore.Issued issued) {
        PermissionResolver.Resolved r = permissionResolver.resolve(user.getId(), user.getPermissionsVersion());
        return new SessionUser(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getJobTitle(),
                r.roles().stream().sorted().toList(), r.permissions().stream().sorted().toList(), user.isMustChangePassword(),
                user.isMfaEnabled());
    }

    private AuditEntry event(User user, String action, AuditResult result, String detail) {
        return AuditEntry.of("auth", action, result).entityType("User").entityId(user.getId()).actorUserId(user.getId())
                .actorEmail(user.getEmail()).detail(detail).build();
    }

    private void sendResetEmail(User user, String link) {
        String minutes = String.valueOf(props.security().resetTokenTtl().toMinutes());
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#0f172a">
                  <h2>Restablece tu contraseña</h2>
                  <p>Hola %s, recibimos una solicitud para restablecer tu contraseña de Nexora Corporación.</p>
                  <p><a href="%s" style="display:inline-block;background:#4f46e5;color:#fff;padding:12px 20px;border-radius:10px;text-decoration:none">Crear nueva contraseña</a></p>
                  <p style="color:#64748b;font-size:13px">El enlace vence en %s minutos y solo funciona una vez. Si no fuiste tú, ignora este correo.</p>
                </div>""".formatted(HtmlUtils.htmlEscape(user.getFirstName()), HtmlUtils.htmlEscape(link), minutes);
        MailService.Result result = mail.send(user.getEmail(), "Restablece tu contraseña", html);
        if (!result.sent()) {
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                log.info("[DEV] SMTP no disponible ({}). Enlace de restablecimiento para {}: {}", result.error(), user.getEmail(), link);
            } else {
                log.warn("No se pudo enviar el correo de restablecimiento: {}", result.error());
            }
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String dummyHash() {
        if (dummyHash == null) {
            dummyHash = passwordEncoder.encode(SecureTokens.randomToken());
        }
        return dummyHash;
    }
}

