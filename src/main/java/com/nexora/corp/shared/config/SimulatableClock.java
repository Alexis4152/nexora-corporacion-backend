package com.nexora.corp.shared.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reloj del sistema que, SOLO para el generador de datos de demostración del perfil dev, puede fijarse en una fecha
 * pasada y así recorrer meses de operación con los servicios reales. En cualquier otro caso es el reloj UTC normal:
 * nada fuera de {@code DevDemoSeeder} lo modifica, y {@code ProductionReadinessValidator} prohíbe los datos demo en prod.
 */
public class SimulatableClock extends Clock {

    private final AtomicReference<Instant> fixed;
    private final ZoneId zone;

    public SimulatableClock() {
        this(new AtomicReference<>(), ZoneOffset.UTC);
    }

    private SimulatableClock(AtomicReference<Instant> fixed, ZoneId zone) {
        this.fixed = fixed;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new SimulatableClock(fixed, zone);
    }

    @Override
    public Instant instant() {
        Instant f = fixed.get();
        return f != null ? f : Instant.now();
    }

    void set(Instant instant) {
        fixed.set(instant);
    }

    void reset() {
        fixed.set(null);
    }
}

