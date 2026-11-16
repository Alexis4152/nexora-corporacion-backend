package com.nexora.corp.shared.config;

import com.nexora.corp.shared.security.AuthPrincipal;
import com.nexora.corp.shared.security.SecurityUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** {@code createdBy}/{@code updatedBy} salen del usuario autenticado; nunca se aceptan del cliente. */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> auditorAware() {
        return () -> SecurityUtils.currentPrincipal().map(AuthPrincipal::userId);
    }
}

