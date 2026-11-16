package com.nexora.corp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Nexora Corporación: monolito modular (un paquete por módulo de negocio sobre {@code shared}). Ver
 * docs/01-ARQUITECTURA.md. Se excluye el usuario en memoria de Spring Boot: la autenticación es propia (JWT).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class NexoraCorpApplication {

    /** JVM en UTC (lección de HOTEL): las fechas de negocio usan la zona configurada de forma explícita. */
    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(NexoraCorpApplication.class, args);
    }
}

