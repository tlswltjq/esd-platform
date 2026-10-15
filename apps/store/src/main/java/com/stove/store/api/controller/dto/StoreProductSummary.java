package com.stove.store.api.controller.dto;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import java.time.Instant;
public record StoreProductSummary(Long productId, String productCode, String title, String shortDescription,
        String thumbnailUrl, long price, String currency, Instant publishedAt) {
    public static StoreProductSummary from(CatalogProductSnapshot p) {
        return new StoreProductSummary(p.productId(), p.productCode(), p.title(), p.shortDescription(),
                p.thumbnailUrl(), p.price(), p.currency(), p.publishedAt());
    }
}
