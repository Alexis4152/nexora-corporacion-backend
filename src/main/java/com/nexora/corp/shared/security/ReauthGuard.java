package com.nexora.corp.shared.security;

import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.error.ReauthRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * Exige que la sesión actual haya confirmado su contraseña hace poco ({@code app.security.reauth-window}) antes de
 * una operación especialmente sensible: revertir pagos, devoluciones, cambiar permisos, administrar usuarios,
 * credenciales de integraciones. Robar un access token no basta para ejecutarlas.
 */
@Component
@RequiredArgsConstructor
public class ReauthGuard {

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final Clock clock;

    public void require() {
        AuthPrincipal principal = SecurityUtils.requirePrincipal();
        List<Timestamp> at = jdbc.queryForList("SELECT reauth_at FROM user_sessions WHERE id = ?", Timestamp.class, principal.sessionId());
        if (at.isEmpty() || at.get(0) == null
                || at.get(0).toInstant().plus(props.security().reauthWindow()).isBefore(clock.instant())) {
            throw new ReauthRequiredException();
        }
    }
}

