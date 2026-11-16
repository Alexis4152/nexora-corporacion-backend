package com.nexora.corp.shared.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Diferencias campo a campo para la auditoría: solo lo que cambió, con valor anterior y nuevo. */
public final class Changes {

    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> after = new LinkedHashMap<>();

    public Changes track(String field, Object oldValue, Object newValue) {
        if (!Objects.equals(normalize(oldValue), normalize(newValue))) {
            before.put(field, oldValue);
            after.put(field, newValue);
        }
        return this;
    }

    private static Object normalize(Object v) {
        if (v instanceof java.math.BigDecimal b) {
            return b.stripTrailingZeros();
        }
        if (v instanceof String s && s.isBlank()) {
            return null;
        }
        return v;
    }

    public boolean isEmpty() {
        return after.isEmpty();
    }

    public Map<String, Object> before() {
        return before;
    }

    public Map<String, Object> after() {
        return after;
    }
}

