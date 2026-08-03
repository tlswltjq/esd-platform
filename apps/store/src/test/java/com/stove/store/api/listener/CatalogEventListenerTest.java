package com.stove.store.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.Topics;
import com.stove.common.event.payload.ProductChangedEvent;
import com.stove.common.test.EventRecords;
import com.stove.store.core.service.StoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 검색 색인 동기화의 입구.
 *
 * <p>store 에는 Inbox 테이블이 없다 — 문서 ID 가 productId 로 고정된 upsert 라
 * 같은 이벤트를 여러 번 받아도 결과가 같기 때문이다(자연 멱등).
 * 그 전제가 실제로 성립하는지, 그리고 이벤트의 필드가 문서로 온전히 넘어가는지를 본다.
 */
class CatalogEventListenerTest {

    private final StoreService storeService = mock(StoreService.class);
    private final CatalogEventListener listener =
            new CatalogEventListener(storeService, EventRecords.OBJECT_MAPPER);

    private static ProductChangedEvent productChanged(String status) {
        return ProductChangedEvent.of(1L, "GAME-001", "게임 A", 1001L,
                30_000L, "KRW", status, "ALL");
    }

    @Test
    @DisplayName("상품 변경 이벤트의 필드가 색인으로 그대로 넘어간다")
    void productChangedIsIndexed() {
        listener.onCatalogEvent(EventRecords.of(Topics.CATALOG, productChanged("ON_SALE")));

        ArgumentCaptor<ProductChangedEvent> captor = ArgumentCaptor.forClass(ProductChangedEvent.class);
        verify(storeService).indexProduct(captor.capture());

        ProductChangedEvent indexed = captor.getValue();
        assertThat(indexed.productId()).isEqualTo(1L);
        assertThat(indexed.productCode()).isEqualTo("GAME-001");
        assertThat(indexed.status()).isEqualTo("ON_SALE");
        assertThat(indexed.price()).isEqualTo(30_000L);
        assertThat(indexed.sellerId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("관심 없는 eventType 은 색인을 건드리지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onCatalogEvent(EventRecords.ofUnrelatedType(Topics.CATALOG));

        verifyNoInteractions(storeService);
    }

    @Test
    @DisplayName("같은 이벤트를 두 번 받아도 같은 문서를 두 번 쓴다 — Inbox 없이 멱등한 이유")
    void duplicateDeliveryIsIdempotent() {
        ProductChangedEvent event = productChanged("ON_SALE");

        listener.onCatalogEvent(EventRecords.of(Topics.CATALOG, event));
        listener.onCatalogEvent(EventRecords.of(Topics.CATALOG, event));

        ArgumentCaptor<ProductChangedEvent> captor = ArgumentCaptor.forClass(ProductChangedEvent.class);
        verify(storeService, org.mockito.Mockito.times(2)).indexProduct(captor.capture());

        // 문서 ID 는 productId 로 고정이므로 두 번째 쓰기가 첫 번째를 덮어쓸 뿐 새 문서가 생기지 않는다.
        assertThat(captor.getAllValues())
                .extracting(ProductChangedEvent::productId)
                .containsExactly(1L, 1L);
    }

    @Test
    @DisplayName("색인 실패는 예외로 전파된다 — 컨테이너 재시도의 전제조건")
    void indexingFailurePropagates() {
        doThrow(new DataAccessResourceFailureException("elasticsearch unavailable"))
                .when(storeService).indexProduct(any());

        // 여기서 잡으면 색인이 조용히 비어 간다. 검색 결과가 틀린 것은 에러 없이 드러나지 않는다.
        assertThatThrownBy(() ->
                listener.onCatalogEvent(EventRecords.of(Topics.CATALOG, productChanged("ON_SALE"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
