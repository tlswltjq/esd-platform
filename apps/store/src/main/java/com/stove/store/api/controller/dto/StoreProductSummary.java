package com.stove.store.api.controller.dto;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
public record StoreProductSummary(Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt, String saleStatus, long catalogVersion, boolean listed, @NotNull @Size(min = 1, max = 3) List<String> genres) {
    public static StoreProductSummary from(CatalogProductSnapshot p) {
        return new StoreProductSummary(p.productId(), p.productCode(), p.title(), p.shortDescription(),
                p.thumbnailUrl(), p.price(), p.currency(), p.publishedAt(), p.saleStatus(), p.catalogVersion(), p.listed(), p.genres());
    }
}
