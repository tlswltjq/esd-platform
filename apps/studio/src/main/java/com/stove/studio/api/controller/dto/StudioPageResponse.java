package com.stove.studio.api.controller.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record StudioPageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> StudioPageResponse<T> from(Page<T> page) {
        return new StudioPageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
