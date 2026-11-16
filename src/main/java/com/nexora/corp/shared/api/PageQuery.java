package com.nexora.corp.shared.api;

import java.util.Map;

/**
 * Paginación y orden seguros para consultas SQL: el orden solo puede ser una columna de la lista blanca de cada
 * consulta (nunca texto del cliente concatenado) y el tamaño está acotado.
 */
public record PageQuery(int page, int size, String sort, boolean desc) {

    public static final int MAX_SIZE = 200;

    public static PageQuery of(Integer page, Integer size, String sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        boolean desc = sort != null && sort.startsWith("-");
        String key = sort == null ? null : sort.replaceFirst("^[-+]", "");
        return new PageQuery(p, s, key, desc);
    }

    public int offset() {
        return page * size;
    }

    /** ORDER BY a partir de la lista blanca {clave pública → expresión SQL}. */
    public String orderBy(Map<String, String> allowed, String fallback) {
        String column = sort != null ? allowed.get(sort) : null;
        if (column == null) {
            return " ORDER BY " + fallback;
        }
        return " ORDER BY " + column + (desc ? " DESC" : " ASC") + " NULLS LAST";
    }

    public String limit() {
        return " LIMIT " + size + " OFFSET " + offset();
    }
}

