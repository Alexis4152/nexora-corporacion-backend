package com.nexora.corp.identity;

import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.security.SecureTokens;
import com.nexora.corp.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sesiones y refresh tokens (JDBC directo: son tablas técnicas sin lógica de dominio). Cada inicio de sesión crea
 * una sesión; el refresh token rota en cada uso dentro de ella. Si se presenta un token ya rotado fuera del margen de
 * gracia (posible robo de cookie), se revoca la sesión completa.
 */
@Component
@RequiredArgsConstructor
public class SessionStore {

    private static final Duration REUSE_GRACE = Duration.ofSeconds(20);

    public record Issued(UUID sessionId, String refreshToken) {
    }

    public record Rotation(UUID sessionId, Long userId, String refreshToken, boolean reuseDetected) {
    }

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final Clock clock;

    @Transactional
    public Issued open(Long userId) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("INSERT INTO user_sessions (id, user_id, ip, user_agent, created_at, last_seen_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, userId, SecurityUtils.clientIp(), SecurityUtils.userAgent(), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.plus(props.jwt().refreshTtl())));
        return new Issued(id, issueToken(id, now));
    }

    private String issueToken(UUID sessionId, Instant now) {
        String raw = SecureTokens.randomToken();
        jdbc.update("INSERT INTO refresh_tokens (session_id, token_hash, expires_at) VALUES (?, ?, ?)",
                sessionId, SecureTokens.sha256(raw), Timestamp.from(now.plus(props.jwt().refreshTtl())));
        return raw;
    }

    /** Rota el token. Devuelve null si no es válido (expirado, revocado o inexistente). */
    @Transactional
    public Rotation rotate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.session_id, t.expires_at, t.revoked_at, t.replaced_by_id, s.user_id, s.revoked_at AS s_revoked,
                       s.expires_at AS s_expires
                FROM refresh_tokens t JOIN user_sessions s ON s.id = t.session_id
                WHERE t.token_hash = ? FOR UPDATE OF t""", SecureTokens.sha256(raw));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        UUID sessionId = (UUID) r.get("session_id");
        Long userId = ((Number) r.get("user_id")).longValue();
        Timestamp revoked = (Timestamp) r.get("revoked_at");
        if (revoked != null) {
            boolean rotatedOutsideGrace = r.get("replaced_by_id") != null && revoked.toInstant().plus(REUSE_GRACE).isBefore(now);
            if (rotatedOutsideGrace) {
                revoke(sessionId, "TOKEN_REUSE");
                return new Rotation(sessionId, userId, null, true);
            }
            return null;
        }
        if (r.get("s_revoked") != null || !((Timestamp) r.get("expires_at")).toInstant().isAfter(now)
                || !((Timestamp) r.get("s_expires")).toInstant().isAfter(now)) {
            return null;
        }
        String next = issueToken(sessionId, now);
        Long nextId = jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE token_hash = ?", Long.class, SecureTokens.sha256(next));
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, replaced_by_id = ? WHERE id = ?",
                Timestamp.from(now), nextId, ((Number) r.get("id")).longValue());
        return new Rotation(sessionId, userId, next, false);
    }

    public UUID sessionOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<UUID> ids = jdbc.queryForList("SELECT session_id FROM refresh_tokens WHERE token_hash = ?", UUID.class, SecureTokens.sha256(raw));
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Transactional
    public void revoke(UUID sessionId, String reason) {
        jdbc.update("UPDATE user_sessions SET revoked_at = now(), revoked_reason = ? WHERE id = ? AND revoked_at IS NULL", reason, sessionId);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE session_id = ? AND revoked_at IS NULL", sessionId);
    }

    @Transactional
    public int revokeAllForUser(Long userId, String reason, UUID except) {
        int n = jdbc.update("""
                UPDATE user_sessions SET revoked_at = now(), revoked_reason = ?
                WHERE user_id = ? AND revoked_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid)""",
                reason, userId, except, except);
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL AND session_id IN
                  (SELECT id FROM user_sessions WHERE user_id = ? AND revoked_at IS NOT NULL)""", userId);
        return n;
    }

    public void markReauth(UUID sessionId) {
        jdbc.update("UPDATE user_sessions SET reauth_at = now() WHERE id = ?", sessionId);
    }

    public List<Map<String, Object>> activeSessions(Long userId) {
        return jdbc.queryForList("""
                SELECT id, ip, user_agent, created_at, last_seen_at, expires_at FROM user_sessions
                WHERE user_id = ? AND revoked_at IS NULL AND expires_at > now() ORDER BY last_seen_at DESC""", userId);
    }

    public boolean belongsTo(UUID sessionId, Long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND user_id = ?", Integer.class, sessionId, userId);
        return n != null && n > 0;
    }

    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void purge() {
        jdbc.update("DELETE FROM user_sessions WHERE expires_at < now() - interval '30 days'");
        jdbc.update("DELETE FROM password_reset_tokens WHERE expires_at < now() - interval '7 days'");
    }
}

