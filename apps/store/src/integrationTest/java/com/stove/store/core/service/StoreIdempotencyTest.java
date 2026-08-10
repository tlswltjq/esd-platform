package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static ProductChangedEvent event(long productId, String name) {
        return ProductChangedEvent.of(productId, "GAME-IDEM-" + productId, name,
                1001L, 12_000L, "KRW", "ON_SALE", "ALL");
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

    /** ES 는 준실시간이라 검색 기반 집계 전에 refresh 가 필요하다. */
    private long countDocuments() {
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
        return searchRepository.count();
    }
}
