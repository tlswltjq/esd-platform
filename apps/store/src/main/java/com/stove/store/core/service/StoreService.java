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
        // PageRequest.of 는 범위를 벗어난 값에 IllegalArgumentException 을 던진다. 그대로 두면
        // GlobalExceptionHandler 의 마지막 분기로 흘러 500 이 나간다 — 클라이언트 잘못을
        // 서버 장애로 표시하는 D-015 와 같은 부류다(D-020).
        //
        // 컨트롤러가 아니라 여기서 막는 이유는 D-019 와 같다. 어댑터에만 두면 그 경로 하나만
        // 지켜지고, 어댑터는 늘어난다.
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "page 는 0 이상, size 는 1 이상이어야 합니다: page=%d, size=%d".formatted(page, size));
        }
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
