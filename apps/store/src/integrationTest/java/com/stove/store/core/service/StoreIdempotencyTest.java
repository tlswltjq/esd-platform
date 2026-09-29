package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.event.payload.PromotionWindow;
import com.stove.common.event.payload.StorefrontSnapshot;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

/**
 * 진열 색인의 <b>자연 멱등</b>.
 *
 * <p>store 에는 Inbox 가드도 processed_event 테이블도 없다. 문서 ID 를 productId 로 고정한
 * upsert 라 연산 자체가 멱등이기 때문이다 — 멱등성이 인프라가 주는 보장이 아니라
 * 연산의 성질이라는 것을 보여주는 쪽 사례다.
 */
@SpringBootTest
@Import({InfraContainers.Elasticsearch.class, InfraContainers.Kafka.class, InfraContainers.Redis.class})
class StoreIdempotencyTest {

    @Autowired
    StoreService storeService;
    @Autowired
    ProductSearchRepository searchRepository;
    @Autowired
    ElasticsearchOperations elasticsearchOperations;
    @Autowired
    CacheManager cacheManager;

    private static ProductChangedEvent event(long productId, String name) {
        return ProductChangedEvent.of(productId, "GAME-IDEM-" + productId, name,
                1001L, 12_000L, "KRW", "ON_SALE", "ALL");
    }

    @Test
    void indexedPromotionShowsCurrentChargeAndListPrice() {
        long productId = 90_011L;
        String code = "GAME-PROMO-" + productId;
        var window = new PromotionWindow(7L, 2_000L, "PLATFORM",
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600));
        storeService.indexProduct(ProductChangedEvent.ofRelease(productId, code,
                "Promotion game", 1001L, 10_000L, "KRW", "ON_SALE", "ALL",
                11L, 22L, 33L, "BASIC", null, null, List.of(), null, 10L, List.of(window)));
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();

        var view = storeService.detail(code);
        assertThat(view.price()).isEqualTo(8_000L);
        assertThat(view.listPrice()).isEqualTo(10_000L);
        assertThat(view.promotionId()).isEqualTo(7L);
        assertThat(view.discountBearer()).isEqualTo("PLATFORM");
        var cache = cacheManager.getCache("store:featured");
        String key = "promotion-" + productId;
        cache.put(key, List.of(view));
        @SuppressWarnings("unchecked")
        List<com.stove.store.core.domain.StoreProductView> restored =
                (List<com.stove.store.core.domain.StoreProductView>) cache.get(key).get();
        assertThat(restored.getFirst().price()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 색인 문서는 하나다")
    void sameEventIndexedTwiceKeepsOneDocument() {
        long productId = 90_001L;
        long before = countDocuments();

        storeService.indexProduct(event(productId, "멱등 테스트 게임"));
        storeService.indexProduct(event(productId, "멱등 테스트 게임"));

        assertThat(searchRepository.findById(String.valueOf(productId))).isPresent();
        assertThat(countDocuments() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("재전송된 이벤트의 내용이 바뀌었으면 덮어쓴다 — 추가가 아니라 upsert")
    void redeliveryWithNewContentOverwrites() {
        long productId = 90_002L;
        long before = countDocuments();

        storeService.indexProduct(event(productId, "예전 이름"));
        storeService.indexProduct(event(productId, "바뀐 이름"));

        ProductDocument indexed = searchRepository.findById(String.valueOf(productId)).orElseThrow();
        assertThat(indexed.getName()).isEqualTo("바뀐 이름");
        assertThat(countDocuments() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("공개 릴리스의 상세 스냅샷은 ES에 저장되고, 릴리스 전 상품은 조회되지 않는다")
    void onlyReleasedSnapshotIsPublic() {
        long productId = 90_003L;
        String productCode = "GAME-IDEM-" + productId;
        storeService.indexProduct(ProductChangedEvent.of(productId, productCode,
                "공개 전 게임", 1001L, 12_000L, "KRW", "APPROVED", "ALL"));
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
        assertThat(searchRepository.findByVisibleTrue(org.springframework.data.domain.PageRequest.of(0, 10)))
                .noneMatch(document -> productCode.equals(document.getProductCode()));

        StorefrontSnapshot snapshot = new StorefrontSnapshot("공개 게임", "짧은 소개", "상세 소개",
                Map.of("en-US", new StorefrontSnapshot.LocalizedContent("Public Game", "Intro", "Details")),
                List.of("ACTION"), List.of("CO_OP"), "Studio", "Publisher",
                List.of("https://cdn.example/screenshot.png"), List.of("https://cdn.example/trailer.mp4"),
                "https://cdn.example/icon.png", "https://cdn.example/cover.png", List.of("ko-KR", "en-US"),
                "WINDOWS", "8GB RAM", "16GB RAM", List.of("CLOUD_SAVE"),
                "https://support.example", "https://privacy.example", "https://eula.example", List.of("KR"));
        storeService.indexProduct(ProductChangedEvent.ofRelease(productId, productCode,
                "공개 게임", 1001L, 12_000L, "KRW", "ON_SALE", "ALL", 11L, 22L, 33L,
                "BASIC", null, null, List.of(), snapshot, 2));
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();

        assertThat(storeService.detail(productCode).storefront()).isEqualTo(snapshot);
        assertThat(storeService.search("공개 게임", 0, 10)).extracting("productCode").contains(productCode);
    }

    @Test
    void searchPaginatesAndSortsReleasedProducts() {
        storeService.indexProduct(ProductChangedEvent.ofRelease(90_004L, "GAME-SORT-A",
                "정렬테스트 A", 1001L, 1_000L, "KRW", "ON_SALE", "ALL", 21L, 1L, 1L));
        storeService.indexProduct(ProductChangedEvent.ofRelease(90_005L, "GAME-SORT-B",
                "정렬테스트 B", 1001L, 2_000L, "KRW", "ON_SALE", "ALL", 22L, 1L, 1L));
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();

        assertThat(storeService.search("정렬테스트", 0, 1, "PRICE_ASC"))
                .extracting("productCode").containsExactly("GAME-SORT-A");
        assertThat(storeService.search("정렬테스트", 1, 1, "PRICE_ASC"))
                .extracting("productCode").containsExactly("GAME-SORT-B");
        assertThat(storeService.search("정렬테스트", 0, 1, "PRICE_DESC"))
                .extracting("productCode").containsExactly("GAME-SORT-B");
        assertThat(storeService.search("정렬테스트", 0, 1, "NEWEST"))
                .extracting("productCode").containsExactly("GAME-SORT-B");
    }

    /** ES 는 준실시간이라 검색 기반 집계 전에 refresh 가 필요하다. */
    private long countDocuments() {
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
        return searchRepository.count();
    }
}
