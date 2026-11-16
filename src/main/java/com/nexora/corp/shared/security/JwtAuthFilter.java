package com.nexora.corp.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Autentica cada request con el JWT. En cada request se vuelve a leer el usuario y su sesión: una cuenta
 * desactivada o una sesión revocada pierden acceso de inmediato, sin esperar a que el token expire.
 * Token ausente o inválido: la request sigue como anónima y {@code SecurityConfig} decide.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_WHILE_MUST_CHANGE = Set.of(
            "/api/v1/auth/me", "/api/v1/auth/change-password", "/api/v1/auth/logout", "/api/v1/auth/refresh");

    private static final String LOAD = """
            SELECT u.email, u.first_name, u.last_name, u.must_change_password, u.permissions_version
            FROM users u JOIN user_sessions s ON s.user_id = u.id
            WHERE u.id = ? AND s.id = ? AND u.active AND s.revoked_at IS NULL AND s.expires_at > now()""";

    private record Row(String email, String name, boolean mustChange, int permissionsVersion) {
    }

    private final JwtTokenProvider tokens;
    private final JdbcTemplate jdbc;
    private final PermissionResolver permissions;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenProvider tokens, JdbcTemplate jdbc, PermissionResolver permissions, ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        var claims = tokens.parseAccess(token).orElse(null);
        if (claims != null) {
            List<Row> rows = jdbc.query(LOAD, (rs, i) -> new Row(rs.getString(1), rs.getString(2) + " " + rs.getString(3),
                    rs.getBoolean(4), rs.getInt(5)), claims.userId(), claims.sessionId());
            if (!rows.isEmpty()) {
                Row row = rows.get(0);
                if (row.mustChange() && !ALLOWED_WHILE_MUST_CHANGE.contains(request.getRequestURI())) {
                    write(response, "Debes cambiar tu contraseña temporal antes de continuar", "MUST_CHANGE_PASSWORD");
                    return;
                }
                PermissionResolver.Resolved resolved = permissions.resolve(claims.userId(), row.permissionsVersion());
                AuthPrincipal principal = new AuthPrincipal(claims.userId(), claims.sessionId(), row.email(), row.name(),
                        resolved.roles(), resolved.permissions());
                List<GrantedAuthority> authorities = new ArrayList<>();
                principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, authorities));
                MDC.put("userId", String.valueOf(claims.userId()));
                touch(claims);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
        }
    }

    /** Última actividad de la sesión, como máximo una escritura por minuto. */
    private void touch(JwtTokenProvider.AccessClaims claims) {
        jdbc.update("UPDATE user_sessions SET last_seen_at = now() WHERE id = ? AND last_seen_at < now() - interval '1 minute'",
                claims.sessionId());
    }

    private void write(HttpServletResponse response, String message, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(message, code, null));
    }
}

