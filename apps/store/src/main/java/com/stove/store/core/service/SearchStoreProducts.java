package com.stove.store.core.service;

import com.stove.common.kafka.contract.payload.CatalogProductSnapshot;
import com.stove.common.web.error.*;
import com.stove.store.core.domain.StoreProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SearchStoreProducts {
    private final StoreProductRepository products;
    private final SynchronizeCatalogProducts synchronization;

    @Transactional(readOnly = true)
    public Page<CatalogProductSnapshot> execute(String q, String sort, int page, int size) {
        String keyword = q == null ? "" : q.strip();
        if (page < 0 || size < 1 || size > 100 || keyword.length() > 100)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어는 100자 이하, page >= 0, size는 1~100이어야 합니다.");
        Sort order = switch (sort) {
            case "NEWEST" -> Sort.by(Sort.Direction.DESC, "publishedAt");
            case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "price");
            case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "price");
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 정렬입니다.");
        };
        synchronization.requireReady();
        String escaped = keyword.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return products.search("%" + escaped + "%",
                PageRequest.of(page, size, order.and(Sort.by(Sort.Direction.DESC, "productId"))))
                .map(p -> p.snapshot());
    }
}
