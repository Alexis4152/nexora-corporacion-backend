package com.nexora.corp.shared.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** Vector oficial del RFC 6238 (SHA1, T=59 s → 94287082; 6 dígitos = 287082). */
    @Test
    void vectorRfc6238() {
        String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertThat(secret).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.codeAt(secret, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.codeAt(secret, Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
    }

    @Test
    void aceptaDesfaseDeUnPasoYRechazaReutilizacion() {
        String secret = Totp.newSecret();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String code = Totp.codeAt(secret, now.minusSeconds(30));
        var step = Totp.verify(secret, code, now, null);
        assertThat(step).isPresent();
        assertThat(Totp.verify(secret, code, now, step.getAsLong())).isEmpty();
        assertThat(Totp.verify(secret, "12345", now, null)).isEmpty();
        assertThat(Totp.verify(secret, code, now.plusSeconds(600), null)).isEmpty();
    }
}

