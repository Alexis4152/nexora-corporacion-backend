package com.nexora.corp.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Perfil prod: detiene el arranque si la configuración es insegura (patrón de HOTEL). Se prefiere no arrancar a
 * operar dinero con secretos débiles, cookies sin Secure, CORS local o datos de demostración.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionReadinessValidator {

    private final AppProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> problems = problems(props);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuración insegura para producción: " + String.join("; ", problems));
        }
    }

    public static List<String> problems(AppProperties p) {
        List<String> out = new ArrayList<>();
        if (p.jwt().secret().length() < 48) {
            out.add("APP_JWT_SECRET debe tener al menos 48 caracteres en producción");
        }
        if (p.jwt().secret().equals(p.encryption().key())) {
            out.add("APP_JWT_SECRET y APP_ENCRYPTION_KEY deben ser distintas");
        }
        if (!p.cookie().secure()) {
            out.add("APP_COOKIE_SECURE debe ser true (HTTPS)");
        }
        if (p.cors().allowedOrigins().stream().anyMatch(o -> o.contains("localhost") || o.equals("*"))) {
            out.add("APP_CORS_ALLOWED_ORIGINS no puede ser local ni comodín");
        }
        if (p.bootstrap().demoData()) {
            out.add("APP_DEMO_DATA está prohibido en producción");
        }
        if (!Path.of(p.storage().dir()).isAbsolute()) {
            out.add("APP_STORAGE_DIR debe ser una ruta absoluta (volumen persistente)");
        } else if (Files.exists(Path.of(p.storage().dir())) && !Files.isWritable(Path.of(p.storage().dir()))) {
            out.add("APP_STORAGE_DIR no es escribible");
        }
        return out;
    }
}

