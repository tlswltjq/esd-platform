package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.messaging.inbox.ProcessedEventGuard;
import com.stove.common.messaging.outbox.OutboxRecorder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 변경 = 캐시 무효화 + store 색인 동기화 이벤트 발행 지점.
 * 상품 마스터를 쓰는 경로를 이 클래스로 한정해 영향 범위를 통제한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductCommandService {

    private static final String AGGREGATE = "Product";
    private static final String CONSUMER_GROUP = "catalog";

    private final ProductRepository productRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;

    /**
     * [승인] review → ReviewApproved → catalog.
     * 상품이 없으면 생성(최초 등록), 있으면 심의 결과만 반영(재심의) — 멱등한 upsert.
     *
     * <p>중복 수신 마킹을 이 트랜잭션 안에서 함께 처리한다. 마킹만 커밋되고 처리가 롤백되면
     * 이벤트가 영구 유실되므로 둘은 반드시 같은 커밋이어야 한다.
     */
    public void upsertFromReview(String eventId, String eventType, ReviewApprovedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }

        Product product = productRepository.findByProductCode(event.productCode())
                .map(existing -> {
                    existing.applyReviewApproval(event.ratingCode());
                    return existing;
                })
                .orElseGet(() -> productRepository.save(Product.fromReview(
                        event.gameId(), event.productCode(), event.title(), event.sellerId(),
                        event.price(), event.currency(), event.ratingCode())));

        publishChanged(product);
        log.info("심의 승인 반영 productCode={} rating={} status={}",
                product.getProductCode(), event.ratingCode(), product.getStatus());
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void openSale(Long productId) {
        Product product = findProduct(productId);
        product.openSale();
        publishChanged(product);
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void suspend(Long productId) {
        Product product = findProduct(productId);
        product.suspend();
        publishChanged(product);
    }

    /**
     * 전체 재색인. 신규 색인 구축이나 store 색인 유실 시 운영툴에서 호출한다.
     * 대량 데이터에서는 페이지 단위로 나눠 발행해야 한다(TODO: 페이징 + 스로틀링).
     *
     * @return 발행한 이벤트 수
     */
    public int republishAll() {
        List<Product> products = productRepository.findAll();
        products.forEach(this::publishChanged);
        log.info("재색인 이벤트 발행 {}건", products.size());
        return products.size();
    }

    private void publishChanged(Product product) {
        outboxRecorder.record(AGGREGATE, product.getProductCode(),
                ProductChangedEvent.of(product.getId(), product.getProductCode(), product.getName(),
                        product.getSellerId(), product.getPrice(), product.getCurrency(),
                        product.getStatus().name(), product.getRatingCode()));
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
