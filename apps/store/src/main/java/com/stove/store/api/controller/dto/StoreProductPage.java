package com.stove.store.api.controller.dto;
import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import org.springframework.data.domain.Page;
import java.util.List;
public record StoreProductPage(List<StoreProductSummary> content, int page, int size, long totalElements, int totalPages) {
    public static StoreProductPage from(Page<CatalogProductSnapshot> p) {
        return new StoreProductPage(p.getContent().stream().map(StoreProductSummary::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
