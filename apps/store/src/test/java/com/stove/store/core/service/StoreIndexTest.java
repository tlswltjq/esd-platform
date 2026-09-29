package com.stove.store.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stove.common.core.error.BusinessException;
import com.stove.common.core.error.ErrorCode;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.store.core.domain.ProductDocument;
import com.stove.store.core.domain.ProductSearchRepository;
import com.stove.store.core.domain.StoreProductView;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * 색인 동기화의 의미론. 버전이 있는 이벤트는 늦게 도착한 옛 상태를 건너뛴다.
 *
 * <p>기존 버전 0 이벤트에는 호환을 위해 통짜 upsert를 적용한다. 새 이벤트에는
 * catalog가 증가시키는 projectionVersion이 있어 중복과 순서 역전을 막는다.
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
        when(repository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(index.get(invocation.getArgument(0))));
        when(repository.findByProductCode(anyString()))
                .thenAnswer(invocation -> index.values().stream()
                        .filter(document -> document.getProductCode().equals(invocation.getArgument(0)))
                        .findFirst());
        when(repository.findByVisibleTrue(any(Pageable.class)))
                .thenAnswer(invocation -> visible());
        when(repository.findByVisibleTrueAndNameContaining(anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> visible().stream()
                        .filter(document -> document.getName().contains(invocation.<String>getArgument(0)))
                        .toList());

        storeService = new StoreService(repository);
    }

    private List<ProductDocument> visible() {
        return index.values().stream()
                .filter(document -> Boolean.TRUE.equals(document.getVisible()))
                .sorted(Comparator.comparingLong(ProductDocument::getPrice))
                .toList();
    }

    private static ProductChangedEvent product(String status, long price) {
        return ProductChangedEvent.ofRelease(1L, "GAME-001", "게임 A", 1001L, price, "KRW",
                status, "ALL", "APPROVED".equals(status) ? null : 10L, 20L, 1L);
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
    @DisplayName("에디션 종류와 상위 상품 정보가 검색 결과에 반영된다")
    void productFamilyAppearsInSearchProjection() {
        storeService.indexProduct(ProductChangedEvent.ofRelease(
                2L, "EDITION-001", "게임 A Deluxe", 1001L, 40_000L, "KRW",
                ON_SALE, "ALL", 10L, 20L, 1L,
                "EDITION", "GAME-001", "Deluxe", List.of()));

        StoreProductView view = storeService.search(null, 0, 10).get(0);
        assertThat(view.productKind()).isEqualTo("EDITION");
        assertThat(view.parentProductCode()).isEqualTo("GAME-001");
        assertThat(view.editionName()).isEqualTo("Deluxe");
    }

    @Test
    @DisplayName("공개된 체험판은 검색되지만 구매할 수 없다")
    void releasedDemoIsVisibleButNotPurchasable() {
        storeService.indexProduct(ProductChangedEvent.ofRelease(
                3L, "DEMO-001", "게임 A 체험판", 1001L, 0L, "KRW",
                "APPROVED", "ALL", 11L, 20L, 1L,
                "DEMO", "GAME-001", null, List.of()));

        StoreProductView view = storeService.detail("DEMO-001");
        assertThat(view.visible()).isTrue();
        assertThat(view.purchasable()).isFalse();
        assertThat(storeService.search("체험판", 0, 10)).hasSize(1);
    }

    @Test
    @DisplayName("판매중이 아닌 상품은 검색에서 빠진다")
    void nonSaleProductIsNotSearchable() {
        storeService.indexProduct(product("APPROVED", 30_000L));

        assertThat(storeService.search(null, 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("판매 중단된 릴리스는 상세와 검색에 남지만 구매할 수 없다")
    void suspendedReleaseRemainsVisibleButCannotBePurchased() {
        storeService.indexProduct(product("SUSPENDED", 30_000L));

        StoreProductView detail = storeService.detail("GAME-001");
        assertThat(detail.status()).isEqualTo("SUSPENDED");
        assertThat(detail.purchasable()).isFalse();
        assertThat(storeService.search(null, 0, 10)).hasSize(1);
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
    @DisplayName("[D-020] 범위를 벗어난 페이지 파라미터는 INVALID_REQUEST 로 거절된다")
    void outOfRangePagingIsRejected() {
        assertThatThrownBy(() -> storeService.search(null, -1, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> storeService.search(null, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> storeService.search(null, 0, 10, "INVALID"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("[D-020] 경계값은 허용된다 — page=0, size=1")
    void boundaryPagingIsAccepted() {
        storeService.indexProduct(product(ON_SALE, 30_000L));

        // 거절 쪽만 단언하면 가드가 한 칸 넘치게 조여져도(page <= 0) 통과한다.
        // 허용되어야 하는 값을 함께 고정해야 경계가 고정된다.
        assertThatCode(() -> storeService.search(null, 0, 1)).doesNotThrowAnyException();
        assertThat(storeService.search(null, 0, 1)).hasSize(1);
    }

    @Test
    @DisplayName("공백뿐인 키워드는 키워드 없음과 같다")
    void blankKeywordBehavesLikeNoKeyword() {
        storeService.indexProduct(product(ON_SALE, 30_000L));

        // 공백 검색이 이름 매칭으로 넘어가면 아무것도 안 잡힌다.
        assertThat(storeService.search("   ", 0, 10))
                .extracting(StoreProductView::productCode)
                .containsExactly("GAME-001");
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

    @Test
    @DisplayName("버전이 있는 색인 이벤트는 중복과 이전 상태의 지연 도착을 무시한다")
    void versionedEventRejectsDuplicateAndStaleProjection() {
        storeService.indexProduct(versioned("APPROVED", 0L, 1));
        storeService.indexProduct(versioned(ON_SALE, 30_000L, 2));
        storeService.indexProduct(versioned("APPROVED", 0L, 1));
        storeService.indexProduct(versioned(ON_SALE, 99_000L, 2));

        assertThat(index).hasSize(1);
        assertThat(storeService.detail("GAME-001").price()).isEqualTo(30_000L);
        assertThat(storeService.search(null, 0, 10)).hasSize(1);
    }

    private static ProductChangedEvent versioned(String status, long price, long version) {
        return ProductChangedEvent.ofRelease(1L, "GAME-001", "게임 A", 1001L, price, "KRW",
                status, "ALL", "APPROVED".equals(status) ? null : 10L, 20L, 1L,
                "BASIC", null, null, List.of(), null, version);
    }
}
