package com.nexora.corp.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj único inyectable: las reglas de fechas se prueban con reloj fijo y la demo recorre meses pasados. */
@Configuration
public class TimeConfig {

    @Bean
    public SimulatableClock clock() {
        return new SimulatableClock();
    }
}

