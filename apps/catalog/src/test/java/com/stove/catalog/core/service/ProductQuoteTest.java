package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.catalog.core.domain.Product;
import com.stove.catalog.core.domain.ProductRepository;
import com.stove.catalog.core.domain.Quote;
import com.stove.catalog.core.domain.QuoteItem;
import com.stove.common.core.error.BusinessException;
import com.stove.common.event.payload.OrderLine;
import com.stove.common.test.InfraContainers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 검증 게이트 1단계 — 서버 측 금액 재계산.
 *
 * <p>주문 금액의 단일 진실 공급원이 여기다. 클라이언트가 보낸 값은 참고만 하고
 * 실제 금액은 이 계산 결과만 쓴다는 것이 설계 전제이므로,
 * 이 메서드가 신뢰할 수 없는 입력에 어떻게 반응하는지가 곧 시스템의 금액 안전성이다.
 */
@SpringBootTest(properties = "stove.outbox.relay-enabled=false")
@Import({InfraContainers.MySql.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class ProductQuoteTest {

    @Autowired
    ProductQueryService productQueryService;
    @Autowired
    ProductRepository productRepository;

    private Product onSale(long price, String currency) {
        Product product = Product.draft("GAME-" + UUID.randomUUID(), "게임 " + UUID.randomUUID(),
                1001L, price, currency);
        product.applyReviewApproval("ALL");
        product.openSale();
        return productRepository.save(product);
    }

    private Product notOnSale(long price) {
        Product product = Product.draft("GAME-" + UUID.randomUUID(), "미판매 게임", 1001L, price, "KRW");
        return productRepository.save(product);
    }

    @Test
    @DisplayName("총액은 서버 가격 × 수량의 합이다")
    void totalIsServerPriceTimesQuantity() {
        Product first = onSale(30_000L, "KRW");
        Product second = onSale(10_000L, "KRW");

        Quote quote = productQueryService.quote(List.of(
                new QuoteItem(first.getId(), 1),
                new QuoteItem(second.getId(), 3)));

        assertThat(quote.totalAmount()).isEqualTo(60_000L);
        assertThat(quote.currency()).isEqualTo("KRW");
        assertThat(quote.lines()).extracting(OrderLine::productId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("주문 라인에는 정산에 필요한 판매자 ID 가 실린다")
    void lineCarriesSellerId() {
        Product product = onSale(30_000L, "KRW");

        Quote quote = productQueryService.quote(List.of(new QuoteItem(product.getId(), 1)));

        assertThat(quote.lines().get(0).sellerId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("없는 상품이 섞이면 주문이 성립하지 않는다")
    void unknownProductRejectsWholeQuote() {
        Product product = onSale(30_000L, "KRW");

        assertThatThrownBy(() -> productQueryService.quote(List.of(
                new QuoteItem(product.getId(), 1),
                new QuoteItem(999_999_999L, 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("판매 중이 아닌 상품이 섞이면 주문이 성립하지 않는다")
    void notOnSaleProductRejectsWholeQuote() {
        Product sellable = onSale(30_000L, "KRW");
        Product blocked = notOnSale(10_000L);

        assertThatThrownBy(() -> productQueryService.quote(List.of(
                new QuoteItem(sellable.getId(), 1),
                new QuoteItem(blocked.getId(), 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("통화가 다른 상품은 함께 주문할 수 없다")
    void mixedCurrencyIsRejected() {
        Product krw = onSale(30_000L, "KRW");
        Product usd = onSale(20L, "USD");

        assertThatThrownBy(() -> productQueryService.quote(List.of(
                new QuoteItem(krw.getId(), 1),
                new QuoteItem(usd.getId(), 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 상품을 여러 줄로 보내도 각 줄이 그대로 계산된다")
    void duplicateProductLinesAreKeptSeparate() {
        Product product = onSale(30_000L, "KRW");

        Quote quote = productQueryService.quote(List.of(
                new QuoteItem(product.getId(), 1),
                new QuoteItem(product.getId(), 2)));

        assertThat(quote.lines()).hasSize(2);
        assertThat(quote.totalAmount()).isEqualTo(90_000L);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-009] 수량이 0 이하인 주문은 거부되어야 한다")
    void nonPositiveQuantityShouldBeRejected() {
        Product product = onSale(30_000L, "KRW");

        // 수량 검증은 HTTP DTO(QuoteRequest.Item @Min(1))에만 있다.
        // core 진입점에는 없으므로 컨트롤러를 거치지 않는 호출 경로에서는 무방비다.
        // 도메인 규칙은 도메인이 지켜야 한다 — 어댑터는 여러 개가 될 수 있다.
        assertThatThrownBy(() -> productQueryService.quote(List.of(new QuoteItem(product.getId(), 0))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Tag("known-defect")
    @DisplayName("[D-009] 음수 수량으로 총액을 깎을 수 없어야 한다")
    void negativeQuantityShouldNotReduceTotal() {
        Product expensive = onSale(100_000L, "KRW");
        Product cheap = onSale(10_000L, "KRW");

        // 음수 수량이 통과하면 lineAmount 가 음수가 되어 총액을 임의로 낮출 수 있다.
        // 결제 금액 대조(게이트 3)는 이 조작된 총액을 기준값으로 삼으므로 전부 통과한다.
        assertThatThrownBy(() -> productQueryService.quote(List.of(
                new QuoteItem(expensive.getId(), 1),
                new QuoteItem(cheap.getId(), -9))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("현재 동작: 음수 수량이 총액을 깎는다")
    void currentBehaviourNegativeQuantityLowersTotal() {
        Product expensive = onSale(100_000L, "KRW");
        Product cheap = onSale(10_000L, "KRW");

        Quote quote = productQueryService.quote(List.of(
                new QuoteItem(expensive.getId(), 1),
                new QuoteItem(cheap.getId(), -9)));

        // 10만원짜리 게임을 1만원에 살 수 있게 된다.
        assertThat(quote.totalAmount()).isEqualTo(10_000L);
    }
}
