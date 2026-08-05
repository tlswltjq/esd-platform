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
     * 재색인 한 페이지를 <b>독립 트랜잭션</b>으로 발행한다.
     *
     * <p>예전에는 {@code findAll()} 로 상품 테이블 전체를 한 트랜잭션에서 발행했다.
     * 세 가지가 겹쳐 있었다 — 전량이 메모리에 올라오고, 단일 커밋이라 막판 실패가 전량 롤백이며,
     * 스로틀이 없어 Outbox 가 한꺼번에 채워졌다. 릴레이는 전 서비스 공유 자원이라
     * 그동안 정상 상태 변경 이벤트가 재색인 뒤에 줄을 선다.
     *
     * <p>페이지마다 커밋되므로 중간에 실패하면 <b>부분 재색인</b>이 남는다. store 색인은
     * 문서 ID 고정 upsert 라 자연 멱등이므로 재실행으로 수렴한다 — 전량 롤백보다 낫다.
     *
     * <p>{@code OutboxRecorder} 가 {@code MANDATORY} 라 페이지 단위 커밋을 만들려면
     * 트랜잭션 경계가 여기여야 하고, 반복은 트랜잭션 밖(조율 계층)에 있어야 한다.
     * 자기 호출은 프록시를 타지 않으므로 반복을 이 클래스 안에 둘 수 없다.
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
