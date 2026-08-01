package com.stove.store.core.service;

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
import org.springframework.stereotype.Service;

/**
 * 진열/검색 유스케이스.
 * 읽기 트래픽이 가장 몰리는 서비스라 ES 조회 앞단에 Redis 캐시를 한 겹 더 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    private static final String ON_SALE = "ON_SALE";

    private final ProductSearchRepository searchRepository;

    /** catalog → ProductChanged 수신 시 색인 upsert (문서 ID = productId → 멱등) */
    @CacheEvict(cacheNames = "store:featured", allEntries = true)
    public void indexProduct(ProductChangedEvent event) {
        searchRepository.save(ProductDocument.from(event));
        log.info("색인 동기화 productCode={} status={}", event.productCode(), event.status());
    }

    public List<StoreProductView> search(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        List<ProductDocument> documents = (keyword == null || keyword.isBlank())
                ? searchRepository.findByStatusOrderByPriceAsc(ON_SALE, pageable)
                : searchRepository.findByStatusAndNameContaining(ON_SALE, keyword, pageable);

        return documents.stream().map(StoreProductView::from).toList();
    }

    /**
     * 메인 진열(프로모션 큐레이션). 전 유저 공통 응답이라 캐시 적중률이 가장 높은 구간이다.
     * 색인이 갱신되면 통째로 무효화한다.
     */
    @Cacheable(cacheNames = "store:featured", key = "'main'")
    public List<StoreProductView> featured() {
        return searchRepository.findByStatusOrderByPriceAsc(ON_SALE, PageRequest.of(0, 10)).stream()
                .map(StoreProductView::from)
                .toList();
    }
}
