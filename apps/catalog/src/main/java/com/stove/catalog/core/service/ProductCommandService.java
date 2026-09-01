package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.ReindexPage;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 변경 = 캐시 무효화 + store 색인 동기화 이벤트 발행 지점.
 * <b>상품 마스터를 쓰는 경로를 이 클래스로 한정한다</b> — docs/code-notes.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductCommandService {

    private static final String AGGREGATE = "Product";

    /** Kafka 컨슈머 그룹이자 Inbox 멱등 키. 리스너도 이 상수를 참조한다 — {@code ConsumerGroupRules} 참고. */
    public static final String CONSUMER_GROUP = "catalog";

    private final ProductRepository productRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;

    /**
     * [승인] review → ReviewApproved → catalog. 멱등한 upsert 다.
     * <b>중복 수신 마킹과 반드시 같은 커밋이어야 한다</b> — 갈리면 이벤트가 영구 유실된다.
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
     * 재색인 한 페이지를 <b>독립 트랜잭션</b>으로 발행한다.
     * 트랜잭션 경계가 여기여야 하고 반복은 밖(조율 계층)에 있어야 한다 — docs/code-notes.md
     *
     * @param afterId  이 id 보다 큰 상품부터 (커서)
     * @param pageSize 한 번에 발행할 수
     */
    public ReindexPage republishFrom(long afterId, int pageSize) {
        List<Product> products = productRepository.findByIdGreaterThanOrderByIdAsc(
                afterId, PageRequest.ofSize(pageSize));
        if (products.isEmpty()) {
            return ReindexPage.empty(afterId);
        }

        products.forEach(this::publishChanged);

        long lastId = products.get(products.size() - 1).getId();
        log.info("재색인 페이지 발행 {}건 (id {} ~ {})", products.size(), afterId, lastId);
        return new ReindexPage(products.size(), lastId, products.size() == pageSize);
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
