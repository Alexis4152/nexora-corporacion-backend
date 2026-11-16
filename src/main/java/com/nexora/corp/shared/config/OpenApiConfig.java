package com.nexora.corp.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación OpenAPI (solo perfil dev; en prod está desactivada). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexoraOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Nexora Corporación API").version("v1")
                        .description("CRM, suscripciones SaaS, cobranza y Business Intelligence de Nexora Systems"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}

