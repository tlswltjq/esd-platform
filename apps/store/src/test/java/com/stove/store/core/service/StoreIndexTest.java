package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import com.stove.store.core.domain.StoreProductView;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * 색인 동기화의 의미론. <b>마지막에 쓴 것이 이긴다(last-writer-wins).</b>
 *
 * <p>{@code indexProduct} 는 버전 검사 없는 통짜 upsert 다. 문서 ID 가 productId 로 고정이라
 * 중복 수신에는 강하지만, <b>순서가 뒤집힌 수신에는 무방비</b>다 —
 * 나중에 도착한 옛 상태가 최신 상태를 덮어쓴다.
 *
 * <p>{@code ProductChangedEvent} 에는 버전도 시퀀스도 없어서 서비스 단독으로는 판별할 수 없다.
 * 즉 <b>이 코드의 정확성은 전적으로 상류의 순서 보장에 기대고 있다</b> —
 * 프로듀서 멱등성, 릴레이의 키 웨이브(D-013 · D-014), 컨슈머 단일 스레드.
 * 그 의존을 테스트로 고정해 둔다. 상류가 무너지면 여기서 무슨 일이 벌어지는지가
 * 검색 결과에서 상품이 사라지는 형태로 나타난다(docs/event-ordering.md 4절 시나리오 A).
 *
 * <p>ES 를 띄우지 않는다. 확인하려는 것은 검색 엔진 동작이 아니라
 * <b>문서 ID 가 고정이라 덮어쓰기가 된다</b>는 색인 의미론이다.
 */
class StoreIndexTest {

    private static final String ON_SALE = "ON_SALE";

    /** 색인 대역. 문서 ID → 문서. ES 의 upsert 를 그대로 흉내 낸다. */
    private final Map<String, ProductDocument> index = new LinkedHashMap<>();
    private final ProductSearchRepository repository = mock(ProductSearchRepository.class);
    private StoreService storeService;

    @BeforeEach
    void setUp() {
        when(repository.save(any(ProductDocument.class))).thenAnswer(invocation -> {
            ProductDocument document = invocation.getArgument(0);
            index.put(document.getId(), document);
            return document;
        });
        when(repository.findByStatusOrderByPriceAsc(anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> byStatus(invocation.getArgument(0)));
        when(repository.findByStatusAndNameContaining(anyString(), anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> byStatus(invocation.<String>getArgument(0)).stream()
                        .filter(document -> document.getName().contains(invocation.<String>getArgument(1)))
                        .toList());

        storeService = new StoreService(repository);
    }

    private List<ProductDocument> byStatus(String status) {
        return index.values().stream()
                .filter(document -> status.equals(document.getStatus()))
                .sorted(Comparator.comparingLong(ProductDocument::getPrice))
                .toList();
    }

    private static ProductChangedEvent product(String status, long price) {
        return ProductChangedEvent.of(1L, "GAME-001", "게임 A", 1001L, price, "KRW", status, "ALL");
    }

    @Test
    @DisplayName("색인된 판매중 상품은 검색에 잡힌다")
    void onSaleProductIsSearchable() {
        storeService.indexProduct(product(ON_SALE, 30_000L));

        assertThat(storeService.search(null, 0, 10))
                .extracting(StoreProductView::productCode)
                .containsExactly("GAME-001");
    }

    @Test
    @DisplayName("판매중이 아닌 상품은 검색에서 빠진다")
    void nonSaleProductIsNotSearchable() {
        storeService.indexProduct(product("APPROVED", 30_000L));

        assertThat(storeService.search(null, 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("같은 상품을 다시 색인하면 새 문서가 아니라 덮어쓰기다 — Inbox 없이 멱등한 근거")
    void reindexingReplacesTheSameDocument() {
        storeService.indexProduct(product(ON_SALE, 30_000L));
        storeService.indexProduct(product(ON_SALE, 25_000L));

        assertThat(index).hasSize(1);
        assertThat(storeService.search(null, 0, 10))
                .extracting(StoreProductView::price)
                .containsExactly(25_000L);
    }

    @Test
    @DisplayName("키워드 검색도 판매중 상품만 본다")
    void keywordSearchIsLimitedToOnSale() {
        storeService.indexProduct(product("APPROVED", 30_000L));

        assertThat(storeService.search("게임", 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("순서대로 오면 최신 상태가 남는다 — 정상 경로")
    void latestStatusWinsWhenOrdered() {
        storeService.indexProduct(product("APPROVED", 30_000L));
        storeService.indexProduct(product(ON_SALE, 30_000L));

        assertThat(storeService.search(null, 0, 10)).hasSize(1);
    }

    @Test
    @DisplayName("순서가 뒤집혀 오면 옛 상태가 이긴다 — 상류 순서 보장에 기대고 있다는 뜻")
    void staleStatusWinsWhenReordered() {
        // 심의 승인(APPROVED) → 노출 전환(ON_SALE) 순으로 발행된 것이 뒤집혀 도착한 상황이다.
        // 이벤트에 버전이 없어 서비스는 어느 쪽이 최신인지 구분할 방법이 없다.
        storeService.indexProduct(product(ON_SALE, 30_000L));
        storeService.indexProduct(product("APPROVED", 30_000L));

        // 결과: 판매 중인 상품이 검색에서 사라진다. 예외도 경고도 남지 않는다.
        //
        // 결함으로 등록하지 않는 이유는 고칠 지점이 여기가 아니기 때문이다.
        // 발행 순서는 D-013/D-014 로 닫혔고 컨슈머 층 제약은 EventOrderingRules 가 지킨다.
        // 여기서 막으려면 이벤트에 버전을 실어 조건부 갱신을 해야 하는데,
        // 그건 계약 변경이라 상류가 뚫렸을 때만 값을 한다.
        assertThat(storeService.search(null, 0, 10))
                .as("옛 상태가 최신 상태를 덮어쓴다")
                .isEmpty();
    }
}
