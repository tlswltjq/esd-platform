package com.stove.review.api.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stove.common.event.EventType;
import com.stove.common.event.Topics;
import com.stove.common.event.payload.BuildUploadedEvent;
import com.stove.common.event.payload.GameRegisteredEvent;
import com.stove.common.test.EventRecords;
import com.stove.review.core.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 게임 등록 → 심의 접수. 심의 대기열의 입구다.
 *
 * <p>studio 토픽은 review 와 download 가 함께 구독하는데 관심 이벤트가 서로 다르다 —
 * review 는 {@code GameRegistered}, download 는 {@code BuildUploaded}.
 * 분기가 틀리면 빌드 업로드마다 심의가 새로 접수되는 식으로 어긋난다.
 */
class StudioEventListenerTest {

    private final ReviewService reviewService = mock(ReviewService.class);
    private final StudioEventListener listener =
            new StudioEventListener(reviewService, EventRecords.OBJECT_MAPPER);

    private static final GameRegisteredEvent REGISTERED = GameRegisteredEvent.of(
            1L, "GAME-001", "게임 A", 1001L, 30_000L, "KRW", false);

    @Test
    @DisplayName("게임 등록은 심의 접수로 이어지고 등록 정보가 그대로 넘어간다")
    void gameRegisteredIsReceived() {
        listener.onStudioEvent(EventRecords.of(Topics.STUDIO, REGISTERED));

        ArgumentCaptor<GameRegisteredEvent> captor = ArgumentCaptor.forClass(GameRegisteredEvent.class);
        verify(reviewService).receive(anyString(), eq(EventType.GAME_REGISTERED), captor.capture());

        assertThat(captor.getValue().productCode()).isEqualTo("GAME-001");
        assertThat(captor.getValue().title()).isEqualTo("게임 A");
    }

    @Test
    @DisplayName("빌드 업로드는 review 의 일이 아니다 — download 담당")
    void buildUploadedIsNotReviewsBusiness() {
        listener.onStudioEvent(EventRecords.of(Topics.STUDIO, BuildUploadedEvent.of(
                1L, "GAME-001", "1.0.0", 1024L, "sha256:abc", "s3://bucket/build")));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("관심 없는 eventType 은 아무 일도 하지 않는다")
    void unrelatedEventTypeIsIgnored() {
        listener.onStudioEvent(EventRecords.ofUnrelatedType(Topics.STUDIO));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("접수 중 일시 장애는 예외로 전파된다 — 삼키면 심의가 누락된다")
    void propagatesTransientFailure() {
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(reviewService).receive(anyString(), anyString(), any());

        assertThatThrownBy(() -> listener.onStudioEvent(EventRecords.of(Topics.STUDIO, REGISTERED)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
