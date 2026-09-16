package com.inventory.controller;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination envelope for API responses - decoupled from Spring Data's
 * {@code PageImpl} serialization, which is not a guaranteed contract.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
