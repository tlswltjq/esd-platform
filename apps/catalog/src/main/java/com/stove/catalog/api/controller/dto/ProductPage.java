package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedProduct;

import org.springframework.data.domain.Page;

import java.util.List;

public record ProductPage(
        List<ProductSummary> content, int page, int size, long totalElements, int totalPages) {
    public static ProductPage from(Page<PublishedProduct> p) {
        return new ProductPage(
                p.getContent().stream().map(ProductSummary::from).toList(),
                p.getNumber(),
                p.getSize(),
                p.getTotalElements(),
                p.getTotalPages());
    }
}
