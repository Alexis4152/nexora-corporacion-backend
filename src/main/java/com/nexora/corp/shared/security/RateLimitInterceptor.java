package com.nexora.corp.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.corp.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Límites por riesgo. Los de acceso van por IP (frenan a quien prueba muchas cuentas); las operaciones financieras,
 * administrativas y exportaciones van por usuario (frenan abuso con una cuenta válida). El bloqueo de cuenta por
 * contraseña errónea es temporal, así un atacante no puede dejar fuera a usuarios legítimos de forma permanente.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private enum Scope { IP, USER }

    private record Rule(String id, String method, String pattern, int capacity, Duration window, Scope scope) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("login", "POST", "/api/v1/auth/login", 10, Duration.ofMinutes(1), Scope.IP),
            new Rule("mfa", "POST", "/api/v1/auth/mfa", 10, Duration.ofMinutes(5), Scope.IP),
            new Rule("forgot", "POST", "/api/v1/auth/forgot-password", 5, Duration.ofMinutes(15), Scope.IP),
            new Rule("reset", "POST", "/api/v1/auth/reset-password", 10, Duration.ofMinutes(15), Scope.IP),
            new Rule("refresh", "POST", "/api/v1/auth/refresh", 60, Duration.ofMinutes(1), Scope.IP),
            new Rule("reauth", "POST", "/api/v1/auth/reauth", 8, Duration.ofMinutes(10), Scope.USER),
            new Rule("license", "GET", "/api/integration/**", 600, Duration.ofMinutes(1), Scope.IP),
            new Rule("export", "GET", "/api/v1/**/export", 30, Duration.ofMinutes(5), Scope.USER),
            new Rule("payments", "POST", "/api/v1/payments/**", 60, Duration.ofMinutes(1), Scope.USER),
            new Rule("payments-root", "POST", "/api/v1/payments", 60, Duration.ofMinutes(1), Scope.USER),
            new Rule("smtp-test", "POST", "/api/v1/integrations/*/test", 5, Duration.ofMinutes(10), Scope.USER),
            new Rule("users", "POST", "/api/v1/users/**", 30, Duration.ofMinutes(5), Scope.USER),
            new Rule("upload", "POST", "/api/v1/documents", 40, Duration.ofMinutes(5), Scope.USER));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final RateLimiterService limiter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && matcher.match(rule.pattern(), path)) {
                String who = rule.scope() == Scope.USER && SecurityUtils.currentUserId() != null
                        ? "u" + SecurityUtils.currentUserId() : "ip" + request.getRemoteAddr();
                long wait = limiter.tryConsume(rule.id() + "|" + who, rule.capacity(), rule.window());
                if (wait > 0) {
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error("Demasiadas solicitudes. Intenta de nuevo en unos minutos.", "RATE_LIMIT", null));
                    return false;
                }
                return true;
            }
        }
        return true;
    }
}

