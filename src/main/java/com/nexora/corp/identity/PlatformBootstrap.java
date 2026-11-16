package com.nexora.corp.identity;

import com.nexora.corp.shared.config.AppProperties;
import com.nexora.corp.shared.util.Validation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Alta inicial del primer SUPER_ADMIN desde variables de entorno, solo si todavía no existe ninguno. */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlatformBootstrap implements ApplicationRunner {

    private final AppProperties props;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.countActiveSuperAdmins() > 0) {
            return;
        }
        String email = props.bootstrap().superAdminEmail();
        String password = props.bootstrap().superAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("No existe ningún SUPER_ADMIN. Define APP_BOOTSTRAP_SUPER_ADMIN_EMAIL y APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD.");
            return;
        }
        String problem = Validation.passwordProblem(password, email);
        if (problem != null) {
            throw new IllegalStateException("APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD no cumple la política: " + problem);
        }
        Role superAdmin = roles.findByCode(Role.SUPER_ADMIN).orElseThrow();
        users.save(User.builder().email(email.trim().toLowerCase(Locale.ROOT)).firstName("Super").lastName("Administrador")
                .jobTitle("Dirección").passwordHash(passwordEncoder.encode(password)).roles(new HashSet<>(List.of(superAdmin))).build());
        log.info("SUPER_ADMIN inicial creado: {}", email);
    }
}
