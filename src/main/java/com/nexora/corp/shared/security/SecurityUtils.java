package com.nexora.corp.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/** Acceso al usuario autenticado y a datos del cliente HTTP desde capas que no reciben el request. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthPrincipal p ? Optional.of(p) : Optional.empty();
    }

    public static AuthPrincipal requirePrincipal() {
        return currentPrincipal().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin sesión"));
    }

    public static Long currentUserId() {
        return currentPrincipal().map(AuthPrincipal::userId).orElse(null);
    }

    public static boolean has(String permission) {
        return currentPrincipal().map(p -> p.has(permission)).orElse(false);
    }

    /** IP real (Tomcat resuelve X-Forwarded-For con server.forward-headers-strategy=native). */
    public static String clientIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 255));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest() : null;
    }
}

