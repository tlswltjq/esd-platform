package com.stove.catalog.api.controller.dto;

import com.stove.catalog.core.domain.PublishedProduct;
import com.stove.common.kafka.contract.payload.ProjectSnapshot;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record ProductDetail(
        Long productId,
        String productCode,
        String title,
        String shortDescription,
        String thumbnailUrl,
        long price,
        String currency,
        Instant publishedAt,
        String content,
        String videoUrl,
        long contentVersion,
        String buildVersion,
        Instant updatedAt, String saleStatus, long catalogVersion, boolean listed, @NotNull @Size(min = 1, max = 3) List<String> genres) {
    public static ProductDetail from(PublishedProduct p) {
        ProjectSnapshot s = p.snapshot();
        return new ProductDetail(
                p.productId(),
                s.productCode(),
                s.title(),
                s.shortDescription(),
                s.thumbnailUrl(),
                s.price(),
                s.currency(),
                p.publishedAt(),
                s.content(),
                s.videoUrl(),
                s.contentVersion(),
                s.version(),
                p.updatedAt(), p.saleStatus(), p.catalogVersion(), p.listed(), s.genres());
    }
}
