package com.stove.store.core.service;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import com.stove.store.core.domain.StoreProductView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 진열/검색 유스케이스. catalog(쓰기)와 분리된 <b>읽기 모델</b>이며 자체 원본을 갖지 않는다.
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    private final ProductSearchRepository searchRepository;

    /** catalog → ProductChanged 수신 시 색인 upsert (문서 ID = productId → 멱등) */
    @CacheEvict(cacheNames = "store:featured", allEntries = true)
    public void indexProduct(ProductChangedEvent event) {
        if (event.projectionVersion() > 0 && searchRepository.findById(String.valueOf(event.productId()))
                .map(existing -> existing.getProjectionVersion() >= event.projectionVersion())
                .orElse(false)) {
            return;
        }
        searchRepository.save(ProductDocument.from(event));
        log.info("색인 동기화 productCode={} status={}", event.productCode(), event.status());
    }

    public List<StoreProductView> search(String keyword, int page, int size) {
        return search(keyword, page, size, "PRICE_ASC");
    }

    public List<StoreProductView> search(String keyword, int page, int size, String sort) {
        // 그대로 두면 IllegalArgumentException 이 500 으로 나간다. [D-020]
        // 컨트롤러가 아니라 여기서 막는 이유는 D-019.
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "page 는 0 이상, size 는 1~100이어야 합니다: page=%d, size=%d".formatted(page, size));
        }
        Sort order = switch (sort == null ? "PRICE_ASC" : sort) {
            case "PRICE_ASC" -> Sort.by("price").ascending();
            case "PRICE_DESC" -> Sort.by("price").descending();
            case "NEWEST" -> Sort.by("releaseId").descending();
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 정렬입니다: " + sort);
        };
        Pageable pageable = PageRequest.of(page, size, order);
        List<ProductDocument> documents = (keyword == null || keyword.isBlank())
                ? searchRepository.findByVisibleTrue(pageable)
                : searchRepository.findByVisibleTrueAndNameContaining(keyword, pageable);

        return documents.stream().map(StoreProductView::from).toList();
    }

    public StoreProductView detail(String productCode) {
        return searchRepository.findByProductCode(productCode)
                .filter(document -> Boolean.TRUE.equals(document.getVisible()))
                .map(StoreProductView::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 메인 진열. 전 유저 공통이라 적중률이 높고, 색인이 갱신되면 통째로 무효화한다. */
    @Cacheable(cacheNames = "store:featured", key = "'main'")
    public List<StoreProductView> featured() {
        return searchRepository.findByVisibleTrue(PageRequest.of(0, 10,
                        Sort.by("price").ascending())).stream()
                .map(StoreProductView::from)
                .toList();
    }
}
