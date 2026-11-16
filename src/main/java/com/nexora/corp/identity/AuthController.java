package com.nexora.corp.identity;

import com.nexora.corp.identity.IdentityDtos.ChangePasswordRequest;
import com.nexora.corp.identity.IdentityDtos.ForgotPasswordRequest;
import com.nexora.corp.identity.IdentityDtos.LoginRequest;
import com.nexora.corp.identity.IdentityDtos.MfaRequest;
import com.nexora.corp.identity.IdentityDtos.ReauthRequest;
import com.nexora.corp.identity.IdentityDtos.ResetPasswordRequest;
import com.nexora.corp.identity.IdentityDtos.SessionResponse;
import com.nexora.corp.identity.IdentityDtos.SessionUser;
import com.nexora.corp.shared.api.ApiResponse;
import com.nexora.corp.shared.config.AppProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesión. El refresh token viaja SOLO en una cookie httpOnly limitada a {@code /api/v1/auth}; el access token vive en
 * memoria del navegador. Refresh y logout exigen {@code X-Requested-With} (un formulario ajeno no puede mandarlo).
 */
@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String COOKIE = "nxc_rt";

    private final AuthService auth;
    private final AppProperties props;

    @PostMapping("/login")
    public ApiResponse<SessionResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return withCookie(auth.login(request.email(), request.password()), response);
    }

    @PostMapping("/mfa")
    public ApiResponse<SessionResponse> mfa(@Valid @RequestBody MfaRequest request, HttpServletResponse response) {
        return withCookie(auth.verifyMfa(request.mfaToken(), request.code()), response);
    }

    @PostMapping("/refresh")
    public ApiResponse<SessionResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        requireAjax(request);
        return withCookie(auth.refresh(readCookie(request)), response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        requireAjax(request);
        auth.logout(readCookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
        return ApiResponse.ok(null, "Sesión cerrada");
    }

    @GetMapping("/me")
    public ApiResponse<SessionUser> me() {
        return ApiResponse.ok(auth.me());
    }

    @PostMapping("/reauth")
    public ApiResponse<Void> reauth(@Valid @RequestBody ReauthRequest request) {
        auth.reauthenticate(request.password());
        return ApiResponse.ok(null, "Identidad confirmada");
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        auth.forgotPassword(request.email());
        return ApiResponse.ok(null, "Si el correo está registrado, te enviamos un enlace para restablecer tu contraseña.");
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        auth.resetPassword(request);
        return ApiResponse.ok(null, "Contraseña actualizada. Ya puedes iniciar sesión.");
    }

    @PostMapping("/change-password")
    public ApiResponse<SessionUser> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.ok(auth.changePassword(request), "Contraseña actualizada");
    }

    private ApiResponse<SessionResponse> withCookie(AuthService.LoginResult result, HttpServletResponse response) {
        if (result.refreshToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), props.jwt().refreshTtl().toSeconds()).toString());
        }
        return ApiResponse.ok(result.session());
    }

    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(props.cookie().secure())
                .sameSite(props.cookie().sameSite()).path("/api/v1/auth").maxAge(maxAge).build();
    }

    private static String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static void requireAjax(HttpServletRequest request) {
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            throw new AccessDeniedException("Solicitud no permitida");
        }
    }
}

