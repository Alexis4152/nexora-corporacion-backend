package com.nexora.corp.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationTest {

    @Test
    void politicaDeContrasenas() {
        assertThat(Validation.passwordProblem("Corta1!", null)).contains("12");
        assertThat(Validation.passwordProblem("sinmayusculas123!", null)).isNotNull();
        assertThat(Validation.passwordProblem("SinSimbolo12345", null)).isNotNull();
        assertThat(Validation.passwordProblem("MiNexora-2026-Ok", null)).contains("común");
        assertThat(Validation.passwordProblem("Roberto-Clave-2026", "roberto@nexora.mx")).contains("correo");
        assertThat(Validation.passwordProblem("Clave-Segura-2026!Q", "ana@nexora.mx")).isNull();
    }
}

