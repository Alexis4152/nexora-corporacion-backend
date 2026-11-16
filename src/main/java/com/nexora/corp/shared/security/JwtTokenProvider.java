package com.nexora.corp.shared.security;

import com.nexora.corp.shared.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT HMAC-SHA256 de vida corta. Se validan firma, algoritmo, expiración, issuer, audience y tipo. El access token
 * lleva el usuario y la sesión ({@code sid}); roles y permisos se resuelven en cada request desde la base, así un
 * cambio de permisos o una sesión revocada aplican de inmediato.
 * <p>
 * El token MFA ({@code typ=mfa}) solo prueba que la contraseña fue correcta y sirve 5 minutos para el segundo factor.
 */
@Component
public class JwtTokenProvider {

    private static final String ISSUER = "nexora-corporacion";
    private static final String AUDIENCE = "nexora-corporacion-web";
    private static final String TYPE = "typ";
    private static final Duration MFA_TTL = Duration.ofMinutes(5);

    public record AccessClaims(Long userId, UUID sessionId) {
    }

    private final Key key;
    private final AppProperties props;
    private final Clock clock;

    public JwtTokenProvider(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
    }

    public String accessToken(Long userId, UUID sessionId) {
        Instant now = clock.instant();
        return Jwts.builder().setIssuer(ISSUER).setAudience(AUDIENCE).setSubject(String.valueOf(userId))
                .claim(TYPE, "access").claim("sid", sessionId.toString())
                .setIssuedAt(Date.from(now)).setExpiration(Date.from(now.plus(props.jwt().accessTtl())))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public String mfaToken(Long userId) {
        Instant now = clock.instant();
        return Jwts.builder().setIssuer(ISSUER).setAudience(AUDIENCE).setSubject(String.valueOf(userId))
                .claim(TYPE, "mfa").setIssuedAt(Date.from(now)).setExpiration(Date.from(now.plus(MFA_TTL)))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public Optional<AccessClaims> parseAccess(String token) {
        return parse(token, "access").map(c -> new AccessClaims(Long.valueOf(c.getSubject()), UUID.fromString(c.get("sid", String.class))));
    }

    public Optional<Long> parseMfa(String token) {
        return parse(token, "mfa").map(c -> Long.valueOf(c.getSubject()));
    }

    private Optional<Claims> parse(String token, String type) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            var jws = Jwts.parserBuilder().setSigningKey(key).requireIssuer(ISSUER).requireAudience(AUDIENCE)
                    .setClock(() -> Date.from(clock.instant())).build().parseClaimsJws(token);
            // Algoritmo permitido explícitamente: nada distinto de HS256 (ni "none").
            if (!SignatureAlgorithm.HS256.getValue().equals(jws.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            Claims claims = jws.getBody();
            return type.equals(claims.get(TYPE, String.class)) ? Optional.of(claims) : Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTtlSeconds() {
        return props.jwt().accessTtl().toSeconds();
    }
}

