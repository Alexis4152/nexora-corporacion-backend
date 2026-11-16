package com.nexora.corp.shared.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Página estable usada como {@code data} en todo listado paginado. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return of(page.map(mapper));
    }

    public static <T> PageResponse<T> of(List<T> content, PageQuery q, long total) {
        return new PageResponse<>(content, q.page(), q.size(), total, (int) Math.ceil((double) total / q.size()));
    }
}

