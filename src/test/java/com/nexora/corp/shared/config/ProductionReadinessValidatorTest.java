package com.nexora.corp.shared.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReadinessValidatorTest {

    private static AppProperties props(boolean secure, String origin, boolean demo, String dir) {
        return new AppProperties("https://corp.nexora.mx", new AppProperties.Cors(List.of(origin)),
                new AppProperties.Jwt("x".repeat(64), Duration.ofMinutes(15), Duration.ofHours(10)), new AppProperties.Cookie(secure, "Strict"),
                new AppProperties.Encryption("y".repeat(64)), new AppProperties.Storage(dir, 10_000_000),
                new AppProperties.Security(true, 5, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(10), true),
                new AppProperties.Bootstrap(null, null, demo, null));
    }

    @Test
    void detectaConfiguracionInsegura() {
        List<String> p = ProductionReadinessValidator.problems(props(false, "http://localhost:5181", true, "./data"));
        assertThat(p).hasSize(4);
        assertThat(ProductionReadinessValidator.problems(props(true, "https://corp.nexora.mx", false, System.getProperty("java.io.tmpdir")))).isEmpty();
    }
}

