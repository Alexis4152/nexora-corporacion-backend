package com.nexora.corp.shared.util;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Folios legibles a partir de secuencias de PostgreSQL (sin huecos por concurrencia ni duplicados). */
@Component
@RequiredArgsConstructor
public class Folios {

    private static final Set<String> SEQUENCES = Set.of("customer_code_seq", "subscription_code_seq", "charge_folio_seq",
            "payment_folio_seq", "ticket_folio_seq", "quote_folio_seq");

    private final JdbcTemplate jdbc;

    public String next(String sequence, String prefix, int digits) {
        if (!SEQUENCES.contains(sequence)) {
            throw new IllegalArgumentException("Secuencia no permitida");
        }
        Long n = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        return prefix + "-" + String.format("%0" + digits + "d", n);
    }
}

