package com.stove.catalog.core.service;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.PromotionRepository;
import com.stove.catalog.core.domain.ReindexPage;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.event.payload.ReleasePublishedEvent;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <b>상품 마스터를 쓰는 경로를 이 클래스로 한정한다.</b>
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
    private final PromotionRepository promotionRepository;
    private final OutboxRecorder outboxRecorder;
    private final ProcessedEventGuard processedEventGuard;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * [승인] review → ReviewApproved → catalog. 멱등한 upsert 다.
     * <b>중복 수신 마킹과 반드시 같은 커밋이어야 한다</b> — 갈리면 이벤트가 영구 유실된다.
     */
    @CacheEvict(cacheNames = "catalog:product", allEntries = true)
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

    @CacheEvict(cacheNames = "catalog:product", allEntries = true)
    public void upsertFromRelease(String eventId, String eventType, ReleasePublishedEvent event) {
        if (!processedEventGuard.firstDelivery(eventId, CONSUMER_GROUP, eventType)) {
            return;
        }

        // 행사 생성과 가격 변경은 같은 상품 행을 잠가 최종 청구액 1원 규칙을 직렬화한다.
        var existingProduct = productRepository.lockByProductCode(event.productCode());
        // 롤백도 새 releaseId를 발급하므로 이전 발행 이벤트의 지연 도착은 안전하게 무시한다.
        if (existingProduct
                .map(existing -> existing.getCurrentReleaseId() != null
                        && existing.getCurrentReleaseId() >= event.releaseId())
                .orElse(false)) {
            return;
        }

        Product product = existingProduct
                .map(existing -> {
                    existing.applyRelease(event.gameId(), event.title(), event.sellerId(),
                            event.price(), event.currency(), event.ratingCode(), event.releaseId(),
                            event.buildId(), event.metadataRevision());
                    existing.applyFamily(event.productKind(), event.parentProductCode(),
                            event.editionName(), event.bundleProductCodes());
                    existing.applyStorefront(serializeStorefront(event.storefront()));
                    return existing;
                })
                .orElseGet(() -> {
                    Product created = Product.fromRelease(event.gameId(), event.productCode(),
                            event.title(), event.sellerId(), event.price(), event.currency(),
                            event.ratingCode(), event.releaseId(), event.buildId(), event.metadataRevision());
                    created.applyFamily(event.productKind(), event.parentProductCode(),
                            event.editionName(), event.bundleProductCodes());
                    created.applyStorefront(serializeStorefront(event.storefront()));
                    return productRepository.save(created);
                });

        if (promotionRepository.findByProductIdAndStoppedAtIsNull(product.getId()).stream()
                .anyMatch(p -> p.getEndsAt().isAfter(java.time.Instant.now())
                        && p.getDiscountPerUnit() >= product.getPrice())) {
            throw new BusinessException(ErrorCode.CONFLICT, "활성 할인보다 낮은 가격으로 변경할 수 없습니다.");
        }

        publishChanged(product);
        log.info("릴리스 공개 반영 productCode={} releaseId={} buildId={}",
                event.productCode(), event.releaseId(), event.buildId());
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void openSale(Long productId) {
        openSale(productId, "system:legacy");
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void openSale(Long productId, String actor) {
        Product product = findProduct(productId);
        product.openSale();
        publishChanged(product);
        auditLogService.record(actor, "EMERGENCY_SALE_OPEN", productId,
                "releaseId=" + product.getCurrentReleaseId());
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void suspend(Long productId) {
        suspend(productId, "system:legacy");
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#productId")
    public void suspend(Long productId, String actor) {
        Product product = findProduct(productId);
        product.suspend();
        publishChanged(product);
        auditLogService.record(actor, "EMERGENCY_SUSPEND", productId,
                "releaseId=" + product.getCurrentReleaseId());
    }

    /**
     * 재색인 한 페이지를 <b>독립 트랜잭션</b>으로 발행한다.
     * 트랜잭션 경계가 여기여야 하고 반복은 밖(조율 계층)에 있어야 한다.
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
        long version = product.advanceProjectionVersion();
        outboxRecorder.record(AGGREGATE, product.getProductCode(),
                ProductChangedEvent.ofRelease(product.getId(), product.getProductCode(), product.getName(),
                        product.getSellerId(), product.getPrice(), product.getCurrency(),
                        product.getStatus().name(), product.getRatingCode(), product.getCurrentReleaseId(),
                        product.getCurrentBuildId(), product.getMetadataRevision(),
                        product.getProductKind(), product.getParentProductCode(),
                        product.getEditionName(), product.getBundleProductCodes(),
                        com.stove.catalog.core.domain.ProductView.from(product).storefront(), version,
                        promotionRepository.findByProductIdAndStoppedAtIsNull(product.getId()).stream()
                                .map(com.stove.catalog.core.domain.Promotion::window).toList()));
    }

    @CacheEvict(cacheNames = "catalog:product", key = "#product.id")
    public void promotionChanged(Product product) {
        publishChanged(product);
    }

    private String serializeStorefront(com.stove.common.event.payload.StorefrontSnapshot storefront) {
        if (storefront == null) return null;
        try {
            return objectMapper.writeValueAsString(storefront);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("상점 스냅샷을 저장할 수 없습니다.", exception);
        }
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
