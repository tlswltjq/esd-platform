package com.stove.studio.api.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.ReviewApprovedEvent;
import com.stove.common.event.payload.ReviewRejectedEvent;
import com.stove.common.test.EventRecords;
import com.stove.studio.core.service.StudioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 심의 결과를 창작자에게 되돌리는 리스너. 승인과 반려 <b>양쪽</b>을 받는 유일한 곳이다.
 *
 * <p>catalog 는 승인만 보고 반려는 무시한다. 두 서비스가 같은 토픽을 구독하면서
 * 분기가 다르므로, 어느 한쪽의 분기가 틀리면 심의 결과가 반쪽만 반영된다.
 */
class ReviewEventListenerTest {

    private final StudioService studioService = mock(StudioService.class);
    private final ReviewEventListener listener =
            new ReviewEventListener(studioService, EventRecords.OBJECT_MAPPER);

    private static final ReviewApprovedEvent APPROVED = ReviewApprovedEvent.of(
            1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", "ALL", false);
    private static final ReviewRejectedEvent REJECTED =
            ReviewRejectedEvent.of(1L, "GAME-001", "RATING", "등급 부적합");

    @Test
    @DisplayName("승인은 등급과 함께 반영된다")
    void approvalIsApplied() {
        listener.onReviewEvent(EventRecords.of(Topics.REVIEW, APPROVED));

        verify(studioService).applyApproval(
                anyString(), eq(EventType.REVIEW_APPROVED), eq("GAME-001"), eq("ALL"));
    }

    @Test
    @DisplayName("반려는 사유와 함께 반영된다 — catalog 가 버리는 이벤트를 여기서 받는다")
    void rejectionIsApplied() {
        listener.onReviewEvent(EventRecords.of(Topics.REVIEW, REJECTED));

        verify(studioService).applyRejection(
                anyString(), eq(EventType.REVIEW_REJECTED), eq("GAME-001"), eq("등급 부적합"));
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onReviewEvent(EventRecords.ofUnrelatedType(Topics.REVIEW));

        verifyNoInteractions(studioService);
    }

    @Test
    @DisplayName("승인 반영 중 일시 장애는 예외로 전파된다")
    void propagatesFailureOnApproval() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(studioService).applyApproval(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onReviewEvent(EventRecords.of(Topics.REVIEW, APPROVED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @DisplayName("반려 반영의 예외도 그대로 전파된다 — 두 분기의 정책이 같다")
    void propagatesFailureOnRejection() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(studioService).applyRejection(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> listener.onReviewEvent(EventRecords.of(Topics.REVIEW, REJECTED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
