package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedProduct;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record ProductSummary(
        Long productId,
        String productCode,
        String title,
        String shortDescription,
        String thumbnailUrl,
        long price,
        String currency,
        Instant publishedAt, String saleStatus, long catalogVersion, boolean listed, @NotNull @Size(min = 1, max = 3) List<String> genres) {
    public static ProductSummary from(PublishedProduct p) {
        ProjectSnapshot s = p.snapshot();
        return new ProductSummary(
                p.productId(),
                s.productCode(),
                s.title(),
                s.shortDescription(),
                s.thumbnailUrl(),
                s.price(),
                s.currency(),
                p.publishedAt(), p.saleStatus(), p.catalogVersion(), p.listed(), s.genres());
    }
}
