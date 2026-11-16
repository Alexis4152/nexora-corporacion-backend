package com.nexora.corp.shared.api;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Ayuda para armar filtros dinámicos SIEMPRE con parámetros con nombre (nunca concatenando valores). */
public final class Sql {

    private final StringBuilder where = new StringBuilder(" WHERE 1=1");
    private final MapSqlParameterSource params = new MapSqlParameterSource();

    public Sql and(String clause) {
        where.append(" AND ").append(clause);
        return this;
    }

    public Sql and(boolean condition, String clause, String name, Object value) {
        if (condition) {
            where.append(" AND ").append(clause);
            params.addValue(name, value);
        }
        return this;
    }

    public Sql param(String name, Object value) {
        params.addValue(name, value);
        return this;
    }

    public String where() {
        return where.toString();
    }

    public MapSqlParameterSource params() {
        return params;
    }

    /** Texto de búsqueda a patrón ILIKE con comodines escapados. */
    public static String like(String q) {
        return "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}

