package com.stove.catalog.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.catalog.core.service.ProductCommandService;
import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import com.stove.common.test.EventRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 심의 승인 → 상품 마스터. 상품이 세상에 처음 생기는 지점이다.
 *
 * <p>어댑터가 트랜잭션을 열지 않는다는 점이 이 리스너의 설계다 —
 * 멱등 마킹·상태 변경·색인 이벤트 적재를 한 트랜잭션으로 묶는 책임은
 * {@code ProductCommandService} 에 있다. 여기서는 봉투를 풀어 넘기는 것까지만 확인한다.
 */
class ReviewEventListenerTest {

    private final ProductCommandService productCommandService = mock(ProductCommandService.class);
    private final ReviewEventListener listener =
            new ReviewEventListener(productCommandService, EventRecords.OBJECT_MAPPER);

    private static final ReviewApprovedEvent APPROVED = ReviewApprovedEvent.of(
            1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", "ALL", false);

    @Test
    @DisplayName("승인 이벤트의 상품 정보가 그대로 넘어간다")
    void approvedCreatesProduct() {
        listener.onReviewEvent(EventRecords.of(Topics.REVIEW, APPROVED));

        ArgumentCaptor<ReviewApprovedEvent> captor = ArgumentCaptor.forClass(ReviewApprovedEvent.class);
        verify(productCommandService).upsertFromReview(
                anyString(), eq(EventType.REVIEW_APPROVED), captor.capture());

        assertThat(captor.getValue().productCode()).isEqualTo("GAME-001");
        assertThat(captor.getValue().price()).isEqualTo(30_000L);
        assertThat(captor.getValue().ratingCode()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("반려는 catalog 의 일이 아니다 — studio 만 처리한다")
    void rejectedIsNotCatalogsBusiness() {
        listener.onReviewEvent(EventRecords.of(Topics.REVIEW,
                ReviewRejectedEvent.of(1L, "GAME-001", "RATING", "등급 부적합")));

        // 같은 토픽을 두 서비스가 구독하므로 분기가 틀리면 반려된 게임이 상품으로 등록된다.
        verifyNoInteractions(productCommandService);
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onReviewEvent(EventRecords.ofUnrelatedType(Topics.REVIEW));

        verifyNoInteractions(productCommandService);
    }

    @Test
    @DisplayName("등록 중 일시 장애는 예외로 전파된다 — 컨테이너 재시도의 전제조건")
    void propagatesTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(productCommandService).upsertFromReview(anyString(), anyString(), any());

        assertThatThrownBy(() -> listener.onReviewEvent(EventRecords.of(Topics.REVIEW, APPROVED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
