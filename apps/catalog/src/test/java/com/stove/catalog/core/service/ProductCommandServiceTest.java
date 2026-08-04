package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.ProductStatus;
import com.stove.catalog.core.domain.ProductView;
import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.EventType;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.messaging.inbox.ProcessedEventRepository;
import com.stove.common.messaging.outbox.OutboxEvent;
import com.stove.common.messaging.outbox.OutboxEventRepository;
import com.stove.common.test.InfraContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 상품 마스터의 유일한 쓰기 경로.
 *
 * <p>이 클래스에는 테스트가 없었다. catalog 의 기존 테스트는 전부 {@code quote()}(D-009 회귀)만 보고 있어,
 * <b>상품이 만들어지고 판매 상태가 바뀌는 경로</b> 자체가 미검증이었다.
 *
 * <p>{@code @CacheEvict} 를 확인하려면 실제 프록시와 Redis 가 필요하다 —
 * 직접 {@code new} 로 만든 인스턴스에서는 애너테이션이 아무 일도 하지 않아서,
 * 캐시를 안 지워도 통과하는 테스트가 된다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class ProductCommandServiceTest {

    @Autowired
    ProductCommandService productCommandService;
    @Autowired
    ProductQueryService productQueryService;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    ProcessedEventRepository processedEventRepository;

    private static String uniqueProductCode() {
        return "GAME-" + UUID.randomUUID();
    }

    private static ReviewApprovedEvent approval(String productCode, String ratingCode) {
        return ReviewApprovedEvent.of(1L, productCode, "로스트아크", 1001L, 39_000L, "KRW",
                ratingCode, false);
    }

    private void receive(ReviewApprovedEvent event) {
        productCommandService.upsertFromReview(UUID.randomUUID().toString(),
                EventType.REVIEW_APPROVED, event);
    }

    private Product find(String productCode) {
        return productRepository.findByProductCode(productCode).orElseThrow();
    }

    private List<OutboxEvent> outboxFor(String productCode) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> productCode.equals(e.getPartitionKey()))
                .toList();
    }

    @Test
    @DisplayName("심의 승인이 상품을 만든다 — 다만 아직 판매 중은 아니다")
    void approvalCreatesApprovedProduct() {
        String productCode = uniqueProductCode();

        receive(approval(productCode, "ALL"));

        Product product = find(productCode);
        // APPROVED 이지 ON_SALE 이 아니다. 판매 시작은 운영자가 따로 연다 —
        // 심의를 통과했다고 상품이 저절로 노출되면 발매일 통제가 사라진다.
        assertThat(product.getStatus()).isEqualTo(ProductStatus.APPROVED);
        assertThat(product.getRatingCode()).isEqualTo("ALL");
        assertThat(product.getGameId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("상품 생성도 ProductChanged 를 발행한다 — store 색인이 이 이벤트로만 채워진다")
    void creationPublishesProductChanged() {
        String productCode = uniqueProductCode();

        receive(approval(productCode, "ALL"));

        List<OutboxEvent> published = outboxFor(productCode);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getEventType()).isEqualTo(EventType.PRODUCT_CHANGED);
        assertThat(published.get(0).getAggregateType()).isEqualTo("Product");
        assertThat(published.get(0).getPayload()).contains("APPROVED");
    }

    @Test
    @DisplayName("재심의는 같은 상품에 반영되고 이벤트도 다시 나간다")
    void reReviewUpdatesInPlaceAndRepublishes() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        Long productId = find(productCode).getId();

        receive(approval(productCode, "ADULT"));

        // 상품이 두 개 생기면 order 가 어느 쪽을 가리키는지 알 수 없게 된다
        assertThat(find(productCode).getId()).isEqualTo(productId);
        assertThat(find(productCode).getRatingCode()).isEqualTo("ADULT");
        // 등급이 바뀌면 store 색인도 따라가야 한다 — 기존 상품 분기에서도 발행되어야 하는 이유다
        assertThat(outboxFor(productCode)).hasSize(2);
    }

    @Test
    @DisplayName("판매 중인 상품의 재심의가 판매를 중단시키지 않는다")
    void reReviewDoesNotResetLiveProduct() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        Long productId = find(productCode).getId();
        productCommandService.openSale(productId);

        receive(approval(productCode, "ADULT"));

        // applyReviewApproval 은 DRAFT/REVIEWING 에서만 상태를 올린다.
        // 이 조건이 없어지면 재심의 한 번에 판매 중이던 상품이 전부 내려간다.
        assertThat(find(productCode).getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(find(productCode).getRatingCode()).isEqualTo("ADULT");
    }

    @Test
    @DisplayName("같은 승인 이벤트를 다시 받으면 아무것도 하지 않는다")
    void upsertIsGuardedByInbox() {
        String productCode = uniqueProductCode();
        String eventId = UUID.randomUUID().toString();

        productCommandService.upsertFromReview(eventId, EventType.REVIEW_APPROVED,
                approval(productCode, "ALL"));
        productCommandService.upsertFromReview(eventId, EventType.REVIEW_APPROVED,
                approval(productCode, "ADULT"));

        assertThat(find(productCode).getRatingCode()).isEqualTo("ALL");
        // 재발행되면 store 가 같은 문서를 다시 쓰고 색인 캐시가 불필요하게 무효화된다
        assertThat(outboxFor(productCode)).hasSize(1);
        assertThat(processedEventRepository.existsByEventIdAndConsumerGroup(eventId, "catalog")).isTrue();
    }

    @Test
    @DisplayName("판매 시작은 상태를 올리고 새 상태를 실은 이벤트를 낸다")
    void openSalePublishesNewStatus() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        Long productId = find(productCode).getId();

        productCommandService.openSale(productId);

        assertThat(find(productCode).getStatus()).isEqualTo(ProductStatus.ON_SALE);
        List<OutboxEvent> published = outboxFor(productCode);
        // 이벤트가 상태 변경 뒤에 적재되어야 ON_SALE 이 실린다. 순서가 뒤바뀌면
        // store 는 APPROVED 를 받아 상품을 계속 숨긴다.
        assertThat(published).hasSize(2);
        assertThat(published.get(1).getPayload()).contains("ON_SALE");
    }

    @Test
    @DisplayName("판매 중지 후 다시 열 수 있다")
    void suspendedProductCanResume() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        Long productId = find(productCode).getId();
        productCommandService.openSale(productId);

        productCommandService.suspend(productId);
        assertThat(find(productCode).getStatus()).isEqualTo(ProductStatus.SUSPENDED);

        productCommandService.openSale(productId);
        assertThat(find(productCode).getStatus()).isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("없는 상품의 판매 시작은 PRODUCT_NOT_FOUND")
    void openSaleUnknownProduct() {
        assertThatThrownBy(() -> productCommandService.openSale(999_999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("상태를 바꾸면 캐시가 실제로 비워진다")
    void stateChangeEvictsTheCache() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        Long productId = find(productCode).getId();

        // 캐시를 채운다
        ProductView cached = productQueryService.getProduct(productId);
        assertThat(cached.status()).isEqualTo(ProductStatus.APPROVED);

        productCommandService.openSale(productId);

        // @CacheEvict 가 동작하지 않으면 5분간 APPROVED 가 계속 응답된다 —
        // 판매를 열었는데 상품 페이지에서는 살 수 없는 상태가 된다.
        assertThat(productQueryService.getProduct(productId).status())
                .isEqualTo(ProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("재색인은 상품 수만큼 이벤트를 낸다")
    void republishAllEmitsOnePerProduct() {
        String productCode = uniqueProductCode();
        receive(approval(productCode, "ALL"));
        long before = outboxEventRepository.count();
        long productCount = productRepository.count();

        int published = productCommandService.republishAll();

        assertThat(published).isEqualTo((int) productCount);
        assertThat(outboxEventRepository.count() - before).isEqualTo(productCount);
    }
}
