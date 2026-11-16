package com.nexora.corp.shared.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** La API responde DTOs; el módulo evita que un proxy LAZY rompa la serialización (mismo criterio que HOTEL). */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        return new Hibernate6Module();
    }
}

