package com.stove.settlement.api.controller.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record SettlementPage<T>(List<T> content,int page,int size,long totalElements,int totalPages) {
    public static <T> SettlementPage<T> from(Page<T> page) {
        return new SettlementPage<T>(page.getContent(),page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages());
    }
}
